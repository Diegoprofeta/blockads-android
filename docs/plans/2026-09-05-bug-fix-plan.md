# Kế hoạch xử lý bug BlockAds

Ngày lập: 2026-09-05. Baseline khảo sát: `fca5b43`.
Nguồn: https://github.com/pass-with-high-score/blockads-android/issues

## Phạm vi và trạng thái

- Snapshot có 59 issue mở, gồm 21 issue mang nhãn bug hoặc tiêu đề [BUG].
- Kế hoạch này chưa xác nhận mọi báo cáo còn tái hiện trên HEAD và chưa triển khai bản sửa.
- Các kết luận trong bình luận GitHub là đầu mối điều tra, không thay thế kiểm tra code và thiết bị.
- Ưu tiên: không mất cấu hình, không làm mất mạng, thực thi bảo vệ đúng và chẩn đoán được lỗi.
- Nhóm theo triệu chứng để điều tra; không đóng trùng issue trước khi xác nhận cùng nguyên nhân.
- Link từng issue có dạng https://github.com/pass-with-high-score/blockads-android/issues/NUMBER.

## 0. Xác nhận baseline trước khi sửa

- [ ] Build HEAD và ghi phiên bản, commit, Android, thiết bị, nguồn APK cho mỗi lần tái hiện.
- [ ] Với từng issue, ghi mode VPN DNS-only / HTTPS filtering / WireGuard / Root Proxy, upstream/fallback DNS, Private DNS, filter và loại mạng.
- [ ] So sánh phiên bản được báo cáo với HEAD nếu có thể; phân loại: còn lỗi, đã sửa cần xác nhận, thiếu dữ liệu, giới hạn tính năng.
- [ ] Thu log có thời gian, DNS latency, transport, kết quả upstream/fallback và lifecycle; bỏ thông tin nhạy cảm trước khi chia sẻ.
- [ ] Giữ fixture filter cố định khi kiểm thử parser để tránh kết quả thay đổi theo danh sách trực tuyến.

Code đã thấy khi lập kế hoạch: SettingsViewModel có export/import whitelistDomains; lịch sử gần đây có sửa đọc /proc và root shell trên đường xử lý DNS. Vì vậy #225 và #130 phải kiểm tra hồi quy trước, không mặc định thiếu implementation.

## 1. Cấu hình và DNS — triển khai đợt đầu

### #236: Profile làm mất lựa chọn filter

- [ ] Tái hiện chọn 12 filter, chuyển sang mặc định rồi quay lại, kể cả sau khi tắt/mở app.
- [ ] Kiểm tra ProfileManager, ProtectionProfileDao, ProfileViewModel và đồng bộ filter khi đổi profile.
- [ ] Xác định nơi ghi đè snapshot profile; bảo đảm lưu/áp dụng nhất quán và không bị đồng bộ remote ghi đè.
- Hoàn thành khi: ID và trạng thái của cả 12 filter được giữ qua chuyển profile, restart và refresh catalog; profile khác không bị đổi theo.

### #225, #222: Backup/restore thiếu dữ liệu

- [ ] Lập bảng đối chiếu trường SettingsBackup với preferences, database và profile; kiểm tra thứ tự restore profile có ghi đè DNS/filter vừa nhập không.
- [ ] Tái hiện export → kiểm tra JSON → restore trong dữ liệu app sạch trên thiết bị thử nghiệm.
- [ ] Kiểm tra whitelist domain, DNS chính/dự phòng, custom rule, firewall, app whitelist và profile; phân biệt import gộp với thay thế theo hành vi sản phẩm hiện có.
- [ ] Bổ sung schema/migration hoặc sửa thứ tự restore chỉ khi xác nhận thiếu; tách logic backup khỏi UI nếu cần mở rộng.
- Hoàn thành khi: round-trip giữ các trường thuộc phạm vi backup; file cũ vẫn đọc được; file hỏng được báo lỗi và không để cấu hình bị cập nhật dở dang.

### #228: DNS khác giao thức bị xem là trùng

- [ ] Kiểm tra DnsProviderViewModel, DnsProvider, DnsProtocol và quy tắc chuẩn hóa endpoint.
- [ ] Dùng danh tính endpoint có giao thức, host, port và path khi phù hợp; tránh so sánh chỉ IP.
- Hoàn thành khi: DNS thường và tls://1.1.1.1 cùng tồn tại; endpoint tương đương thật sự vẫn bị phát hiện trùng; có ca IPv6, port và DoH path.

## 2. Filter chặn nhầm và cập nhật

### #201, #217; tham chiếu #214

