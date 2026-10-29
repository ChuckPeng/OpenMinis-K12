package wecom

import (
	"context"
	"encoding/xml"
	"log"
	"net/http"
	"strings"
	"time"

	"github.com/ChuckPeng/OpenMinis-K12/server/internal/crypto"
	"github.com/ChuckPeng/OpenMinis-K12/server/internal/httpx"
	"github.com/ChuckPeng/OpenMinis-K12/server/internal/store"
)

// CallbackHandler serves GET/POST /wecom/callback/{callback_key}.
type CallbackHandler struct {
	Store  *store.Store
	Master crypto.MasterKey
	OnCard func(ctx context.Context, app *store.WecomApp, ev CardEvent) error
}

// CardEvent is a parsed template_card_event (J12).
type CardEvent struct {
	FromUserName string
	EventKey     string
	TaskID       string
	ResponseCode string
	AgentID      int
	CreateTime   int64
}

type xmlMsg struct {
	ToUserName   string `xml:"ToUserName"`
	FromUserName string `xml:"FromUserName"`
	CreateTime   int64  `xml:"CreateTime"`
	MsgType      string `xml:"MsgType"`
	Event        string `xml:"Event"`
	EventKey     string `xml:"EventKey"`
	TaskId       string `xml:"TaskId"`
	ResponseCode string `xml:"ResponseCode"`
	AgentID      int    `xml:"AgentID"`
	Content      string `xml:"Content"`
}

func (h *CallbackHandler) ServeHTTP(w http.ResponseWriter, r *http.Request) {
	key := strings.TrimPrefix(r.URL.Path, "/wecom/callback/")
	key = strings.Trim(key, "/")
	if key == "" || strings.Contains(key, "/") {
		http.NotFound(w, r)
		return
	}
	ctx := r.Context()
	app, err := h.Store.GetWecomAppByCallbackKey(ctx, key)
	if err != nil || app == nil || !app.Enabled {
		http.NotFound(w, r)
		return
	}
	token := app.Token
	aesKeyPlain, err := h.aesKey(app)
	if err != nil {
		httpx.WriteError(w, http.StatusInternalServerError, "config_error", "callback crypto unavailable")
		return
	}
	crypt, err := crypto.NewWXBizMsgCrypt(token, aesKeyPlain, app.CorpID)
	if err != nil {
		httpx.WriteError(w, http.StatusInternalServerError, "config_error", "callback crypto unavailable")
		return
	}

	q := r.URL.Query()
	msgSig := q.Get("msg_signature")
	ts := q.Get("timestamp")
	nonce := q.Get("nonce")

	switch r.Method {
	case http.MethodGet:
		echostr := q.Get("echostr")
		plain, err := crypt.VerifyURL(msgSig, ts, nonce, echostr)
		if err != nil {
			httpx.WriteError(w, http.StatusForbidden, "bad_signature", "verify failed")
			return
		}
		httpx.WriteText(w, http.StatusOK, plain)
	case http.MethodPost:
		body := readBody(r)
		plain, err := crypt.DecryptMsg(msgSig, ts, nonce, body)
		if err != nil {
			httpx.WriteError(w, http.StatusForbidden, "bad_signature", "decrypt failed")
			return
		}
		// Acknowledge within 5s with empty body first (spec).
		w.WriteHeader(http.StatusOK)
		_, _ = w.Write([]byte{})
		go h.handlePlain(context.Background(), app, plain)
	default:
		http.Error(w, "method not allowed", http.StatusMethodNotAllowed)
	}
}

func (h *CallbackHandler) aesKey(app *store.WecomApp) (string, error) {
	if app.AESKeyEnc == "" {
		return "", errNoKey
	}
	b, err := h.Master.Open(app.AESKeyEnc)
	if err != nil {
		return "", err
	}
	return string(b), nil
}

var errNoKey = errString("aes key missing")

type errString string

func (e errString) Error() string { return string(e) }

func readBody(r *http.Request) string {
	defer r.Body.Close()
	buf := make([]byte, 0, 4096)
	tmp := make([]byte, 4096)
	for {
		n, err := r.Body.Read(tmp)
		if n > 0 {
			buf = append(buf, tmp[:n]...)
		}
		if err != nil {
			break
		}
		if len(buf) > 1<<20 {
			break
		}
	}
	return string(buf)
}

func (h *CallbackHandler) handlePlain(ctx context.Context, app *store.WecomApp, plain string) {
	ctx, cancel := context.WithTimeout(ctx, 30*time.Second)
	defer cancel()
	var msg xmlMsg
	if err := xml.Unmarshal([]byte(plain), &msg); err != nil {
		log.Printf("wecom callback: xml parse error (redacted)")
		return
	}
	switch {
	case msg.MsgType == "event" && msg.Event == "template_card_event":
		_ = h.Store.SetButtonCallbackResult(ctx, app.ID, true)
		ev := CardEvent{
			FromUserName: msg.FromUserName,
			EventKey:     msg.EventKey,
			TaskID:       msg.TaskId,
			ResponseCode: msg.ResponseCode,
			AgentID:      msg.AgentID,
			CreateTime:   msg.CreateTime,
		}
		if h.OnCard != nil {
			if err := h.OnCard(ctx, app, ev); err != nil {
				log.Printf("wecom card handler: %v", err)
			}
		}
		_ = h.Store.AppendAudit(ctx, "wecom:"+msg.FromUserName, "template_card_event", msg.EventKey+"/"+msg.TaskId)
	case msg.MsgType == "text":
		// Fallback: parent replies with the 6-digit backup code.
		code := strings.TrimSpace(msg.Content)
		if len(code) == 6 && isDigits(code) {
			if err := ApproveByBackupCode(ctx, h.Store, app, msg.FromUserName, code); err != nil {
				log.Printf("wecom backup code: %v", err)
			}
		}
	}
}

func isDigits(s string) bool {
	for _, r := range s {
		if r < '0' || r > '9' {
			return false
		}
	}
	return true
}
