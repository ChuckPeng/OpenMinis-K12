package httpx

import (
	"net"
	"net/http"
	"strings"
)

// TrustedProxies decides whether X-Forwarded-* headers may be trusted.
type TrustedProxies struct {
	nets []*net.IPNet
}

// ParseTrustedProxies builds a matcher from IP/CIDR strings. Empty list means
// ignore all X-Forwarded-* headers.
func ParseTrustedProxies(list []string) (*TrustedProxies, error) {
	tp := &TrustedProxies{}
	for _, p := range list {
		p = strings.TrimSpace(p)
		if p == "" {
			continue
		}
		if _, n, err := net.ParseCIDR(p); err == nil {
			tp.nets = append(tp.nets, n)
			continue
		}
		ip := net.ParseIP(p)
		if ip == nil {
			return nil, &net.ParseError{Type: "IP/CIDR", Text: p}
		}
		bits := 128
		if ip.To4() != nil {
			bits = 32
			ip = ip.To4()
		}
		tp.nets = append(tp.nets, &net.IPNet{IP: ip, Mask: net.CIDRMask(bits, bits)})
	}
	return tp, nil
}

func (t *TrustedProxies) trusted(r *http.Request) bool {
	if t == nil || len(t.nets) == 0 {
		return false
	}
	host, _, err := net.SplitHostPort(r.RemoteAddr)
	if err != nil {
		host = r.RemoteAddr
	}
	ip := net.ParseIP(host)
	if ip == nil {
		return false
	}
	for _, n := range t.nets {
		if n.Contains(ip) {
			return true
		}
	}
	return false
}

// ClientIP returns the client address for rate limiting. Spoofed
// X-Forwarded-For from untrusted sources is ignored (R6).
func (t *TrustedProxies) ClientIP(r *http.Request) string {
	host, _, err := net.SplitHostPort(r.RemoteAddr)
	if err != nil {
		host = r.RemoteAddr
	}
	if !t.trusted(r) {
		return host
	}
	xff := r.Header.Get("X-Forwarded-For")
	if xff == "" {
		return host
	}
	parts := strings.Split(xff, ",")
	return strings.TrimSpace(parts[0])
}

// IsHTTPS reports whether the external request was HTTPS (for Secure cookies).
func (t *TrustedProxies) IsHTTPS(r *http.Request) bool {
	if r.TLS != nil {
		return true
	}
	if !t.trusted(r) {
		return false
	}
	return strings.EqualFold(r.Header.Get("X-Forwarded-Proto"), "https")
}
