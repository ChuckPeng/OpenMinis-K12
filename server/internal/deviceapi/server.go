package deviceapi

import (
	"net/http"
	"strings"

	"github.com/ChuckPeng/OpenMinis-K12/server/internal/httpx"
	"github.com/ChuckPeng/OpenMinis-K12/server/internal/store"
)

// Server is the device-facing HTTP port (default 8080). Admin paths return 404 (R7).
type Server struct {
	Store    *store.Store
	Callback http.Handler
}

// Handler mounts health endpoints, WeCom callback, and stubs for future device API.
func (s *Server) Handler() http.Handler {
	mux := http.NewServeMux()
	mux.HandleFunc("/healthz", func(w http.ResponseWriter, _ *http.Request) {
		httpx.WriteText(w, 200, "ok")
	})
	mux.HandleFunc("/readyz", func(w http.ResponseWriter, r *http.Request) {
		if err := s.Store.Ping(r.Context()); err != nil {
			httpx.WriteText(w, 503, "unavailable")
			return
		}
		httpx.WriteText(w, 200, "ok")
	})
	if s.Callback != nil {
		mux.Handle("/wecom/", s.Callback)
	}
	mux.HandleFunc("/", func(w http.ResponseWriter, r *http.Request) {
		// R7: management paths on the public/device port must 404.
		if strings.HasPrefix(r.URL.Path, "/api/v1/admin") || r.URL.Path == "/metrics" {
			http.NotFound(w, r)
			return
		}
		if strings.HasPrefix(r.URL.Path, "/api/v1/device/") {
			// Device API lands in M5b; acknowledge path exists as 501 for now.
			httpx.WriteError(w, http.StatusNotImplemented, "not_implemented", "device API arrives in M5b")
			return
		}
		http.NotFound(w, r)
	})
	return mux
}
