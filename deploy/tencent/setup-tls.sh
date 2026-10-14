#!/usr/bin/env bash
# 腾讯云免费证书(TrustAsia DV) 全自动：申请 → DNS 自动验证 → 下载 → 装进 nginx → 重载 → 验证
# 前置：域名 DNS 托管在同账号 DNSPod（才能 DNS_AUTO 自动加 TXT 记录）
#       子用户需 QcloudSSLFullAccess + QcloudDNSPodFullAccess
# 用法：bash setup-tls.sh <域名> [后端端口]
#   例：bash setup-tls.sh ai.example.com 8001
set -euo pipefail

DOMAIN="${1:?用法: bash setup-tls.sh <域名> [后端端口]}"
UPSTREAM_PORT="${2:-8001}"
# 凭据/密钥目录（**故意在仓库外** —— 私钥与 API 密钥永不入库）
TX_ENV_DIR="${TX_ENV_DIR:-$HOME/work/env/tencent}"
KEY_DIR="$TX_ENV_DIR"
SSH_ALIAS="${SSH_ALIAS:-soukmind-tx}"

set -a; source "$KEY_DIR/tencent.env"; set +a
REGION="${TENCENTCLOUD_REGION:-ap-guangzhou}"
WORK="$(mktemp -d)"; trap 'rm -rf "$WORK"' EXIT

jq_py() { python3 -c "import sys,json;d=json.load(sys.stdin);$1"; }

# ── 1. 申请证书 ────────────────────────────────────────────────────
echo "▸ 申请免费证书：$DOMAIN"
CERT_ID="$(tccli ssl ApplyCertificate --region "$REGION" \
    --DvAuthMethod DNS_AUTO --DomainName "$DOMAIN" --PackageType 83 --Alias "soukmind-$DOMAIN" \
    | jq_py 'print(d["CertificateId"])')"
echo "  CertificateId = $CERT_ID"

# ── 2. 轮询签发状态（Status: 0待验证 1已通过 2审核中 …）────────────
echo "▸ 等待 DNS 验证与签发（通常 1–10 分钟）…"
for i in $(seq 1 60); do
  ST="$(tccli ssl DescribeCertificate --region "$REGION" --CertificateId "$CERT_ID" \
        | jq_py 'print(d.get("Status"))')"
  case "$ST" in
    1) echo "  ✓ 已签发"; break;;
    0|2|4) printf "\r  状态=%s 第%d次轮询…" "$ST" "$i"; sleep 15;;
    *) echo; echo "✗ 签发失败，Status=$ST。去控制台 SSL 证书页查原因"; exit 1;;
  esac
  [[ $i -eq 60 ]] && { echo; echo "✗ 15分钟未签发，Status=$ST"; exit 1; }
done

# ── 3. 下载证书（nginx 格式）───────────────────────────────────────
echo "▸ 下载证书"
tccli ssl DownloadCertificate --region "$REGION" --CertificateId "$CERT_ID" \
  | jq_py 'import base64;open("'"$WORK"'/cert.zip","wb").write(base64.b64decode(d["Content"]))'
unzip -qo "$WORK/cert.zip" -d "$WORK/cert"
CRT="$(find "$WORK/cert" -name '*bundle.crt' -o -name '*.crt' | grep -i nginx | head -1)"
KEY="$(find "$WORK/cert" -name '*.key' | grep -i nginx | head -1)"
[[ -n "$CRT" && -n "$KEY" ]] || { echo "✗ 压缩包里没找到 nginx 证书文件"; find "$WORK/cert" -type f; exit 1; }

# ── 4. 上传并配置 nginx ────────────────────────────────────────────
echo "▸ 上传证书到服务器"
ssh "$SSH_ALIAS" "mkdir -p /etc/nginx/ssl/$DOMAIN && chmod 700 /etc/nginx/ssl"
scp -q "$CRT" "$SSH_ALIAS:/etc/nginx/ssl/$DOMAIN/fullchain.crt"
scp -q "$KEY" "$SSH_ALIAS:/etc/nginx/ssl/$DOMAIN/privkey.key"
ssh "$SSH_ALIAS" "chmod 600 /etc/nginx/ssl/$DOMAIN/privkey.key"

echo "▸ 写入 nginx 站点配置（反代 → 127.0.0.1:$UPSTREAM_PORT）"
ssh "$SSH_ALIAS" "cat > /etc/nginx/sites-available/$DOMAIN" <<NGINX
# soukmind · $DOMAIN —— 由 setup-tls.sh 生成，勿手改（改了下次会被覆盖）
server {
    listen 80;
    listen [::]:80;
    server_name $DOMAIN;
    location /.well-known/acme-challenge/ { root /var/www/html; }
    location / { return 301 https://\$host\$request_uri; }
}

server {
    # http2 写法随版本而变：nginx ≥1.25.1 用独立的 \`http2 on;\`，1.24 及以下写在 listen 行。
    # 这台是 1.24.0，用老式写法；升级 nginx 后可改回 \`http2 on;\`。
    listen 443 ssl http2;
    listen [::]:443 ssl http2;
    server_name $DOMAIN;

    ssl_certificate     /etc/nginx/ssl/$DOMAIN/fullchain.crt;
    ssl_certificate_key /etc/nginx/ssl/$DOMAIN/privkey.key;
    ssl_protocols       TLSv1.2 TLSv1.3;
    ssl_ciphers         ECDHE-ECDSA-AES128-GCM-SHA256:ECDHE-RSA-AES128-GCM-SHA256:ECDHE-ECDSA-CHACHA20-POLY1305:ECDHE-RSA-CHACHA20-POLY1305;
    ssl_prefer_server_ciphers off;
    ssl_session_cache   shared:SSL:10m;
    ssl_session_timeout 1d;
    add_header Strict-Transport-Security "max-age=31536000" always;

    # SSE 流式响应：关缓冲，长超时（soukmind 对话是 SSE，缓冲会把流打断）
    proxy_buffering off;
    proxy_cache off;
    proxy_read_timeout 300s;
    proxy_send_timeout 300s;

    client_max_body_size 32m;

    location / {
        proxy_pass http://127.0.0.1:$UPSTREAM_PORT;
        proxy_http_version 1.1;
        proxy_set_header Host              \$host;
        proxy_set_header X-Real-IP         \$remote_addr;
        proxy_set_header X-Forwarded-For   \$proxy_add_x_forwarded_for;
        proxy_set_header X-Forwarded-Proto \$scheme;
        proxy_set_header Connection        "";
    }
}
NGINX

ssh "$SSH_ALIAS" "ln -sfn /etc/nginx/sites-available/$DOMAIN /etc/nginx/sites-enabled/$DOMAIN && rm -f /etc/nginx/sites-enabled/default && nginx -t && systemctl reload nginx"

# ── 5. 验证 ────────────────────────────────────────────────────────
echo "▸ 验证"
curl -sI -m 10 "https://$DOMAIN/" | head -1 || echo "  (后端未起时非 200 属正常，看的是 TLS 握手是否通过)"
echo | openssl s_client -connect "$DOMAIN:443" -servername "$DOMAIN" 2>/dev/null \
  | openssl x509 -noout -subject -issuer -dates 2>/dev/null || echo "  ✗ TLS 握手失败：查 443 防火墙 / DNS 解析"
echo "✓ 完成。证书 90 天有效，到期前重跑本脚本即可。"
