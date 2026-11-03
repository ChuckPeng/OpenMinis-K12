package admin

import (
	"net/http"
	"strings"
	"unicode/utf8"

	"github.com/ChuckPeng/OpenMinis-K12/server/internal/httpx"
	"github.com/ChuckPeng/OpenMinis-K12/server/internal/store"
)

// tabletModelView is the admin read model. It has no API key field.
type tabletModelView struct {
	Scope           string                `json:"scope"`
	Version         int64                 `json:"version"`
	Cleared         bool                  `json:"cleared"`
	ProviderType    string                `json:"provider_type"`
	BaseURL         string                `json:"base_url"`
	ModelName       string                `json:"model_name"`
	VisionModelName string                `json:"vision_model_name"`
	APIKeySet       bool                  `json:"api_key_set"`
	APIKeyChangedAt string                `json:"api_key_changed_at,omitempty"`
	UpdatedAt       string                `json:"updated_at,omitempty"`
	Deliveries      []tabletModelDelivery `json:"deliveries"`
}

type tabletModelDelivery struct {
	DeviceID    string `json:"device_id"`
	DeviceLabel string `json:"device_label"`
	Status      string `json:"status"`
	Version     int64  `json:"version"`
	DeliveredAt string `json:"delivered_at,omitempty"`
}

func (s *Server) tabletModel(w http.ResponseWriter, r *http.Request) {
	switch r.Method {
	case http.MethodGet:
		s.withAuth(false, s.getTabletModel)(w, r)
	case http.MethodPut:
		s.withAuth(true, s.putTabletModel)(w, r)
	case http.MethodDelete:
		s.withAuth(true, s.deleteTabletModel)(w, r)
	default:
		http.Error(w, "method not allowed", http.StatusMethodNotAllowed)
	}
}

func (s *Server) getTabletModel(w http.ResponseWriter, r *http.Request) {
	view, err := s.loadTabletModelView(r)
	if err != nil {
		httpx.WriteError(w, http.StatusInternalServerError, "internal_error", "failed to read config")
		return
	}
	httpx.WriteJSON(w, http.StatusOK, view)
}

func (s *Server) putTabletModel(w http.ResponseWriter, r *http.Request) {
	var body struct {
		ProviderType    string `json:"provider_type"`
		BaseURL         string `json:"base_url"`
		ModelName       string `json:"model_name"`
		VisionModelName string `json:"vision_model_name"`
		APIKey          string `json:"api_key"`
	}
	if err := httpx.DecodeJSON(r, &body); err != nil {
		httpx.WriteError(w, http.StatusBadRequest, "bad_request", "invalid json")
		return
	}
	providerType := strings.TrimSpace(body.ProviderType)
	baseURL := strings.TrimSpace(body.BaseURL)
	modelName := strings.TrimSpace(body.ModelName)
	vision := strings.TrimSpace(body.VisionModelName)
	if !acceptableLabel(providerType, 64) {
		httpx.WriteError(w, http.StatusBadRequest, "provider_type_required", "provider type is required")
		return
	}
	if !validModelBaseURL(baseURL) {
		httpx.WriteError(w, http.StatusBadRequest, "invalid_base_url", "base URL must be an http or https URL")
		return
	}
	if !acceptableLabel(modelName, 128) {
		httpx.WriteError(w, http.StatusBadRequest, "model_name_required", "model name is required")
		return
	}
	if vision != "" && !acceptableLabel(vision, 128) {
		httpx.WriteError(w, http.StatusBadRequest, "invalid_vision_model", "vision model name is too long")
		return
	}
	apiKey := body.APIKey
	if strings.TrimSpace(apiKey) != "" && utf8.RuneCountInString(apiKey) > 4096 {
		httpx.WriteError(w, http.StatusBadRequest, "invalid_api_key", "api key is too long")
		return
	}

	ctx := r.Context()
	familyID, err := s.Store.EnsureFamily(ctx, "home")
	if err != nil {
		httpx.WriteError(w, http.StatusInternalServerError, "internal_error", "failed to resolve family")
		return
	}
	current, err := s.Store.GetTabletModel(ctx, familyID)
	if err != nil {
		httpx.WriteError(w, http.StatusInternalServerError, "internal_error", "failed to read config")
		return
	}
	save := store.TabletModelSave{
		ProviderType:    providerType,
		BaseURL:         baseURL,
		ModelName:       modelName,
		VisionModelName: vision,
		UpdatedBy:       s.currentUser(r),
	}
	if strings.TrimSpace(apiKey) != "" {
		enc, err := s.Master.Seal([]byte(apiKey))
		if err != nil {
			httpx.WriteError(w, http.StatusInternalServerError, "internal_error", "failed to store secret")
			return
		}
		save.KeyEnc = enc
		save.KeyChanged = true
	} else if current.APIKeyEnc == "" || current.Cleared {
		httpx.WriteError(w, http.StatusBadRequest, "api_key_required", "api key is required")
		return
	}
	version, err := s.Store.SaveTabletModel(ctx, familyID, save)
	if err != nil {
		httpx.WriteError(w, http.StatusInternalServerError, "internal_error", "failed to save config")
		return
	}
	// Audit target is family id and version only. The key is not included.
	_ = s.Store.AppendAudit(ctx, s.currentUser(r), "tablet_model_save", familyID+":v"+itoa(version))
	view, err := s.loadTabletModelView(r)
	if err != nil {
		httpx.WriteError(w, http.StatusInternalServerError, "internal_error", "failed to read config")
		return
	}
	httpx.WriteJSON(w, http.StatusOK, view)
}

