FROM golang:1.27-alpine AS build
WORKDIR /src
COPY go.mod go.sum ./
RUN go mod download
COPY *.go ./
RUN CGO_ENABLED=0 go build -trimpath -ldflags="-s -w" -o /adblock-dns .

FROM alpine:3.22
LABEL org.opencontainers.image.source=https://github.com/TYFSADIK/mobile-chrome-adblock
LABEL org.opencontainers.image.description="Ad blocking for Chrome on Android and iPhone via encrypted DNS"
LABEL org.opencontainers.image.licenses=MIT
RUN apk add --no-cache ca-certificates
COPY --from=build /adblock-dns /usr/local/bin/adblock-dns
COPY allowlist.txt /data/allowlist.txt
WORKDIR /data
VOLUME /data/certs
EXPOSE 80 443 853
ENTRYPOINT ["adblock-dns"]
