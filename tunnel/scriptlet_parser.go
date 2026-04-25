package tunnel

import (
	"strings"
)

// ─────────────────────────────────────────────────────────────────────────────
// scriptlet_parser.go — parses EasyList +js() rules.
//
// Supported rule formats:
//   ##+js(name)
//   ##+js(name, arg1)
//   ##+js(name, arg1, arg2)
//   domain1.com,domain2.com##+js(name, args)
//   ~excluded.com##+js(name, args)
//
// We are deliberately lenient: invalid rules are skipped silently
// rather than failing the whole filter list. uBlock-style snippets
// (e.g., ##+js(scriptlet:something)) are unsupported and skipped.
// ─────────────────────────────────────────────────────────────────────────────

// parseScriptletRules takes the raw text of a filter list and extracts
// every +js() rule it can parse. Lines that aren't +js() rules are
// ignored.
func parseScriptletRules(content string) []ScriptletRule {
	var out []ScriptletRule
	lines := strings.Split(content, "\n")
	for _, line := range lines {
		line = strings.TrimSpace(line)
		if line == "" || strings.HasPrefix(line, "!") || strings.HasPrefix(line, "#") {
			continue
		}
		rule, ok := parseOneScriptletRule(line)
		if !ok {
			continue
		}
		out = append(out, rule)
	}
	return out
}

// parseOneScriptletRule parses a single line. Returns (rule, true) on
// success or zero value + false otherwise.
func parseOneScriptletRule(line string) (ScriptletRule, bool) {
	// Find the "##+js(" anchor. EasyList also uses "#@#+js(" for
	// exception rules — we ignore those (no current call-site to apply
	// negative scriptlets).
	idx := strings.Index(line, "##+js(")
	if idx < 0 {
		return ScriptletRule{}, false
	}
	domainsStr := line[:idx]
	rest := line[idx+len("##+js("):]
	closeIdx := strings.LastIndex(rest, ")")
	if closeIdx < 0 {
		return ScriptletRule{}, false
	}
	body := rest[:closeIdx]

	// Split body by comma — naive but matches AdGuard's parser for
	// scriptlets that don't take comma-bearing args (the common case).
	parts := splitArgs(body)
	if len(parts) == 0 {
		return ScriptletRule{}, false
	}
	name := strings.TrimSpace(parts[0])
	if name == "" {
		return ScriptletRule{}, false
	}

	args := make([]string, 0, len(parts)-1)
	for _, p := range parts[1:] {
		args = append(args, strings.TrimSpace(p))
	}

	var domains []string
	if domainsStr != "" {
		for _, d := range strings.Split(domainsStr, ",") {
			d = strings.TrimSpace(strings.ToLower(d))
			if d != "" {
				domains = append(domains, d)
			}
		}
	}

	return ScriptletRule{
		Domains: domains,
		Name:    name,
		Args:    args,
	}, true
}

// splitArgs splits a +js() body by comma, honouring single-quoted
// segments so 'foo, bar' counts as one arg. Whitespace is left to the
// caller to trim.
func splitArgs(s string) []string {
	var out []string
	var cur strings.Builder
	inQuote := false
	var quoteCh byte
	for i := 0; i < len(s); i++ {
		c := s[i]
		switch {
		case inQuote:
			if c == quoteCh {
				inQuote = false
				continue
			}
			cur.WriteByte(c)
		case c == '\'' || c == '"':
			inQuote = true
			quoteCh = c
		case c == ',':
			out = append(out, cur.String())
			cur.Reset()
		default:
			cur.WriteByte(c)
		}
	}
	out = append(out, cur.String())
	return out
}

// buildScriptletStore organises parsed rules into the lookup structure
// used by the per-host invocation builder.
func buildScriptletStore(rules []ScriptletRule) *scriptletStore {
	s := &scriptletStore{
		byHost: make(map[string][]ScriptletRule),
	}
	for _, r := range rules {
		positive := positiveDomains(r)
		if len(positive) == 0 {
			s.all = append(s.all, r)
			continue
		}
		for _, d := range positive {
			s.byHost[d] = append(s.byHost[d], r)
		}
	}
	return s
}

// positiveDomains filters rule.Domains to entries that don't start
// with "~" (the negation prefix).
func positiveDomains(r ScriptletRule) []string {
	var out []string
	for _, d := range r.Domains {
		if !strings.HasPrefix(d, "~") {
			out = append(out, d)
		}
	}
	return out
}
