#!/usr/bin/env bash
# Let's Encrypt 证书全自动：签发 → 装进 nginx → reload → 之后 60 天自动续期，无需人工介入。
#
# 与同目录 setup-tls.sh 的区别：
#   setup-tls.sh      腾讯云免费证书(TrustAsia)，90 天有效，**到期要手工重跑**，且只支持 DNSPod
#   setup-tls-acme.sh acme.sh + Let's Encrypt，60 天由服务器上的 cron 自动续期并 reload
# 证书路径与 setup-tls.sh 保持一致，所以可以直接接管已有站点，nginx 配置不用改。
#
# 解析在哪家就用哪家的插件（**不需要把 NS 搬来搬去**）：
#   www.hxmall.top            → DNSPod  → dns_tencent
#   ichain.top / hxtech.top   → 阿里云   → dns_ali
#
# 签证书**不需要 ICP 备案**：ACME 走 DNS-01 验证，全程只写 TXT 记录，不碰 80/443，
# 也不要求网站可访问。备案只决定站点能不能对外提供服务，与能否拿到证书无关。
#
# 前置：服务器上已装 acme.sh（未装则本脚本会给出安装命令后退出）；本机有对应的凭据文件。
#
# 泛域名由 **Let's Encrypt 免费签发**。阿里云/腾讯云自家免费证书只给单域名，但那是另一个
# 产品，这里没用到 —— 它们在本脚本里只当 DNS 服务商，被调 API 写一条 TXT 而已。
#
# 用法：bash setup-tls-acme.sh <域名[,域名...]> [dns_tencent|dns_ali]
#   例：bash setup-tls-acme.sh www.hxmall.top            # 默认 dns_tencent
#       bash setup-tls-acme.sh www.ichain.top dns_ali
#       bash setup-tls-acme.sh '*.ichain.top,ichain.top' dns_ali   # 泛域名 + 裸域，一张证书
# 多域名用逗号分隔，第一个是主域名（决定证书目录名）。
# 注意 `*.x.com` **不覆盖裸域** `x.com`，要覆盖就得像上面那样把裸域一起列出来；
# 两者的验证记录名同为 `_acme-challenge.x.com`，靠同名两条 TXT 区分，acme.sh 自行处理。
set -euo pipefail

DOMAIN_LIST="${1:?用法: bash setup-tls-acme.sh <域名[,域名...]> [dns_tencent|dns_ali]}"
IFS=',' read -r -a DOMAINS <<<"$DOMAIN_LIST"
DOMAIN="${DOMAINS[0]}"
DNS_PLUGIN="${2:-dns_tencent}"
TX_ENV_DIR="${TX_ENV_DIR:-$HOME/work/env/tencent}"
SSH_ALIAS="${SSH_ALIAS:-soukmind-tx}"
ACCOUNT_EMAIL="${ACCOUNT_EMAIL:-nearone@neargo.ai}"

# 每家插件要的变量名不同，凭据文件也分开放 —— 一家的 AK 泄漏不牵连另一家。
case "$DNS_PLUGIN" in
  dns_tencent)
    CRED_FILE="${CRED_FILE:-$TX_ENV_DIR/dnspod-acme.env}"
    REQUIRED_VARS=(Tencent_SecretId Tencent_SecretKey)
    CRED_HINT="腾讯云访问管理新建子用户，只给 QcloudDNSPodFullAccess"
    ;;
  dns_ali)
    CRED_FILE="${CRED_FILE:-$HOME/work/env/aliyun/ali-acme.env}"
    REQUIRED_VARS=(Ali_Key Ali_Secret)
    CRED_HINT="阿里云 RAM 新建子用户，只给 AliyunDNSFullAccess"
    ;;
  *) echo "✗ 不支持的插件 $DNS_PLUGIN（可选 dns_tencent / dns_ali）"; exit 1 ;;
esac

if [[ ! -f "$CRED_FILE" ]]; then
  echo "✗ 找不到凭据文件 $CRED_FILE"
  echo "  $CRED_HINT，把 AK 写成两行："
  printf '      %s=...\n' "${REQUIRED_VARS[@]}"
  exit 1
fi
set -a; source "$CRED_FILE"; set +a

CRED_EXPORTS=""
for v in "${REQUIRED_VARS[@]}"; do
  [[ -n "${!v:-}" ]] || { echo "✗ $CRED_FILE 里缺 $v"; exit 1; }
  CRED_EXPORTS+="export $v='${!v}'"$'\n'
done

echo "▸ 检查服务器上的 acme.sh"
ssh "$SSH_ALIAS" 'sudo -n test -x /root/.acme.sh/acme.sh' || {
  echo "✗ 服务器上没装 acme.sh。先跑："
  echo "    ssh $SSH_ALIAS 'curl -fsS -o /tmp/a.sh https://get.acme.sh && sudo sh /tmp/a.sh email=$ACCOUNT_EMAIL'"
  exit 1
}

# 证书目录名去掉泛域名的 '*.'，否则路径里带星号，nginx 和 shell 都难伺候
SSL_NAME="${DOMAIN#\*.}"

# 拼 acme.sh 的 -d 参数；每个域名单引号包住，防止 '*' 被远端 shell 展开成文件名
D_ARGS=""
for d in "${DOMAINS[@]}"; do D_ARGS+=" -d '$d'"; done

# 凭据与签发脚本经 stdin 送进去执行 —— 不进 argv，不出现在服务器的 ps 里。
echo "▸ 签发 + 安装（${DOMAINS[*]} via $DNS_PLUGIN，首次约 1–3 分钟，等 DNS TXT 生效）"
ssh "$SSH_ALIAS" "sudo -n bash -s" <<REMOTE
set -euo pipefail
$CRED_EXPORTS
ACME=/root/.acme.sh/acme.sh
SSL_DIR=/etc/nginx/ssl/$SSL_NAME

\$ACME --set-default-ca --server letsencrypt

# --issue 失败时不会碰 \$SSL_DIR 里的现有证书，所以签发过程中站点始终可用
\$ACME --issue --dns $DNS_PLUGIN$D_ARGS --keylength ec-256 || {
  rc=\$?
  # 2 = 证书尚未到续期窗口（Already next renewal time），不是错误
  [ \$rc -eq 2 ] && echo "  (证书还在有效期内且未到续期窗口，跳过签发)" || exit \$rc
}

mkdir -p "\$SSL_DIR"
# reloadcmd 里 nginx -t 失败就不 reload —— 站点还没配好时证书照样装，只是不重载
\$ACME --install-cert -d '$DOMAIN' --ecc \\
  --fullchain-file "\$SSL_DIR/fullchain.crt" \\
  --key-file       "\$SSL_DIR/privkey.key" \\
  --reloadcmd      "chmod 600 \$SSL_DIR/privkey.key && { nginx -t && systemctl reload nginx || echo '  (nginx 配置未就绪，证书已装但未重载)'; }"
REMOTE

echo "▸ 验证"
ssh "$SSH_ALIAS" "
  sudo -n openssl x509 -in /etc/nginx/ssl/$SSL_NAME/fullchain.crt -noout -subject -issuer -dates
  echo '--- 自动续期任务 ---'
  sudo -n crontab -l | grep -i acme || echo '  ✗ 没有 acme cron，续期不会自动发生！'
  echo '--- acme.sh 已纳管的证书与下次续期时间 ---'
  sudo -n /root/.acme.sh/acme.sh --list
"
echo "✓ 完成。之后 acme.sh 会在到期前自动续签并 reload nginx，不需要再跑本脚本。"
