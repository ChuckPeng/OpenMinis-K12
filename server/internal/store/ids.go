package store

import (
	"database/sql"

	"github.com/google/uuid"
)

func newID() string {
	return uuid.NewString()
}

// NullString wraps a string into sql.NullString.
func NullString(s string) sql.NullString {
	if s == "" {
		return sql.NullString{}
	}
	return sql.NullString{String: s, Valid: true}
}

