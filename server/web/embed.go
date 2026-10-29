package web

import "embed"

// Dist holds the Vite build output (index.html + assets). A checked-in
// placeholder keeps `go test` / `go build` working before `npm run build`.
//
//go:embed all:dist
var Dist embed.FS
