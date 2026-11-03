package wecom

import (
	"context"
	"encoding/json"
	"fmt"
	"io"
	"net/http"
	"net/url"
	"strings"
	"sync"
	"time"
)

// Client talks to WeCom API via a configurable base URL (outbound proxy).
type Client struct {
	HTTP    *http.Client
	BaseURL string

	mu        sync.Mutex
	token     string
	expiresAt time.Time
}

// NewClient returns a client. baseURL defaults to the official API host.
func NewClient(baseURL string) *Client {
	if baseURL == "" {
		baseURL = "https://qyapi.weixin.qq.com"
	}
	baseURL = strings.TrimRight(baseURL, "/")
	return &Client{
		HTTP:    &http.Client{Timeout: 15 * time.Second},
		BaseURL: baseURL,
	}
}

type tokenResp struct {
	ErrCode     int    `json:"errcode"`
	ErrMsg      string `json:"errmsg"`
	AccessToken string `json:"access_token"`
	ExpiresIn   int    `json:"expires_in"`
}

// GetToken fetches (and caches) an access token. Token is never returned to callers of admin APIs.
func (c *Client) GetToken(ctx context.Context, corpid, secret string) error {
	c.mu.Lock()
	defer c.mu.Unlock()
	if c.token != "" && time.Now().Before(c.expiresAt.Add(-5*time.Minute)) {
		return nil
	}
	return c.refreshLocked(ctx, corpid, secret)
}

func (c *Client) refreshLocked(ctx context.Context, corpid, secret string) error {
	u := fmt.Sprintf("%s/cgi-bin/gettoken?corpid=%s&corpsecret=%s",
		c.BaseURL, url.QueryEscape(corpid), url.QueryEscape(secret))
	req, err := http.NewRequestWithContext(ctx, http.MethodGet, u, nil)
	if err != nil {
		return err
	}
	res, err := c.HTTP.Do(req)
	if err != nil {
		return err
	}
	defer res.Body.Close()
	body, _ := io.ReadAll(io.LimitReader(res.Body, 1<<20))
	var tr tokenResp
	if err := json.Unmarshal(body, &tr); err != nil {
		return err
	}
	if tr.ErrCode != 0 || tr.AccessToken == "" {
		return fmt.Errorf("gettoken errcode=%d", tr.ErrCode)
	}
	c.token = tr.AccessToken
	sec := tr.ExpiresIn
	if sec <= 0 {
		sec = 7200
	}
	c.expiresAt = time.Now().Add(time.Duration(sec) * time.Second)
	return nil
}

// TestToken tries gettoken without exposing the token (admin test-token).
func (c *Client) TestToken(ctx context.Context, corpid, secret string) error {
	c.mu.Lock()
	defer c.mu.Unlock()
	c.token = ""
	return c.refreshLocked(ctx, corpid, secret)
}

// SendText sends a text message. Used for fallback unlock notices and test-send.
func (c *Client) SendText(ctx context.Context, corpid, secret string, agentID int, toUser, content string) error {
	if err := c.GetToken(ctx, corpid, secret); err != nil {
		return err
	}
	payload := map[string]any{
		"touser":  toUser,
		"msgtype": "text",
		"agentid": agentID,
		"text":    map[string]string{"content": content},
	}
	return c.postMessage(ctx, corpid, secret, payload)
}

func (c *Client) postMessage(ctx context.Context, corpid, secret string, payload map[string]any) error {
	c.mu.Lock()
	token := c.token
	c.mu.Unlock()
	body, _ := json.Marshal(payload)
	u := fmt.Sprintf("%s/cgi-bin/message/send?access_token=%s", c.BaseURL, url.QueryEscape(token))
	req, err := http.NewRequestWithContext(ctx, http.MethodPost, u, strings.NewReader(string(body)))
	if err != nil {
		return err
	}
	req.Header.Set("Content-Type", "application/json")
	res, err := c.HTTP.Do(req)
	if err != nil {
		return err
	}
	defer res.Body.Close()
	raw, _ := io.ReadAll(io.LimitReader(res.Body, 1<<20))
	var tr tokenResp
	_ = json.Unmarshal(raw, &tr)
	if tr.ErrCode == 40014 || tr.ErrCode == 42001 {
		c.mu.Lock()
		_ = c.refreshLocked(ctx, corpid, secret)
		token = c.token
		c.mu.Unlock()
		u = fmt.Sprintf("%s/cgi-bin/message/send?access_token=%s", c.BaseURL, url.QueryEscape(token))
		req, _ = http.NewRequestWithContext(ctx, http.MethodPost, u, strings.NewReader(string(body)))
		req.Header.Set("Content-Type", "application/json")
		res2, err := c.HTTP.Do(req)
		if err != nil {
			return err
		}
		defer res2.Body.Close()
		raw, _ = io.ReadAll(io.LimitReader(res2.Body, 1<<20))
		_ = json.Unmarshal(raw, &tr)
	}
	if tr.ErrCode != 0 {
		return fmt.Errorf("send errcode=%d", tr.ErrCode)
	}
	return nil
}
