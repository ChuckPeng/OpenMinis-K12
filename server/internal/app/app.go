package app

import (
	"context"
	"fmt"
	"io/fs"
	"net/http"
	"os"
	"time"

	"github.com/ChuckPeng/OpenMinis-K12/server/internal/admin"
	"github.com/ChuckPeng/OpenMinis-K12/server/internal/config"
	"github.com/ChuckPeng/OpenMinis-K12/server/internal/crypto"
	"github.com/ChuckPeng/OpenMinis-K12/server/internal/deviceapi"
	"github.com/ChuckPeng/OpenMinis-K12/server/internal/httpx"
	"github.com/ChuckPeng/OpenMinis-K12/server/internal/outbox"
	"github.com/ChuckPeng/OpenMinis-K12/server/internal/store"
	"github.com/ChuckPeng/OpenMinis-K12/server/internal/wecom"
)

// Runtime wires both HTTP listeners and background workers.
type Runtime struct {
	Cfg    config.Config
	Store  *store.Store
	Master crypto.MasterKey
	UI     fs.FS
}

// Open prepares data dir, master key, and store (with auto-backup + migrate).
func Open(cfg config.Config) (*Runtime, error) {
	if err := os.MkdirAll(cfg.DataDir, 0o750); err != nil {
		return nil, err
	}
	if _, err := store.AutoBackup(cfg.DBPath(), cfg.BackupKeep); err != nil {
		return nil, fmt.Errorf("auto-backup: %w", err)
	}
	master, err := crypto.LoadOrCreateMasterKey(cfg.MasterKeyFile())
	if err != nil {
		return nil, err
	}
	st, err := store.Open(cfg.DBPath())
	if err != nil {
		return nil, err
	}
	_, _ = st.EnsureFamily(context.Background(), "home")
	return &Runtime{Cfg: cfg, Store: st, Master: master}, nil
}

// Close releases resources.
func (rt *Runtime) Close() error {
	if rt.Store != nil {
		return rt.Store.Close()
	}
	return nil
}

// ListenAndServe starts device + admin servers until ctx is done.
func (rt *Runtime) ListenAndServe(ctx context.Context) error {
	proxies, err := httpx.ParseTrustedProxies(rt.Cfg.TrustedProxies)
	if err != nil {
		return err
	}
	notifier := deviceapi.NewNotifier()
	cb := &wecom.CallbackHandler{
		Store:  rt.Store,
		Master: rt.Master,
		OnCard: func(ctx context.Context, app *store.WecomApp, ev wecom.CardEvent) error {
			err := wecom.HandleCardEvent(ctx, rt.Store, app, ev)
			if err == nil {
				req, _ := rt.Store.GetUnlockByTaskID(ctx, ev.TaskID)
				if req != nil {
					notifier.Wake("unlock:" + req.ID)
				}
			}
			return err
		},
	}
	dev := &deviceapi.Server{
		Cfg:      rt.Cfg,
		Store:    rt.Store,
		Master:   rt.Master,
		Callback: cb,
		Notifier: notifier,
	}
	adm := &admin.Server{
		Cfg:      rt.Cfg,
		Store:    rt.Store,
		Master:   rt.Master,
		Proxies:  proxies,
		UI:       rt.UI,
		WeCom:    wecom.NewClient(""),
		Notifier: notifier,
	}
	worker := &outbox.Worker{
		Store: rt.Store,
		Sender: func(ctx context.Context, item store.OutboxItem) error {
			// Delivery against live WeCom is optional; failures stay in outbox (R5).
			return fmt.Errorf("outbound delivery deferred (configure api_base_url / proxy)")
		},
		Every: 5 * time.Second,
	}
	go worker.Run(ctx)

	errCh := make(chan error, 2)
	go func() {
		srv := &http.Server{Addr: rt.Cfg.DeviceListen, Handler: dev.Handler()}
		go func() {
			<-ctx.Done()
			shutdownCtx, cancel := context.WithTimeout(context.Background(), 5*time.Second)
			defer cancel()
			_ = srv.Shutdown(shutdownCtx)
		}()
		errCh <- srv.ListenAndServe()
	}()
	go func() {
		srv := &http.Server{Addr: rt.Cfg.AdminListen, Handler: adm.Handler()}
		go func() {
			<-ctx.Done()
			shutdownCtx, cancel := context.WithTimeout(context.Background(), 5*time.Second)
			defer cancel()
			_ = srv.Shutdown(shutdownCtx)
		}()
		errCh <- srv.ListenAndServe()
	}()

	err = <-errCh
	if err == http.ErrServerClosed {
		return nil
	}
	return err
}
