package admin

import (
	"context"
	"database/sql"
	"fmt"
	"io/fs"
	"net/http"
	"strings"
	"time"

	"github.com/ChuckPeng/OpenMinis-K12/server/internal/config"
	"github.com/ChuckPeng/OpenMinis-K12/server/internal/crypto"
	"github.com/ChuckPeng/OpenMinis-K12/server/internal/deviceapi"
	"github.com/ChuckPeng/OpenMinis-K12/server/internal/httpx"
	"github.com/ChuckPeng/OpenMinis-K12/server/internal/store"
	"github.com/ChuckPeng/OpenMinis-K12/server/internal/version"
	"github.com/ChuckPeng/OpenMinis-K12/server/internal/wecom"
)

const (
	sessionCookie = "k12_session"
	idleTTL       = 30 * time.Minute
	absoluteTTL   = 12 * time.Hour
	lockAfter     = 5
	lockFor       = 15 * time.Minute
)

// Server is the admin HTTP API + embedded UI.
type Server struct {
	Cfg      config.Config
	Store    *store.Store
	Master   crypto.MasterKey
	Proxies  *httpx.TrustedProxies
	UI       fs.FS
	WeCom    *wecom.Client
	Notifier *deviceapi.Notifier
}

func (s *Server) Handler() http.Handler {
	mux := http.NewServeMux()
	mux.HandleFunc("/healthz", s.healthz)
	mux.HandleFunc("/readyz", s.readyz)
	mux.HandleFunc("/metrics", s.metrics)
	mux.HandleFunc("/api/v1/admin/setup", s.requireMethod("POST", s.setup))
	mux.HandleFunc("/api/v1/admin/login", s.requireMethod("POST", s.login))
	mux.HandleFunc("/api/v1/admin/logout", s.requireMethod("POST", s.withAuth(true, s.logout)))
	mux.HandleFunc("/api/v1/admin/session", s.requireMethod("GET", s.withAuth(false, s.sessionInfo)))
	mux.HandleFunc("/api/v1/admin/wecom-apps", s.wecomApps)
	mux.HandleFunc("/api/v1/admin/wecom-apps/", s.wecomAppsSub)
	mux.HandleFunc("/api/v1/admin/parents", s.parents)
	mux.HandleFunc("/api/v1/admin/parents/", s.parentsSub)
	mux.HandleFunc("/api/v1/admin/unlock-requests", s.requireMethod("GET", s.withAuth(false, s.listUnlocks)))
	mux.HandleFunc("/api/v1/admin/devices", s.requireMethod("GET", s.withAuth(false, s.listDevices)))
	mux.HandleFunc("/api/v1/admin/devices/", s.devicesSub)
	mux.HandleFunc("/api/v1/admin/pair-codes", s.requireMethod("POST", s.withAuth(true, s.createPairCode)))
	mux.HandleFunc("/api/v1/admin/alerts", s.requireMethod("GET", s.withAuth(false, s.listAlerts)))
	mux.HandleFunc("/api/v1/admin/alerts/", s.alertsSub)
	mux.HandleFunc("/api/v1/admin/children", s.requireMethod("GET", s.withAuth(false, s.listChildren)))
	mux.HandleFunc("/api/v1/admin/children/", s.childrenSub)
	mux.HandleFunc("/api/v1/admin/reports", s.requireMethod("GET", s.withAuth(false, s.listReports)))
	mux.HandleFunc("/api/v1/admin/reports/generate", s.requireMethod("POST", s.withAuth(true, s.generateReport)))
	mux.HandleFunc("/api/v1/admin/reports/", s.reportsSub)
	mux.HandleFunc("/api/v1/admin/audit", s.requireMethod("GET", s.withAuth(false, s.listAudit)))
	mux.HandleFunc("/api/v1/admin/settings", s.settings)
	mux.HandleFunc("/api/v1/admin/status", s.requireMethod("GET", s.withAuth(false, s.status)))
	mux.HandleFunc("/api/v1/admin/j12/simulate", s.requireMethod("POST", s.withAuth(true, s.simulateJ12)))
	mux.HandleFunc("/api/v1/admin/j12/fallback-code", s.requireMethod("POST", s.withAuth(true, s.createFallbackUnlock)))
	if s.UI != nil {
		fileServer := http.FileServer(http.FS(s.UI))
		mux.Handle("/", spaHandler(fileServer))
	} else {
		mux.HandleFunc("/", func(w http.ResponseWriter, r *http.Request) {
			if r.URL.Path != "/" {
				http.NotFound(w, r)
				return
			}
			httpx.WriteText(w, 200, "openminis-k12-server admin "+version.String())
		})
	}
	return mux
}

