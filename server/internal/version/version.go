// Package version holds build-time identity for openminis-k12-server.
// Values are overwritten via -ldflags at link time.
package version

// Product SemVer and related metadata. Defaults match the current milestone
// when built without ldflags (local `go test` / `go build`).
var (
	Version         = "1.0.0-m5a"
	VersionCode     = "1000005"
	GitCommit       = "unknown"
	UpstreamVersion = "1.15"
)

// String returns the footer / --version line.
func String() string {
	return Version + " (code " + VersionCode + ", commit " + GitCommit + ", upstream " + UpstreamVersion + ")"
}
