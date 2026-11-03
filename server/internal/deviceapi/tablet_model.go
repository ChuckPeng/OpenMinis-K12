package deviceapi

import (
	"net/http"

	"github.com/ChuckPeng/OpenMinis-K12/server/internal/httpx"
)

// deviceTabletModel is sent only on the dedicated paired-device fetch.
// Heartbeat must not use this struct.
type deviceTabletModel struct {
	Version         int64  `json:"version"`
	Cleared         bool   `json:"cleared"`
	ProviderType    string `json:"provider_type,omitempty"`
	BaseURL         string `json:"base_url,omitempty"`
	ModelName       string `json:"model_name,omitempty"`
	VisionModelName string `json:"vision_model_name,omitempty"`
	APIKey          string `json:"api_key,omitempty"`
}

func (s *Server) getTabletModel(w http.ResponseWriter, r *http.Request) {
	if r.Method != http.MethodGet {
		http.Error(w, "method not allowed", http.StatusMethodNotAllowed)
		return
	}
	dev := getContextDevice(r)
	if dev == nil {
		httpx.WriteError(w, http.StatusUnauthorized, "unauthorized", "device context missing")
		return
	}
	ctx := r.Context()
	cfg, err := s.Store.GetTabletModel(ctx, dev.FamilyID)
	if err != nil {
		httpx.WriteError(w, http.StatusInternalServerError, "internal_error", "failed to read config")
		return
	}
	resp := deviceTabletModel{Version: cfg.Version, Cleared: cfg.Cleared || cfg.Version == 0}
	if cfg.Version > 0 && !cfg.Cleared {
		plain, err := s.Master.Open(cfg.APIKeyEnc)
		if err != nil || len(plain) == 0 {
			httpx.WriteError(w, http.StatusInternalServerError, "internal_error", "failed to read secret")
			return
		}
		resp.ProviderType = cfg.ProviderType
		resp.BaseURL = cfg.BaseURL
		resp.ModelName = cfg.ModelName
		resp.VisionModelName = cfg.VisionModelName
		resp.APIKey = string(plain)
		resp.Cleared = false
	}
	if err := s.Store.RecordTabletModelDelivery(ctx, dev.FamilyID, dev.ID, resp.Version); err != nil {
		httpx.WriteError(w, http.StatusInternalServerError, "internal_error", "failed to record delivery")
		return
	}
	// Device id, version, and time. The key is not part of the audit target.
	if err := s.Store.AppendAudit(ctx, "device:"+dev.ID, "tablet_model_deliver", dev.ID+":v"+itoa64(resp.Version)); err != nil {
		httpx.WriteError(w, http.StatusInternalServerError, "internal_error", "failed to record delivery")
		return
	}
	httpx.WriteJSON(w, http.StatusOK, resp)
}

func itoa64(v int64) string {
	if v == 0 {
		return "0"
	}
	var buf [20]byte
	i := len(buf)
	for v > 0 {
		i--
		buf[i] = byte('0' + v%10)
		v /= 10
	}
	return string(buf[i:])
}
