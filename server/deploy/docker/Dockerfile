# Optional secondary deployment path. Primary install is systemd.
FROM node:22-bookworm AS web
WORKDIR /src/web
COPY web/package.json web/package-lock.json* ./
RUN npm install
COPY web/ ./
RUN npm run build:ci

FROM golang:1.22-bookworm AS build
WORKDIR /src
COPY go.mod go.sum ./
RUN go mod download
COPY . .
COPY --from=web /src/web/dist ./web/dist
ARG VERSION=1.0.0-m5a
ARG VERSION_CODE=1000005
ARG GIT_COMMIT=unknown
ARG UPSTREAM_VERSION=1.15
RUN CGO_ENABLED=0 go build -trimpath \
  -ldflags "-s -w -X github.com/ChuckPeng/OpenMinis-K12/server/internal/version.Version=${VERSION} -X github.com/ChuckPeng/OpenMinis-K12/server/internal/version.VersionCode=${VERSION_CODE} -X github.com/ChuckPeng/OpenMinis-K12/server/internal/version.GitCommit=${GIT_COMMIT} -X github.com/ChuckPeng/OpenMinis-K12/server/internal/version.UpstreamVersion=${UPSTREAM_VERSION}" \
  -o /out/openminis-k12-server ./cmd/openminis-k12-server

FROM gcr.io/distroless/static-debian12:nonroot
WORKDIR /data
COPY --from=build /out/openminis-k12-server /openminis-k12-server
USER nonroot:nonroot
EXPOSE 8080 8081
ENTRYPOINT ["/openminis-k12-server"]
CMD ["serve"]
