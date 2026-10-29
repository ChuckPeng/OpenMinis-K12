package crypto

import (
	"strings"
	"testing"
)

// Public sample vector from xen0n/go-workwx (algorithm-compatible with the
// official WeCom WXBizMsgCrypt samples). These are published demo fixtures,
// not production secrets.
func TestDecryptOfficialStyleVector(t *testing.T) {
	encodingAESKey := "4Ma3YBrSBbX2aez8MJpXGBne5LSDwgGqHbhM9WPYIws"
	c, err := NewWXBizMsgCrypt("unused-for-decrypt-only", encodingAESKey, "ww6a112864f8022910")
	if err != nil {
		t.Fatal(err)
	}
	cipherB64 := "6KmUQuPVu7UhjyVqRdbo5SfcRqaHvbUlKSHFvBV2ZuR6TIlKsygcfeSd1GDplg1C5KSKr6UPHCaC/nIX3ZNt9w=="
	msg, receiveID, err := c.decrypt(cipherB64)
	if err != nil {
		t.Fatal(err)
	}
	if string(msg) != "94966531020182955848408" {
		t.Fatalf("msg=%q", msg)
	}
	if receiveID != "ww6a112864f8022910" {
		t.Fatalf("receiveID=%q", receiveID)
	}
}

func TestRoundTripEncryptDecryptAndSignature(t *testing.T) {
	token := "k12PublicSampleToken"
	key := "abcdefghijklmnopqrstuvwxyz0123456789ABCDEFG"
	corp := "wwSampleCorpId0001"
	c, err := NewWXBizMsgCrypt(token, key, corp)
	if err != nil {
		t.Fatal(err)
	}
	plain := `<xml><MsgType><![CDATA[event]]></MsgType><Event><![CDATA[template_card_event]]></Event><EventKey><![CDATA[approve_question]]></EventKey><TaskId><![CDATA[task-1]]></TaskId><FromUserName><![CDATA[parent_a]]></FromUserName></xml>`
	ts, nonce := "1700000000", "nonce-sample"
	encXML, err := c.EncryptMsg(plain, ts, nonce)
	if err != nil {
		t.Fatal(err)
	}
	enc, err := extractEncrypt(encXML)
	if err != nil {
		t.Fatal(err)
	}
	sig := c.Signature(ts, nonce, enc)
	out, err := c.DecryptMsg(sig, ts, nonce, encXML)
	if err != nil {
		t.Fatal(err)
	}
	if out != plain {
		t.Fatalf("round-trip mismatch\nwant %s\ngot  %s", plain, out)
	}

	// VerifyURL path uses the same decrypt of echostr.
	echoEnc, err := c.EncryptPlain("1615088319")
	if err != nil {
		t.Fatal(err)
	}
	echoSig := c.Signature(ts, nonce, echoEnc)
	got, err := c.VerifyURL(echoSig, ts, nonce, echoEnc)
	if err != nil {
		t.Fatal(err)
	}
	if got != "1615088319" {
		t.Fatalf("echo=%q", got)
	}
}

func TestRejectBadSignature(t *testing.T) {
	c, err := NewWXBizMsgCrypt("tok", "abcdefghijklmnopqrstuvwxyz0123456789ABCDEFG", "corp")
	if err != nil {
		t.Fatal(err)
	}
	_, err = c.VerifyURL("deadbeef", "1", "2", "AAAA")
	if err == nil || !strings.Contains(err.Error(), "signature") {
		t.Fatalf("expected signature error, got %v", err)
	}
}
