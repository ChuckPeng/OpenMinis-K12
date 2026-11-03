package crypto

import (
	"crypto/ed25519"
	"crypto/hmac"
	"crypto/rand"
	"crypto/sha256"
	"crypto/subtle"
	"encoding/base64"
	"encoding/hex"
	"encoding/json"
	"fmt"
	"math/big"
	"strconv"
	"strings"

	"golang.org/x/crypto/nacl/box"
)

// GrantPayload represents the structured grant for an approved unlock.
type GrantPayload struct {
	DecidedByLabel string `json:"decided_by_label"`
	DeviceID       string `json:"device_id"`
	ExpiresAt      string `json:"expires_at"`
	GrantID        string `json:"grant_id"`
	QuestionHash   string `json:"question_hash"`
	RequestID      string `json:"request_id"`
	Scope          string `json:"scope"`
	V              int    `json:"v"`
}

// CanonicalGrantPayload produces key-sorted compact UTF-8 JSON without extra whitespace.
func CanonicalGrantPayload(p GrantPayload) ([]byte, error) {
	// Sorted keys: decided_by_label, device_id, expires_at, grant_id, question_hash, request_id, scope, v
	type canonical struct {
		DecidedByLabel string `json:"decided_by_label"`
		DeviceID       string `json:"device_id"`
		ExpiresAt      string `json:"expires_at"`
		GrantID        string `json:"grant_id"`
		QuestionHash   string `json:"question_hash"`
		RequestID      string `json:"request_id"`
		Scope          string `json:"scope"`
		V              int    `json:"v"`
	}
	c := canonical{
		DecidedByLabel: p.DecidedByLabel,
		DeviceID:       p.DeviceID,
		ExpiresAt:      p.ExpiresAt,
		GrantID:        p.GrantID,
		QuestionHash:   p.QuestionHash,
		RequestID:      p.RequestID,
		Scope:          p.Scope,
		V:              p.V,
	}
	return json.Marshal(c)
}

// SignGrant signs the canonical JSON representation of GrantPayload using Ed25519.
func SignGrant(priv ed25519.PrivateKey, payload []byte) string {
	sig := ed25519.Sign(priv, payload)
	return base64.RawURLEncoding.EncodeToString(sig)
}

// VerifyGrant verifies an Ed25519 signature over canonical grant JSON.
func VerifyGrant(pub ed25519.PublicKey, payload []byte, sigStr string) bool {
	sig, err := base64.RawURLEncoding.DecodeString(sigStr)
	if err != nil {
		return false
	}
	return ed25519.Verify(pub, payload, sig)
}

// BuildDeviceSignString builds the 5-line string to sign according to conventions.
// Method (uppercase), Path (with query), Ts (ms), Nonce (32 hex), Body SHA256 (lowercase hex).
func BuildDeviceSignString(method, path, ts, nonce, bodyHash string) string {
	if bodyHash == "" {
		h := sha256.Sum256(nil)
		bodyHash = hex.EncodeToString(h[:])
	}
	return strings.Join([]string{
		strings.ToUpper(strings.TrimSpace(method)),
		strings.TrimSpace(path),
		strings.TrimSpace(ts),
		strings.ToLower(strings.TrimSpace(nonce)),
		strings.ToLower(strings.TrimSpace(bodyHash)),
	}, "\n")
}

// VerifyDeviceSignature checks the signature from X-K12-Sig against the 5-line sign string.
func VerifyDeviceSignature(pubKey []byte, signString string, sigStr string) bool {
	sigStr = strings.TrimSpace(sigStr)
	var sig []byte
	var err error
	// Try unpadded base64url, then standard base64, then hex.
	sig, err = base64.RawURLEncoding.DecodeString(sigStr)
	if err != nil {
		sig, err = base64.StdEncoding.DecodeString(sigStr)
		if err != nil {
			sig, err = hex.DecodeString(sigStr)
			if err != nil {
				return false
			}
		}
	}

	if len(pubKey) == ed25519.PublicKeySize {
		return ed25519.Verify(pubKey, []byte(signString), sig)
	}
	return false
}

