package crypto

import (
	"bytes"
	"crypto/aes"
	"crypto/cipher"
	"crypto/rand"
	"crypto/sha1"
	"encoding/base64"
	"encoding/binary"
	"encoding/xml"
	"errors"
	"fmt"
	"io"
	"sort"
	"strings"
)

// WXBizMsgCrypt implements the WeCom callback encryptor (AES-256-CBC + SHA-1
// signature) as documented by WeChat Work. receiveID is the corp id for
// application callbacks.
type WXBizMsgCrypt struct {
	token          string
	encodingAESKey string
	receiveID      string
	aesKey         []byte
}

// NewWXBizMsgCrypt constructs a cryptor. encodingAESKey must be 43 chars.
func NewWXBizMsgCrypt(token, encodingAESKey, receiveID string) (*WXBizMsgCrypt, error) {
	if len(encodingAESKey) != 43 {
		return nil, fmt.Errorf("EncodingAESKey must be 43 characters")
	}
	key, err := base64.StdEncoding.DecodeString(encodingAESKey + "=")
	if err != nil {
		return nil, fmt.Errorf("EncodingAESKey base64: %w", err)
	}
	if len(key) != 32 {
		return nil, fmt.Errorf("EncodingAESKey must decode to 32 bytes")
	}
	return &WXBizMsgCrypt{
		token:          token,
		encodingAESKey: encodingAESKey,
		receiveID:      receiveID,
		aesKey:         key,
	}, nil
}

// Signature computes msg_signature = sha1(sort(token, timestamp, nonce, encrypt)).
func (c *WXBizMsgCrypt) Signature(timestamp, nonce, encrypt string) string {
	arr := []string{c.token, timestamp, nonce, encrypt}
	sort.Strings(arr)
	sum := sha1.Sum([]byte(strings.Join(arr, "")))
	return fmt.Sprintf("%x", sum)
}

// VerifyURL decrypts echostr for the GET URL verification handshake.
func (c *WXBizMsgCrypt) VerifyURL(msgSignature, timestamp, nonce, echostr string) (string, error) {
	if c.Signature(timestamp, nonce, echostr) != msgSignature {
		return "", errors.New("invalid signature")
	}
	plain, receiveID, err := c.decrypt(echostr)
	if err != nil {
		return "", err
	}
	if receiveID != c.receiveID {
		return "", fmt.Errorf("receive id mismatch")
	}
	return string(plain), nil
}

// DecryptMsg decrypts a POST callback body (XML with Encrypt element).
func (c *WXBizMsgCrypt) DecryptMsg(msgSignature, timestamp, nonce, postBody string) (string, error) {
	enc, err := extractEncrypt(postBody)
	if err != nil {
		return "", err
	}
	if c.Signature(timestamp, nonce, enc) != msgSignature {
		return "", errors.New("invalid signature")
	}
	plain, receiveID, err := c.decrypt(enc)
	if err != nil {
		return "", err
	}
	if receiveID != c.receiveID {
		return "", fmt.Errorf("receive id mismatch")
	}
	return string(plain), nil
}

// EncryptPlain returns the raw base64 ciphertext (used for echostr / tests).
func (c *WXBizMsgCrypt) EncryptPlain(plain string) (string, error) {
	return c.encrypt([]byte(plain))
}

// EncryptMsg encrypts a reply plaintext into the WeCom XML envelope.
func (c *WXBizMsgCrypt) EncryptMsg(reply, timestamp, nonce string) (string, error) {
	enc, err := c.encrypt([]byte(reply))
	if err != nil {
		return "", err
	}
	sig := c.Signature(timestamp, nonce, enc)
	var b strings.Builder
	b.WriteString("<xml>")
	b.WriteString("<Encrypt><![CDATA[" + enc + "]]></Encrypt>")
	b.WriteString("<MsgSignature><![CDATA[" + sig + "]]></MsgSignature>")
	b.WriteString("<TimeStamp>" + timestamp + "</TimeStamp>")
	b.WriteString("<Nonce><![CDATA[" + nonce + "]]></Nonce>")
	b.WriteString("</xml>")
	return b.String(), nil
}

