package admin

import (
	"net/url"
	"unicode/utf8"
)

// validModelBaseURL checks URL syntax only. It does not look up a vendor,
// resolve a name, or reject an unknown host.
func validModelBaseURL(raw string) bool {
	if raw == "" || utf8.RuneCountInString(raw) > 512 {
		return false
	}
	u, err := url.ParseRequestURI(raw)
	if err != nil || u == nil {
		return false
	}
	if u.Scheme != "http" && u.Scheme != "https" {
		return false
	}
	return u.Host != ""
}

func acceptableLabel(s string, maxRunes int) bool {
	if s == "" || utf8.RuneCountInString(s) > maxRunes {
		return false
	}
	for _, r := range s {
		if r < 0x20 || r == 0x7f {
			return false
		}
	}
	return true
}