// DeriveBackupCode derives a 6-digit offline backup code from codeSecret and (requestID || questionHash).
// Uses HMAC-SHA256 and RFC 4226 dynamic truncation.
func DeriveBackupCode(codeSecret []byte, requestID, questionHash string) string {
	mac := hmac.New(sha256.New, codeSecret)
	mac.Write([]byte(requestID + ":" + questionHash))
	sum := mac.Sum(nil)
	offset := sum[len(sum)-1] & 0x0f
	code := (int(sum[offset]&0x7f)<<24 | int(sum[offset+1])<<16 | int(sum[offset+2])<<8 | int(sum[offset+3])) % 1000000
	return fmt.Sprintf("%06d", code)
}

// SignReportLink generates an HMAC-SHA256 signature for a report link.
// Plain text: link_id + "\n" + exp (decimal unix seconds).
func SignReportLink(key []byte, linkID string, exp int64) string {
	mac := hmac.New(sha256.New, key)
	msg := linkID + "\n" + strconv.FormatInt(exp, 10)
	mac.Write([]byte(msg))
	return base64.RawURLEncoding.EncodeToString(mac.Sum(nil))
}

// VerifyReportLink verifies an HMAC-SHA256 signature for a report link.
func VerifyReportLink(key []byte, linkID string, exp int64, sigStr string) bool {
	sig, err := base64.RawURLEncoding.DecodeString(sigStr)
	if err != nil {
		return false
	}
	mac := hmac.New(sha256.New, key)
	msg := linkID + "\n" + strconv.FormatInt(exp, 10)
	mac.Write([]byte(msg))
	expected := mac.Sum(nil)
	return subtle.ConstantTimeCompare(sig, expected) == 1
}

// Ed25519ToX25519 converts an Ed25519 public key to an X25519 Montgomery public key.
// u = (1 + y) / (1 - y) mod p, where p = 2^255 - 19.
func Ed25519ToX25519(edPubKey []byte) ([32]byte, error) {
	var out [32]byte
	if len(edPubKey) != 32 {
		return out, fmt.Errorf("invalid ed25519 public key length %d", len(edPubKey))
	}
	// Ed25519 public key encodes point (x, y) with y in lower 255 bits and sign of x in highest bit.
	var yBytes [32]byte
	copy(yBytes[:], edPubKey)
	yBytes[31] &= 0x7f // clear highest bit

	// Little-endian to big.Int
	y := new(big.Int)
	for i := 31; i >= 0; i-- {
		y.Lsh(y, 8)
		y.Or(y, big.NewInt(int64(yBytes[i])))
	}

	// p = 2^255 - 19
	p := new(big.Int).Sub(new(big.Int).Lsh(big.NewInt(1), 255), big.NewInt(19))

	one := big.NewInt(1)
	num := new(big.Int).Add(one, y)
	num.Mod(num, p)

	den := new(big.Int).Sub(one, y)
	den.Mod(den, p)

	denInv := new(big.Int).ModInverse(den, p)
	if denInv == nil {
		return out, fmt.Errorf("invalid ed25519 public key: y = 1 mod p")
	}

	u := new(big.Int).Mul(num, denInv)
	u.Mod(u, p)

	// Convert u back to 32 little-endian bytes
	uBytes := u.Bytes() // big-endian
	for i, b := range uBytes {
		out[len(uBytes)-1-i] = b
	}
	return out, nil
}

// SealCodeSecret wraps a 32-byte codeSecret using sealed box (X25519 anonymous sealed box).
func SealCodeSecret(devicePubKey []byte, secret []byte) (alg string, wrappedBase64 string, err error) {
	var x25519Pub [32]byte
	if len(devicePubKey) == 32 {
		// Convert Ed25519 to X25519
		xPub, err := Ed25519ToX25519(devicePubKey)
		if err == nil {
			x25519Pub = xPub
		} else {
			copy(x25519Pub[:], devicePubKey)
		}
	} else {
		return "", "", fmt.Errorf("unsupported public key length %d", len(devicePubKey))
	}

	sealed, err := box.SealAnonymous(nil, secret, &x25519Pub, rand.Reader)
	if err != nil {
		return "", "", fmt.Errorf("seal anonymous: %w", err)
	}

	return "sealedbox_x25519", base64.StdEncoding.EncodeToString(sealed), nil
}