func (c *WXBizMsgCrypt) encrypt(msg []byte) (string, error) {
	rand16 := make([]byte, 16)
	if _, err := io.ReadFull(rand.Reader, rand16); err != nil {
		return "", err
	}
	buf := &bytes.Buffer{}
	buf.Write(rand16)
	_ = binary.Write(buf, binary.BigEndian, uint32(len(msg)))
	buf.Write(msg)
	buf.WriteString(c.receiveID)
	padded := pkcs7Pad(buf.Bytes(), aes.BlockSize)
	block, err := aes.NewCipher(c.aesKey)
	if err != nil {
		return "", err
	}
	iv := c.aesKey[:16]
	mode := cipher.NewCBCEncrypter(block, iv)
	ct := make([]byte, len(padded))
	mode.CryptBlocks(ct, padded)
	return base64.StdEncoding.EncodeToString(ct), nil
}

func (c *WXBizMsgCrypt) decrypt(cipherB64 string) (msg []byte, receiveID string, err error) {
	raw, err := base64.StdEncoding.DecodeString(cipherB64)
	if err != nil {
		return nil, "", err
	}
	if len(raw)%aes.BlockSize != 0 {
		return nil, "", errors.New("ciphertext length invalid")
	}
	block, err := aes.NewCipher(c.aesKey)
	if err != nil {
		return nil, "", err
	}
	iv := c.aesKey[:16]
	mode := cipher.NewCBCDecrypter(block, iv)
	plain := make([]byte, len(raw))
	mode.CryptBlocks(plain, raw)
	plain, err = pkcs7Unpad(plain, aes.BlockSize)
	if err != nil {
		return nil, "", err
	}
	if len(plain) < 20 {
		return nil, "", errors.New("plaintext too short")
	}
	msgLen := binary.BigEndian.Uint32(plain[16:20])
	if int(20+msgLen) > len(plain) {
		return nil, "", errors.New("msg length overflow")
	}
	msg = plain[20 : 20+msgLen]
	receiveID = string(plain[20+msgLen:])
	return msg, receiveID, nil
}

type xmlEncrypt struct {
	Encrypt string `xml:"Encrypt"`
}

func extractEncrypt(body string) (string, error) {
	body = strings.TrimSpace(body)
	if strings.HasPrefix(body, "{") {
		// JSON callback form used by some WeCom samples.
		const key = `"encrypt"`
		i := strings.Index(strings.ToLower(body), key)
		if i < 0 {
			i = strings.Index(body, `"Encrypt"`)
		}
		if i < 0 {
			return "", errors.New("encrypt field missing")
		}
		rest := body[i:]
		start := strings.Index(rest, `"`)
		if start < 0 {
			return "", errors.New("encrypt field missing")
		}
		// find value after colon
		colon := strings.Index(rest, ":")
		if colon < 0 {
			return "", errors.New("encrypt field missing")
		}
		rest = rest[colon+1:]
		rest = strings.TrimSpace(rest)
		if !strings.HasPrefix(rest, `"`) {
			return "", errors.New("encrypt field missing")
		}
		rest = rest[1:]
		end := strings.Index(rest, `"`)
		if end < 0 {
			return "", errors.New("encrypt field missing")
		}
		return rest[:end], nil
	}
	var x xmlEncrypt
	if err := xml.Unmarshal([]byte(body), &x); err != nil {
		return "", err
	}
	if x.Encrypt == "" {
		return "", errors.New("Encrypt element missing")
	}
	return x.Encrypt, nil
}

func pkcs7Pad(data []byte, blockSize int) []byte {
	pad := blockSize - len(data)%blockSize
	if pad == 0 {
		pad = blockSize
	}
	return append(data, bytes.Repeat([]byte{byte(pad)}, pad)...)
}

func pkcs7Unpad(data []byte, blockSize int) ([]byte, error) {
	if len(data) == 0 || len(data)%blockSize != 0 {
		return nil, errors.New("invalid padding size")
	}
	pad := int(data[len(data)-1])
	if pad == 0 || pad > blockSize || pad > len(data) {
		return nil, errors.New("invalid padding")
	}
	for i := 0; i < pad; i++ {
		if data[len(data)-1-i] != byte(pad) {
			return nil, errors.New("invalid padding")
		}
	}
	return data[:len(data)-pad], nil
}