func (s *Server) deleteTabletModel(w http.ResponseWriter, r *http.Request) {
	ctx := r.Context()
	familyID, err := s.Store.EnsureFamily(ctx, "home")
	if err != nil {
		httpx.WriteError(w, http.StatusInternalServerError, "internal_error", "failed to resolve family")
		return
	}
	version, err := s.Store.ClearTabletModel(ctx, familyID, s.currentUser(r))
	if err != nil {
		httpx.WriteError(w, http.StatusInternalServerError, "internal_error", "failed to clear config")
		return
	}
	_ = s.Store.AppendAudit(ctx, s.currentUser(r), "tablet_model_clear", familyID+":v"+itoa(version))
	view, err := s.loadTabletModelView(r)
	if err != nil {
		httpx.WriteError(w, http.StatusInternalServerError, "internal_error", "failed to read config")
		return
	}
	httpx.WriteJSON(w, http.StatusOK, view)
}

func (s *Server) loadTabletModelView(r *http.Request) (tabletModelView, error) {
	ctx := r.Context()
	familyID, err := s.Store.EnsureFamily(ctx, "home")
	if err != nil {
		return tabletModelView{}, err
	}
	cfg, err := s.Store.GetTabletModel(ctx, familyID)
	if err != nil {
		return tabletModelView{}, err
	}
	deliveries, err := s.Store.ListTabletModelDeliveries(ctx, familyID)
	if err != nil {
		return tabletModelView{}, err
	}
	view := tabletModelView{
		Scope:           "family",
		Version:         cfg.Version,
		Cleared:         cfg.Cleared || cfg.Version == 0,
		ProviderType:    cfg.ProviderType,
		BaseURL:         cfg.BaseURL,
		ModelName:       cfg.ModelName,
		VisionModelName: cfg.VisionModelName,
		APIKeySet:       cfg.APIKeyEnc != "" && !cfg.Cleared,
		APIKeyChangedAt: cfg.APIKeyChangedAt,
		UpdatedAt:       cfg.UpdatedAt,
		Deliveries:      make([]tabletModelDelivery, 0, len(deliveries)),
	}
	if cfg.Cleared {
		view.ProviderType = ""
		view.BaseURL = ""
		view.ModelName = ""
		view.VisionModelName = ""
		view.APIKeySet = false
		view.APIKeyChangedAt = ""
	}
	for _, d := range deliveries {
		view.Deliveries = append(view.Deliveries, tabletModelDelivery{
			DeviceID:    d.DeviceID,
			DeviceLabel: d.DeviceLabel,
			Status:      d.Status,
			Version:     d.Version,
			DeliveredAt: d.DeliveredAt,
		})
	}
	return view, nil
}

func itoa(v int64) string {
	if v == 0 {
		return "0"
	}
	neg := v < 0
	if neg {
		v = -v
	}
	var buf [20]byte
	i := len(buf)
	for v > 0 {
		i--
		buf[i] = byte('0' + v%10)
		v /= 10
	}
	if neg {
		i--
		buf[i] = '-'
	}
	return string(buf[i:])
}
