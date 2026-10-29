package config

import (
	"fmt"
	"net"
	"os"
	"strconv"
	"strings"

	"gopkg.in/yaml.v3"
)

// Config is process configuration (YAML + OPENMINIS_K12_* env). Business
// settings live in the settings table, not here.
type Config struct {
	DataDir        string   `yaml:"data_dir"`
	MasterKeyPath  string   `yaml:"master_key_path"`
	PublicBaseURL  string   `yaml:"public_base_url"`
	TrustedProxies []string `yaml:"trusted_proxies"`
	DeviceListen   string   `yaml:"device_listen"`
	AdminListen    string   `yaml:"admin_listen"`
	BackupKeep     int      `yaml:"backup_keep"`
}

// Default returns systemd-oriented defaults. Docker images override data_dir.
func Default() Config {
	return Config{
		DataDir:       "/var/lib/openminis-k12",
		MasterKeyPath: "",
		PublicBaseURL: "",
		DeviceListen:  "127.0.0.1:8080",
		AdminListen:   "127.0.0.1:8081",
		BackupKeep:    14,
	}
}

// LoadFile reads YAML from path (optional empty) then applies environment overrides.
// Does not validate — call Validate before serve.
func LoadFile(path string) (Config, error) {
	cfg := Default()
	if path != "" {
		b, err := os.ReadFile(path)
		if err != nil {
			return Config{}, fmt.Errorf("read config: %w", err)
		}
		if err := yaml.Unmarshal(b, &cfg); err != nil {
			return Config{}, fmt.Errorf("parse config: %w", err)
		}
	}
	ApplyEnv(&cfg)
	cfg.PublicBaseURL = strings.TrimRight(cfg.PublicBaseURL, "/")
	return cfg, nil
}

// ApplyEnv overlays OPENMINIS_K12_* variables.
func ApplyEnv(cfg *Config) {
	if v := os.Getenv("OPENMINIS_K12_DATA_DIR"); v != "" {
		cfg.DataDir = v
	}
	if v := os.Getenv("OPENMINIS_K12_MASTER_KEY_PATH"); v != "" {
		cfg.MasterKeyPath = v
	}
	if v := os.Getenv("OPENMINIS_K12_PUBLIC_BASE_URL"); v != "" {
		cfg.PublicBaseURL = strings.TrimRight(v, "/")
	}
	if v := os.Getenv("OPENMINIS_K12_TRUSTED_PROXIES"); v != "" {
		cfg.TrustedProxies = splitCSV(v)
	}
	if v := os.Getenv("OPENMINIS_K12_DEVICE_LISTEN"); v != "" {
		cfg.DeviceListen = v
	}
	if v := os.Getenv("OPENMINIS_K12_ADMIN_LISTEN"); v != "" {
		cfg.AdminListen = v
	}
	if v := os.Getenv("OPENMINIS_K12_BACKUP_KEEP"); v != "" {
		if n, err := strconv.Atoi(v); err == nil {
			cfg.BackupKeep = n
		}
	}
}

func splitCSV(s string) []string {
	parts := strings.Split(s, ",")
	out := make([]string, 0, len(parts))
	for _, p := range parts {
		p = strings.TrimSpace(p)
		if p != "" {
			out = append(out, p)
		}
	}
	return out
}

// Validate enforces required fields for serve.
func (c Config) Validate() error {
	if c.DataDir == "" {
		return fmt.Errorf("data_dir is required")
	}
	proxies := c.TrustedProxies
	if len(proxies) == 0 && os.Getenv("OPENMINIS_K12_ALLOW_EMPTY_TRUSTED_PROXIES") == "1" {
		proxies = []string{"127.0.0.1/32"}
	}
	if len(proxies) == 0 {
		return fmt.Errorf("trusted_proxies is required")
	}
	for _, p := range proxies {
		if _, _, err := net.ParseCIDR(p); err == nil {
			continue
		}
		if ip := net.ParseIP(p); ip == nil {
			return fmt.Errorf("trusted_proxies entry %q is neither IP nor CIDR", p)
		}
	}
	if strings.HasSuffix(c.PublicBaseURL, "/") {
		return fmt.Errorf("public_base_url must not end with /")
	}
	if c.DeviceListen == "" || c.AdminListen == "" {
		return fmt.Errorf("device_listen and admin_listen are required")
	}
	return nil
}

// WithTestProxies returns a copy that satisfies Validate when empty proxies are allowed.
func (c Config) WithTestProxies() Config {
	if len(c.TrustedProxies) == 0 && os.Getenv("OPENMINIS_K12_ALLOW_EMPTY_TRUSTED_PROXIES") == "1" {
		c.TrustedProxies = []string{"127.0.0.1/32"}
	}
	return c
}

// MasterKeyFile returns the path used for the 32-byte master key.
func (c Config) MasterKeyFile() string {
	if c.MasterKeyPath != "" {
		return c.MasterKeyPath
	}
	return c.DataDir + "/master.key"
}

// DBPath is the SQLite file.
func (c Config) DBPath() string {
	return c.DataDir + "/openminis-k12.db"
}

// CallbackURL builds the WeCom callback URL from public_base_url.
func (c Config) CallbackURL(callbackKey string) string {
	base := strings.TrimRight(c.PublicBaseURL, "/")
	if base == "" {
		return ""
	}
	return base + "/wecom/callback/" + callbackKey
}