- [ ] Thu rule chính xác khiến youtube.com, raw.githubusercontent.com, t.co và AMP bị chặn; #214 chỉ tham chiếu #201, chưa có bằng chứng lỗi riêng.
- [ ] Kiểm tra compiler/parser Kotlin, tunnel/compiler.go và backend nếu danh sách được biên dịch từ xa.
- [ ] Kiểm tra rule URL/path, modifier, exception có bị biến thành chặn cả domain không; phân biệt false positive từ nguồn filter với lỗi parser.
- [ ] Không dùng whitelist cứng cho vài website để che lỗi chuyển đổi rule.
- Hoàn thành khi: fixture rule hợp lệ chặn đúng; rule không hỗ trợ không bị nới thành chặn toàn domain; exception đúng; các trang báo lỗi truy cập được với fixture đã sửa và domain quảng cáo kiểm soát vẫn bị chặn.

### #170: Cập nhật filter không chạy hoặc bị treo

- [ ] Kiểm tra FilterUpdateScheduler/Worker: Wi-Fi-only khi VPN bật, constraints, retry/backoff, timeout, hủy và cập nhật thủ công.
- [ ] Tái hiện app chạy nền lâu, Wi-Fi có VPN, mạng lỗi và chuyển mạng; việc chuyển mạng tự kích hoạt update là yêu cầu tính năng riêng.
- [ ] Hiển thị lần thành công gần nhất và lý do skip/fail; giữ bản filter tốt gần nhất nếu tải/biên dịch thất bại.
- Hoàn thành khi: job chạy khi đủ điều kiện, lỗi kết thúc hữu hạn và có lý do; cập nhật thủ công không quay vô hạn; filter cũ còn dùng được.

## 3. Kết nối và DNS routing — thay đổi nhỏ, kiểm tra riêng từng mode

| Issue | Điều tra và hướng xử lý | Tiêu chí hoàn thành |
|---|---|---|
| #226, #172 | Tái hiện DNS lỗi/chậm ~5 giây; kiểm tra resolver.go, timeout/fallback, network binding, cache và chuyển Wi-Fi/mobile. Xác minh endpoint cấu hình có truy cập được. | Không lặp timeout trên mạng tốt; fallback hoạt động theo cấu hình; lỗi upstream có chẩn đoán rõ. |
| #196, #104 | Home Assistant và IP LAN 10.x không truy cập; phân biệt DNS, IP trực tiếp, route LAN và HTTPS interception. | Truy cập LAN và HA hoạt động trong mode hỗ trợ; tắt filter không còn bị chặn ngoài ý muốn. |
| #212 | MacroDroid ping timeout; xác định đường ICMP trong mode thực tế và mức hỗ trợ của tunnel. | Ping hoạt động nếu được hỗ trợ; nếu chưa hỗ trợ, ghi giới hạn rõ và không tuyên bố đã sửa bằng whitelist app. |
| #137 | Kiểm tra split DNS đi tới DNS nội bộ qua WireGuard trong resolver.go, wireguard.go, outbound_wireguard.go. | Domain nội bộ được resolve trong tunnel; domain công khai đi đúng cấu hình; không rò truy vấn split DNS ra resolver công khai khi tunnel lỗi. |
| #145 | Tái hiện DNS ISP với Private DNS/DoH của app, fallback và IPv6; đọc lại thay đổi bị revert. | Chứng minh truy vấn được hỗ trợ đi đúng resolver; phát hiện/cảnh báo đường bypass chưa hỗ trợ; không gây mất mạng. |
| #162 | Kiểm tra lockdown với DNS-only và các mode full-route thực sự hỗ trợ forwarding. | DNS-only giải thích rõ bất tương thích; chỉ công bố hỗ trợ lockdown khi TCP/UDP/DNS được kiểm chứng đầy đủ. |

Không áp dụng default route toàn bộ traffic chỉ để sửa DNS leak nếu engine của mode đó chưa forward được traffic tương ứng. Full-route/DoT interception là thay đổi kiến trúc riêng nếu cần, không gộp vào hotfix.

## 4. Lifecycle, firewall và profile Android

| Issue | Công việc | Tiêu chí hoàn thành |
|---|---|---|
| #206 | Kiểm tra BootReceiver, RootProxyResumeWorker, RootProxyService, root sẵn sàng chậm và giới hạn khởi động foreground service. | Reboot tự khôi phục khi đã bật auto-reconnect; khi tắt tùy chọn thì không tự bật; retry có giới hạn và không tạo service trùng. |
| #221; liên quan #244 | Thu log service bị dừng, crash, Doze và thay đổi mạng; kiểm tra VpnRetryManager/VpnResumeWorker. | Phục hồi được các trường hợp hệ điều hành cho phép; thao tác tắt chủ động của người dùng được tôn trọng. Watchdog 5–100 giây là yêu cầu tính năng, không mặc định là giải pháp. |
| #152 | Tái hiện WhatsApp với firewall, UID, IPv4/IPv6 và kết nối đang mở; phân biệt push hệ thống với traffic trực tiếp của app. | Traffic app bị chặn đúng Wi-Fi/mobile theo rule; bỏ chặn hoạt động; log xác định được UID hoặc nêu rõ không xác định. |
| #148 | Kiểm tra VpnUtils và phát hiện VPN khác trong Work Profile so với Personal Profile. | Hai profile độc lập chạy đúng trường hợp Android hỗ trợ; vẫn xử lý xung đột VPN trong cùng profile. |
| #130 | Retest Root Proxy Android 16 + KernelSU sau các sửa /proc/root shell hiện có; kiểm tra iptables IPv4/IPv6 và log mới. | DNS không bị nghẽn bởi tra UID; blocking có kiểm chứng; lỗi quyền/rule được báo rõ. Chỉ đóng sau xác nhận. |

