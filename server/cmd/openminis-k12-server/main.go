package main

import (
	"context"
	"database/sql"
	"flag"
	"fmt"
	"io/fs"
	"os"
	"os/signal"
	"strings"
	"syscall"

	"github.com/ChuckPeng/OpenMinis-K12/server/internal/app"
	"github.com/ChuckPeng/OpenMinis-K12/server/internal/auth"
	"github.com/ChuckPeng/OpenMinis-K12/server/internal/config"
	"github.com/ChuckPeng/OpenMinis-K12/server/internal/store"
	"github.com/ChuckPeng/OpenMinis-K12/server/internal/version"
	"github.com/ChuckPeng/OpenMinis-K12/server/web"
)

func main() {
	if len(os.Args) > 1 {
		switch os.Args[1] {
		case "version", "--version", "-version":
			fmt.Println(version.String())
			return
		case "serve", "migrate", "backup", "restore", "healthcheck", "reset-admin":
			os.Exit(runCommand(os.Args[1], os.Args[2:]))
		}
	}
	os.Exit(runCommand("serve", os.Args[1:]))
}

func runCommand(cmd string, args []string) int {
	fs := flag.NewFlagSet(cmd, flag.ExitOnError)
	cfgPath := fs.String("config", os.Getenv("OPENMINIS_K12_CONFIG"), "path to config.yaml")
	dataDir := fs.String("data-dir", "", "override data_dir")
	_ = fs.Parse(args)

	path := *cfgPath
	if path == "" {
		if _, err := os.Stat("/etc/openminis-k12/config.yaml"); err == nil {
			path = "/etc/openminis-k12/config.yaml"
		}
	}
	cfg, err := config.LoadFile(path)
	if err != nil {
		fmt.Fprintf(os.Stderr, "config: %v\n", err)
		return 1
	}
	if *dataDir != "" {
		cfg.DataDir = *dataDir
	}

	switch cmd {
	case "serve":
		cfg = cfg.WithTestProxies()
		if err := cfg.Validate(); err != nil {
			fmt.Fprintf(os.Stderr, "%v\n", err)
			return 1
		}
		return cmdServe(cfg)
	case "migrate":
		return cmdMigrate(cfg)
	case "backup":
		return cmdBackup(cfg)
	case "restore":
		fmt.Fprintln(os.Stderr, "restore: place the backup DB in data_dir and restore master.key separately (docs/k12/ops/install-systemd.md)")
		return 0
	case "healthcheck":
		return cmdHealth(cfg)
	case "reset-admin":
		return cmdResetAdmin(cfg, fs.Args())
	default:
		fmt.Fprintf(os.Stderr, "unknown command %s\n", cmd)
		return 2
	}
}

func cmdServe(cfg config.Config) int {
	rt, err := app.Open(cfg)
	if err != nil {
		fmt.Fprintf(os.Stderr, "open: %v\n", err)
		return 1
	}
	defer rt.Close()
	if sub, err := fs.Sub(web.Dist, "dist"); err == nil {
		rt.UI = sub
	}
	ctx, stop := signal.NotifyContext(context.Background(), syscall.SIGINT, syscall.SIGTERM)
	defer stop()
	fmt.Printf("openminis-k12-server %s device=%s admin=%s\n", version.String(), cfg.DeviceListen, cfg.AdminListen)
	if err := rt.ListenAndServe(ctx); err != nil {
		fmt.Fprintf(os.Stderr, "serve: %v\n", err)
		return 1
	}
	return 0
}

func cmdMigrate(cfg config.Config) int {
	if err := os.MkdirAll(cfg.DataDir, 0o750); err != nil {
		fmt.Fprintf(os.Stderr, "%v\n", err)
		return 1
	}
	if _, err := store.AutoBackup(cfg.DBPath(), cfg.BackupKeep); err != nil {
		fmt.Fprintf(os.Stderr, "backup: %v\n", err)
		return 1
	}
	st, err := store.Open(cfg.DBPath())
	if err != nil {
		fmt.Fprintf(os.Stderr, "%v\n", err)
		return 1
	}
	defer st.Close()
	fmt.Println("migrate ok")
	return 0
}

func cmdBackup(cfg config.Config) int {
	path, err := store.AutoBackup(cfg.DBPath(), cfg.BackupKeep)
	if err != nil {
		fmt.Fprintf(os.Stderr, "%v\n", err)
		return 1
	}
	if path == "" {
		fmt.Println("no database yet")
		return 0
	}
	fmt.Println(path)
	return 0
}

func cmdHealth(cfg config.Config) int {
	st, err := sqlOpen(cfg)
	if err != nil {
		fmt.Fprintln(os.Stderr, "unavailable")
		return 1
	}
	defer st.Close()
	if err := st.Ping(context.Background()); err != nil {
		fmt.Fprintln(os.Stderr, "unavailable")
		return 1
	}
	fmt.Println("ok")
	return 0
}

func sqlOpen(cfg config.Config) (*store.Store, error) {
	return store.Open(cfg.DBPath())
}

func cmdResetAdmin(cfg config.Config, args []string) int {
	if len(args) < 2 {
		fmt.Fprintln(os.Stderr, "usage: reset-admin <username> <new-password>")
		return 2
	}
	user, pw := args[0], args[1]
	if err := auth.ValidatePassword(pw); err != nil {
		fmt.Fprintf(os.Stderr, "%v\n", err)
		return 1
	}
	hash, err := auth.HashPassword(pw)
	if err != nil {
		fmt.Fprintf(os.Stderr, "%v\n", err)
		return 1
	}
	st, err := store.Open(cfg.DBPath())
	if err != nil {
		fmt.Fprintf(os.Stderr, "%v\n", err)
		return 1
	}
	defer st.Close()
	a, err := st.GetAdminByUsername(context.Background(), user)
	if err != nil || a == nil {
		fmt.Fprintln(os.Stderr, "admin not found")
		return 1
	}
	if err := st.ResetAdminPassword(context.Background(), user, hash); err != nil {
		if err == sql.ErrNoRows {
			fmt.Fprintln(os.Stderr, "admin not found")
			return 1
		}
		fmt.Fprintf(os.Stderr, "%v\n", err)
		return 1
	}
	_ = st.DeleteAdminSessions(context.Background(), a.ID)
	fmt.Println("password reset")
	return 0
}

// silence unused in some build tags
var _ = strings.TrimSpace
