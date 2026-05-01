package app.pwhs.blockads.utils

import android.content.Context
import android.content.pm.PackageManager
import android.net.ConnectivityManager
import android.os.Build
import android.os.SystemClock
import android.system.OsConstants
import timber.log.Timber
import java.io.File
import java.net.InetAddress
import java.net.InetSocketAddress
import java.util.concurrent.ConcurrentHashMap

/**
 * Resolves the source app name for a DNS query by mapping the connection
 * to the owning UID, then looking up the app label via PackageManager.
 *
 * On Android 10+ (API 29+): Uses the official ConnectivityManager.getConnectionOwnerUid() API.
 * On older versions: Falls back to parsing /proc/net/udp (and /proc/net/udp6).
 */
class AppNameResolver(private val context: Context) {
    // Cache UID -> app name to avoid repeated PackageManager lookups
    private val uidToAppNameCache = ConcurrentHashMap<Int, String>()

    /**
     * Resolved app identity containing both display name and package name.
     */
    data class AppIdentity(val appName: String, val packageName: String)

    /**
     * Resolve the app name that owns the given DNS query connection.
     * Returns the app label (e.g. "Chrome") or empty string if not found.
     *
     * @param sourcePort  Local UDP source port of the DNS query
     * @param sourceIp    Source IP address bytes from the DNS packet
     * @param destIp      Destination IP address bytes from the DNS packet
     * @param destPort    Destination port (typically 53)
     */
    fun resolve(sourcePort: Int, sourceIp: ByteArray, destIp: ByteArray, destPort: Int): String {
        val uid = findUidForConnection(sourcePort, sourceIp, destIp, destPort)
        if (uid < 0) return ""
        return getAppNameForUid(uid)
    }

    /**
     * Resolve both app name and package name in a single UID lookup.
     * Avoids duplicate UID resolution on the hot path.
     */
    fun resolveIdentity(sourcePort: Int, sourceIp: ByteArray, destIp: ByteArray, destPort: Int): AppIdentity {
        val uid = findUidForConnection(sourcePort, sourceIp, destIp, destPort)
        if (uid < 0) return AppIdentity("", "")
        return AppIdentity(
            appName = getAppNameForUid(uid),
            packageName = getPackageNameForUid(uid)
        )
    }

    /**
     * Find the UID owning the connection.
     * Uses official API on Android 10+, falls back to /proc/net/udp on older
     * versions or when the official API returns UID_UNKNOWN (Root Proxy mode:
     * iptables REDIRECT rewrites the 5-tuple so getConnectionOwnerUid can't
     * see the original socket pair).
     */
    private fun findUidForConnection(
        sourcePort: Int,
        sourceIp: ByteArray,
        destIp: ByteArray,
        destPort: Int
    ): Int {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            val officialUid = try {
                val cm = context.getSystemService(Context.CONNECTIVITY_SERVICE) as ConnectivityManager
                val local = InetSocketAddress(InetAddress.getByAddress(sourceIp), sourcePort)
                val remote = InetSocketAddress(InetAddress.getByAddress(destIp), destPort)
                cm.getConnectionOwnerUid(OsConstants.IPPROTO_UDP, local, remote)
            } catch (_: Exception) {
                -1
            }
            if (officialUid >= 0) return officialUid
            // Fall through to cached /proc/net lookup for Root Proxy mode.
        }

