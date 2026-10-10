# syntax=docker/dockerfile:1
# AceHub gateway - Dockerfile skeleton (REFERENCE, giả định implement bằng Go).
# Pin phiên bản base image theo digest khi build thật.

FROM golang:1.23-bookworm AS build
WORKDIR /src
COPY go.mod go.sum ./
RUN go mod download
COPY . .
ARG VERSION=1.2.0
RUN CGO_ENABLED=0 GOOS=linux go build -trimpath \
    -ldflags="-s -w -X main.version=${VERSION}" \
    -o /out/acehub ./cmd/acehub

# distroless static: có ca-certificates + tzdata, không có shell.
FROM gcr.io/distroless/static-debian12:nonroot
COPY --from=build /out/acehub /usr/local/bin/acehub
USER nonroot:nonroot
EXPOSE 8000
ENV ACEHUB_LISTEN_ADDR=0.0.0.0:8000
# Không có curl trong distroless: binary tự có subcommand healthcheck (GET http://127.0.0.1:8000/health).
HEALTHCHECK --interval=15s --timeout=3s --start-period=20s --retries=3 \
  CMD ["/usr/local/bin/acehub", "healthcheck"]
ENTRYPOINT ["/usr/local/bin/acehub"]
CMD ["serve"]
