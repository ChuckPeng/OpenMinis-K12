package httpx

import (
	"encoding/json"
	"net/http"
)

// ErrorBody is the standard API error envelope.
type ErrorBody struct {
	Error   string `json:"error"`
	Message string `json:"message"`
}

// WriteJSON writes a JSON response.
func WriteJSON(w http.ResponseWriter, status int, v any) {
	w.Header().Set("Content-Type", "application/json; charset=utf-8")
	w.WriteHeader(status)
	_ = json.NewEncoder(w).Encode(v)
}

// WriteError writes a snake_case error body.
func WriteError(w http.ResponseWriter, status int, code, message string) {
	WriteJSON(w, status, ErrorBody{Error: code, Message: message})
}

// WriteText writes a plain text body (WeCom echostr).
func WriteText(w http.ResponseWriter, status int, body string) {
	w.Header().Set("Content-Type", "text/plain; charset=utf-8")
	w.WriteHeader(status)
	_, _ = w.Write([]byte(body))
}

// DecodeJSON decodes a JSON body with DisallowUnknownFields left off for
// forward-compatible admin writes that omit optional secrets.
func DecodeJSON(r *http.Request, v any) error {
	defer r.Body.Close()
	dec := json.NewDecoder(r.Body)
	return dec.Decode(v)
}