        return findUidFromProcNet(sourcePort)
    }

    // Cached snapshot of /proc/net/udp{,6}. A full read is amortised across
    // every DNS query that arrives inside [PROC_NET_TTL_MS] of the snapshot.
    // Without this, root-shell reads serialise and DNS threads back up under
    // load (issue #130). -1 entries (port not in snapshot) are cached too so
    // we don't re-snapshot on every miss.
    @Volatile private var procNetSnapshot: Map<Int, Int>? = null
    @Volatile private var procNetSnapshotAtMs: Long = 0L
    private val procNetLock = Any()

    /**
     * Fallback: Look up /proc/net/udp and /proc/net/udp6 to find the UID owning the given port.
     * Used on Android versions before 10 (API < 29) and on Android 10+ in
     * Root Proxy mode (where getConnectionOwnerUid returns -1 because
     * iptables REDIRECT rewrites the 5-tuple).
     */
    private fun findUidFromProcNet(port: Int): Int {
        val snapshot = getOrRefreshProcNetSnapshot()
        return snapshot[port] ?: -1
    }

    private fun getOrRefreshProcNetSnapshot(): Map<Int, Int> {
        val now = SystemClock.elapsedRealtime()
        val cached = procNetSnapshot
        if (cached != null && now - procNetSnapshotAtMs < PROC_NET_TTL_MS) {
            return cached
        }
        synchronized(procNetLock) {
            // Double-check after acquiring the lock — another thread may
            // have refreshed while we were waiting.
            val cached2 = procNetSnapshot
            if (cached2 != null && SystemClock.elapsedRealtime() - procNetSnapshotAtMs < PROC_NET_TTL_MS) {
                return cached2
            }
            val fresh = readProcNetSnapshot()
            procNetSnapshot = fresh
            procNetSnapshotAtMs = SystemClock.elapsedRealtime()
            return fresh
        }
    }

    /**
     * Build a port → UID map from /proc/net/udp{,6}. Tries unprivileged
     * read first (works on API < 29 and for our own sockets). On Android
     * 10+ SELinux hides other apps' sockets, so we fall back to a single
     * root-shell read via libsu's persistent shell.
     */
    private fun readProcNetSnapshot(): Map<Int, Int> {
        val map = HashMap<Int, Int>()
        parseProcFile("/proc/net/udp", map)
        parseProcFile("/proc/net/udp6", map)
        if (map.isEmpty()) {
            try {
                val result = com.topjohnwu.superuser.Shell.cmd(
                    "cat /proc/net/udp /proc/net/udp6 2>/dev/null"
                ).exec()
                if (result.isSuccess) {
                    for (line in result.out) {
                        parseProcLine(line, map)
                    }
                }
            } catch (e: Exception) {
                Timber.d("Root /proc/net lookup failed: ${e.message}")
            }
        }
        return map
    }

    private fun parseProcFile(path: String, out: MutableMap<Int, Int>) {
        try {
            File(path).bufferedReader(Charsets.UTF_8).use { reader ->
                reader.readLine() ?: return
                var line = reader.readLine()
                while (line != null) {
                    parseProcLine(line, out)
                    line = reader.readLine()
                }
            }
        } catch (e: Exception) {
            Timber.d("Cannot read $path: ${e.message}")
        }
    }

    private fun parseProcLine(line: String, out: MutableMap<Int, Int>) {
        try {
            val parts = line.trim().split("\\s+".toRegex())
            if (parts.size < 8) return
            val localAddress = parts[1]
            val colonIndex = localAddress.lastIndexOf(':')
            if (colonIndex < 0) return
            val port = localAddress.substring(colonIndex + 1).toInt(16)
            val uid = parts[7].toIntOrNull() ?: return
            // First-writer-wins: the kernel can list a port across both
            // udp and udp6, but the UID is the same.
            out.putIfAbsent(port, uid)
        } catch (_: Exception) {
            // Skip header / malformed lines
        }
    }

    private fun getAppNameForUid(uid: Int): String {
        uidToAppNameCache[uid]?.let { return it }

        val pm = context.packageManager
        val packages = pm.getPackagesForUid(uid)
        if (packages.isNullOrEmpty()) {
            // System UIDs
            val name = when {
                uid == 0 -> "System (root)"
                uid == 1000 -> "Android System"
                uid < 10000 -> "System ($uid)"
                else -> ""
            }
            uidToAppNameCache[uid] = name
            return name
        }

        // Use the first package's app label
        val appName = try {
            val appInfo = pm.getApplicationInfo(packages[0], 0)
            pm.getApplicationLabel(appInfo).toString()
        } catch (e: PackageManager.NameNotFoundException) {
            Timber.e("Package not found for UID $uid: ${e.message}")
            packages[0]
        }

        uidToAppNameCache[uid] = appName
        return appName
    }

    // Cache UID -> package name
    private val uidToPackageNameCache = ConcurrentHashMap<Int, String>()

    private fun getPackageNameForUid(uid: Int): String {
        uidToPackageNameCache[uid]?.let { return it }

        val pm = context.packageManager
        val packages = pm.getPackagesForUid(uid)
        if (packages.isNullOrEmpty()) return ""

        val packageName = packages[0]
        uidToPackageNameCache[uid] = packageName
        return packageName
    }

    private companion object {
        // Short enough that recycled ephemeral ports don't get mis-attributed
        // to a previous owner, long enough to collapse a burst of DNS queries
        // into a single /proc/net read.
        const val PROC_NET_TTL_MS = 3_000L
    }
}