func spaHandler(static http.Handler) http.Handler {
	return http.HandlerFunc(func(w http.ResponseWriter, r *http.Request) {
		if strings.HasPrefix(r.URL.Path, "/api/") {
			http.NotFound(w, r)
			return
		}
		// Try static; fall back to index.html for client routes.
		static.ServeHTTP(w, r)
	})
}

func (s *Server) requireMethod(method string, next http.HandlerFunc) http.HandlerFunc {
	return func(w http.ResponseWriter, r *http.Request) {
		if r.Method != method {
			http.Error(w, "method not allowed", http.StatusMethodNotAllowed)
			return
		}
		next(w, r)
	}
}

type ctxKey int

const ctxSession ctxKey = 1

func (s *Server) withAuth(needCSRF bool, next http.HandlerFunc) http.HandlerFunc {
	return func(w http.ResponseWriter, r *http.Request) {
		sess, err := s.loadSession(r)
		if err != nil || sess == nil {
			httpx.WriteError(w, http.StatusUnauthorized, "unauthorized", "login required")
			return
		}
		if needCSRF && r.Method != http.MethodGet && r.Method != http.MethodHead {
			if r.Header.Get("X-CSRF-Token") == "" || r.Header.Get("X-CSRF-Token") != sess.CSRFToken {
				httpx.WriteError(w, http.StatusForbidden, "csrf", "missing or invalid CSRF token")
				return
			}
		}
		_ = s.Store.TouchSession(r.Context(), sess.ID)
		ctx := context.WithValue(r.Context(), ctxSession, sess)
		next(w, r.WithContext(ctx))
	}
}

func (s *Server) loadSession(r *http.Request) (*store.Session, error) {
	c, err := r.Cookie(sessionCookie)
	if err != nil || c.Value == "" {
		return nil, err
	}
	sess, err := s.Store.GetSession(r.Context(), c.Value)
	if err != nil || sess == nil {
		return nil, err
	}
	abs, err := time.Parse(time.RFC3339Nano, sess.AbsoluteExpiresAt)
	if err != nil || time.Now().UTC().After(abs) {
		_ = s.Store.DeleteSession(r.Context(), sess.ID)
		return nil, sql.ErrNoRows
	}
	last, err := time.Parse(time.RFC3339Nano, sess.LastSeenAt)
	if err != nil || time.Now().UTC().After(last.Add(idleTTL)) {
		_ = s.Store.DeleteSession(r.Context(), sess.ID)
		return nil, sql.ErrNoRows
	}
	return sess, nil
}

func (s *Server) setSessionCookie(w http.ResponseWriter, r *http.Request, id string) {
	secure := s.Proxies.IsHTTPS(r) || strings.HasPrefix(strings.ToLower(s.Cfg.PublicBaseURL), "https://")
	http.SetCookie(w, &http.Cookie{
		Name:     sessionCookie,
		Value:    id,
		Path:     "/",
		HttpOnly: true,
		SameSite: http.SameSiteStrictMode,
		Secure:   secure,
		MaxAge:   int(absoluteTTL.Seconds()),
	})
}

func (s *Server) clearSessionCookie(w http.ResponseWriter) {
	http.SetCookie(w, &http.Cookie{Name: sessionCookie, Value: "", Path: "/", MaxAge: -1, HttpOnly: true, SameSite: http.SameSiteStrictMode})
}

func (s *Server) healthz(w http.ResponseWriter, _ *http.Request) {
	httpx.WriteText(w, 200, "ok")
}

func (s *Server) readyz(w http.ResponseWriter, r *http.Request) {
	if err := s.Store.Ping(r.Context()); err != nil {
		httpx.WriteText(w, 503, "unavailable")
		return
	}
	httpx.WriteText(w, 200, "ok")
}

func (s *Server) metrics(w http.ResponseWriter, r *http.Request) {
	n, _ := s.Store.CountOutboxPending(r.Context())
	w.Header().Set("Content-Type", "text/plain; version=0.0.4")
	_, _ = w.Write([]byte("# TYPE openminis_outbox_pending gauge\n"))
	_, _ = w.Write([]byte(fmt.Sprintf("openminis_outbox_pending %d\n", n)))
}
