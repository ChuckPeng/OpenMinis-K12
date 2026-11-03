package outbox

import (
	"context"
	"encoding/json"
	"log"
	"time"

	"github.com/ChuckPeng/OpenMinis-K12/server/internal/store"
)

// Sender delivers one outbox payload. Tests inject a fake; production uses WeCom.
type Sender func(ctx context.Context, item store.OutboxItem) error

// Worker drains the outbox with exponential backoff.
type Worker struct {
	Store  *store.Store
	Sender Sender
	Every  time.Duration
}

// Run loops until ctx is cancelled.
func (w *Worker) Run(ctx context.Context) {
	if w.Every <= 0 {
		w.Every = 5 * time.Second
	}
	t := time.NewTicker(w.Every)
	defer t.Stop()
	for {
		w.Tick(ctx)
		select {
		case <-ctx.Done():
			return
		case <-t.C:
		}
	}
}

// Tick processes due outbox rows once (exported for tests).
func (w *Worker) Tick(ctx context.Context) {
	items, err := w.Store.ClaimDueOutbox(ctx, 20)
	if err != nil {
		return
	}
	for _, it := range items {
		if it.ExpiresAt.Valid {
			exp, err := time.Parse(time.RFC3339Nano, it.ExpiresAt.String)
			if err == nil && time.Now().UTC().After(exp) {
				// Unlock-class: drop when expired (R5).
				_ = w.Store.MarkOutboxDropped(ctx, it.ID, "expired")
				continue
			}
		}
		if w.Sender == nil {
			continue
		}
		err := w.Sender(ctx, it)
		if err == nil {
			_ = w.Store.MarkOutboxSent(ctx, it.ID)
			continue
		}
		attempts := it.Attempts + 1
		if attempts >= 24 { // ~24h with capped backoff
			_ = w.Store.MarkOutboxDropped(ctx, it.ID, "abandoned")
			_ = w.Store.AppendAudit(ctx, "system", "outbox_abandoned", it.ID)
			continue
		}
		delay := time.Duration(1<<min(attempts, 6)) * time.Minute
		if delay > time.Hour {
			delay = time.Hour
		}
		_ = w.Store.MarkOutboxRetry(ctx, it.ID, attempts, time.Now().UTC().Add(delay), redactErr(err))
		log.Printf("outbox retry id=%s attempt=%d", it.ID, attempts)
	}
}

func min(a, b int) int {
	if a < b {
		return a
	}
	return b
}

func redactErr(err error) string {
	s := err.Error()
	// Never echo secrets; keep short machine codes.
	if len(s) > 120 {
		s = s[:120]
	}
	return s
}

// EnqueueJSON is a helper for producers.
func EnqueueJSON(ctx context.Context, st *store.Store, appID, kind string, payload any, expires *time.Time) error {
	b, err := json.Marshal(payload)
	if err != nil {
		return err
	}
	item := &store.OutboxItem{
		AppID:       appID,
		Kind:        kind,
		PayloadJSON: string(b),
	}
	if expires != nil {
		item.ExpiresAt.Valid = true
		item.ExpiresAt.String = expires.UTC().Format(time.RFC3339Nano)
	}
	return st.EnqueueOutbox(ctx, item)
}
