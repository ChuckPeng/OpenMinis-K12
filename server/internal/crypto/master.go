package crypto

import (
	"crypto/aes"
	"crypto/cipher"
	"crypto/rand"
	"encoding/base64"
	"fmt"
	"io"
	"os"
)

// MasterKey is a 32-byte AES-256 key used for field encryption at rest.
type MasterKey []byte

// LoadOrCreateMasterKey reads path (mode 0600) or creates a new random key.
func LoadOrCreateMasterKey(path string) (MasterKey, error) {
	b, err := os.ReadFile(path)
	if err == nil {
		raw, err := decodeKey(bytesTrim(b))
		if err != nil {
			return nil, err
		}
		return MasterKey(raw), nil
	}
	if !os.IsNotExist(err) {
		return nil, err
	}
	raw := make([]byte, 32)
	if _, err := io.ReadFull(rand.Reader, raw); err != nil {
		return nil, err
	}
	if err := os.WriteFile(path, []byte(base64.StdEncoding.EncodeToString(raw)+"\n"), 0o600); err != nil {
		return nil, fmt.Errorf("write master key: %w", err)
	}
	return MasterKey(raw), nil
}

func decodeKey(s []byte) ([]byte, error) {
	if len(s) == 32 {
		return s, nil
	}
	raw, err := base64.StdEncoding.DecodeString(string(s))
	if err != nil {
		return nil, fmt.Errorf("master key decode: %w", err)
	}
	if len(raw) != 32 {
		return nil, fmt.Errorf("master key must be 32 bytes, got %d", len(raw))
	}
	return raw, nil
}

func bytesTrim(b []byte) []byte {
	for len(b) > 0 && (b[len(b)-1] == '\n' || b[len(b)-1] == '\r' || b[len(b)-1] == ' ') {
		b = b[:len(b)-1]
	}
	return b
}

// Seal encrypts plaintext with AES-256-GCM. Output is base64(nonce|ciphertext|tag).
func (k MasterKey) Seal(plain []byte) (string, error) {
	if len(k) != 32 {
		return "", fmt.Errorf("master key missing")
	}
	block, err := aes.NewCipher(k)
	if err != nil {
		return "", err
	}
	gcm, err := cipher.NewGCM(block)
	if err != nil {
		return "", err
	}
	nonce := make([]byte, gcm.NonceSize())
	if _, err := io.ReadFull(rand.Reader, nonce); err != nil {
		return "", err
	}
	out := gcm.Seal(nonce, nonce, plain, nil)
	return base64.StdEncoding.EncodeToString(out), nil
}

// Open decrypts a Seal() result.
func (k MasterKey) Open(sealed string) ([]byte, error) {
	if len(k) != 32 {
		return nil, fmt.Errorf("master key missing")
	}
	raw, err := base64.StdEncoding.DecodeString(sealed)
	if err != nil {
		return nil, err
	}
	block, err := aes.NewCipher(k)
	if err != nil {
		return nil, err
	}
	gcm, err := cipher.NewGCM(block)
	if err != nil {
		return nil, err
	}
	if len(raw) < gcm.NonceSize() {
		return nil, fmt.Errorf("ciphertext too short")
	}
	nonce, ct := raw[:gcm.NonceSize()], raw[gcm.NonceSize():]
	return gcm.Open(nil, nonce, ct, nil)
}
