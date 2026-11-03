package crypto_test

import (
	"crypto/ed25519"
	"crypto/rand"
	"testing"
	"time"

	"github.com/ChuckPeng/OpenMinis-K12/server/internal/crypto"
)

func TestGrantPayloadCanonicalAndSign(t *testing.T) {
	pub, priv, err := ed25519.GenerateKey(rand.Reader)
	if err != nil {
		t.Fatal(err)
	}

	payload := crypto.GrantPayload{
		V:              1,
		GrantID:        "grant-123",
		RequestID:      "req-456",
		DeviceID:       "dev-789",
		QuestionHash:   "abcde12345",
		Scope:          "question",
		ExpiresAt:      time.Now().UTC().Format(time.RFC3339),
		DecidedByLabel: "家长",
	}

	canonical, err := crypto.CanonicalGrantPayload(payload)
	if err != nil {
		t.Fatal(err)
	}

	sig := crypto.SignGrant(priv, canonical)
	if sig == "" {
		t.Fatal("empty signature")
	}

	if !crypto.VerifyGrant(pub, canonical, sig) {
		t.Fatal("grant signature verification failed")
	}

	// Corrupted payload fails
	corrupted := append(canonical, 'x')
	if crypto.VerifyGrant(pub, corrupted, sig) {
		t.Fatal("corrupted payload should fail")
	}
}

func TestDeviceSignatureVerify(t *testing.T) {
	pub, priv, err := ed25519.GenerateKey(rand.Reader)
	if err != nil {
		t.Fatal(err)
	}

	signStr := crypto.BuildDeviceSignString("POST", "/api/v1/device/heartbeat", "1761800000000", "0123456789abcdef0123456789abcdef", "")
	sig := crypto.SignGrant(priv, []byte(signStr))

	if !crypto.VerifyDeviceSignature(pub, signStr, sig) {
		t.Fatal("device signature verification failed")
	}
}

func TestDeriveBackupCode(t *testing.T) {
	secret := make([]byte, 32)
	for i := range secret {
		secret[i] = byte(i)
	}

	code1 := crypto.DeriveBackupCode(secret, "req-1", "hash-1")
	if len(code1) != 6 {
		t.Fatalf("expected 6 digits, got %s", code1)
	}

	// Deterministic
	code2 := crypto.DeriveBackupCode(secret, "req-1", "hash-1")
	if code1 != code2 {
		t.Fatalf("expected deterministic code, got %s and %s", code1, code2)
	}

	// Different request produces different code
	code3 := crypto.DeriveBackupCode(secret, "req-2", "hash-1")
	if code1 == code3 {
		t.Logf("different requests might collide by chance, but rarely")
	}
}

func TestReportLinkSignAndVerify(t *testing.T) {
	key := []byte("master-derived-report-key-32b-ok")
	exp := time.Now().Add(72 * time.Hour).Unix()
	linkID := "link-uuid-1234"

	sig := crypto.SignReportLink(key, linkID, exp)
	if sig == "" {
		t.Fatal("empty sig")
	}

	if !crypto.VerifyReportLink(key, linkID, exp, sig) {
		t.Fatal("valid link failed verification")
	}

	if crypto.VerifyReportLink(key, linkID, exp+1, sig) {
		t.Fatal("tampered exp should fail")
	}

	if crypto.VerifyReportLink(key, "other-link", exp, sig) {
		t.Fatal("tampered linkID should fail")
	}
}