## 5. UI, hiệu năng và các issue cần phân loại

- [ ] #203: sửa focus/D-pad cho Android TV; điều hướng tới nút bảo vệ, bật/tắt và quay lại được bằng remote, không cần touch.
- [ ] #119: tách báo cáo thiếu log HTTPS và DNS NextDNS khỏi yêu cầu Shizuku; tái hiện HTTPS on/off, kiểm tra resolver và log theo đúng loại traffic.
- [ ] #85: yêu cầu URL/app, phiên bản, mode, filter và request cụ thể; không dùng điểm ad-test hay YouTube đơn lẻ làm tiêu chí đúng cho mọi khả năng lọc.
- [ ] #151: đo thời gian khởi tạo theo bước tải/biên dịch filter, khởi tạo engine và VPN; chọn tối ưu từ số đo, ghi trước/sau trên cùng thiết bị.
- [ ] #95: kiểm tra danh sách app whitelist có cập nhật khi cài/gỡ app hoặc quay lại màn hình; yêu cầu tùy biến navigation là tính năng riêng.
- [ ] #56 / #164: xác nhận allowlist chưa hỗ trợ hay parser sai. Nếu chưa hỗ trợ, báo rõ loại danh sách; triển khai allowlist là hạng mục riêng, có kiểm tra exception precedence và số rule.
- [ ] #167: xem nhãn giờ biểu đồ chồng nhau và hiển thị DNS fallback; các yêu cầu test DNS trước lưu, thống kê và UI khác phân loại riêng.
- #243 và các yêu cầu DoQ, userscript, proxy, DPI, per-app domain rule không thuộc đợt sửa bug này.

## Kiểm tra và phát hành

- Mỗi bản sửa có fixture/test hồi quy phản ánh hành vi lỗi, tránh test chỉ lặp lại implementation.
- Kotlin: chạy unit test liên quan và `./gradlew assembleDebug`; chọn đúng task flavor nếu dự án yêu cầu.
- Go: chạy test liên quan rồi `go test ./...` trong tunnel khi sửa engine; build lại AAR theo workflow repository và kiểm tra tích hợp APK.
- Ma trận tối thiểu theo phạm vi thay đổi: Wi-Fi/mobile, IPv4/IPv6, upstream thành công/thất bại; VPN thường/HTTPS/WireGuard/Root Proxy khi bị ảnh hưởng.
- Với lifecycle: reboot, Doze, tắt chủ động, mạng mất/kết nối lại. Với dữ liệu: restart, import cũ, file lỗi và round-trip.
- Thiếu thiết bị Root/TV/Work Profile: ghi rõ chưa kiểm chứng, cung cấp build và bước tái hiện cho người báo; không đánh dấu pass thay cho kiểm thử thật.
- Mỗi PR ghi issue, nguyên nhân đã xác nhận, hành vi trước/sau, kiểm tra đã chạy và giới hạn còn lại.
- Chỉ đóng issue khi có bằng chứng sửa trên phiên bản cụ thể; thay đổi filter/backend phải ghi artifact/version tương ứng.

## Thứ tự commit/PR dự kiến

1. #236: giữ lựa chọn filter theo profile.
2. #225/#222: sửa phần backup/restore còn tái hiện, dùng chung test round-trip.
3. #228: phân biệt endpoint DNS theo transport.
4. #201/#217: sửa parser/filter sau khi xác định rule lỗi; tách PR nếu khác nguyên nhân.
5. #170: cập nhật filter và lý do thất bại.
6. #206 rồi #221: boot/recovery, mỗi nguyên nhân một PR.
7. #137: split DNS WireGuard.
8. #226/#172/#196/#104/#212: từng bản sửa kết nối theo kết quả tái hiện.
9. #145/#162: chẩn đoán bypass/lockdown trước; kiến trúc full-route riêng nếu cần.
10. #152/#148/#130: firewall, Work Profile, xác nhận Root Proxy.
11. #203 và các báo cáo phụ: TV, log HTTPS, refresh danh sách, hiệu năng.

Thứ tự có thể đổi khi xác nhận lỗi mất mạng diện rộng hoặc mất dữ liệu. Mỗi mục chỉ được đánh dấu hoàn thành khi tiêu chí hành vi và kiểm tra tương ứng đạt; không xem commit kế hoạch là đã sửa bug.
