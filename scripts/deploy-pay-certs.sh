#!/usr/bin/env bash
#
# 把通道凭据从本机搬到服务器。**只搬文件，不读内容、不进日志、不进仓库。**
#
# 用法：
#   scripts/deploy-pay-certs.sh                     # 默认 ~/cert/<mchid>
#   CERT_DIR=~/cert/1117261658 scripts/deploy-pay-certs.sh
#   DRY=1 scripts/deploy-pay-certs.sh               # 只校验与比对，不传
#
# ─────────────────────────────────────────────────────────────────────────
# **为什么要有这个脚本，而不是手敲 scp**
#
# 2026-09-04 微信支付上线时踩过一次：root 上去 scp，落地是 700 root:root，
# 权限位看着完全正确 —— 而服务跑在 deploy 下，**读不到**。
# WechatPayChannelConfig 的「缺凭据就拒绝装配」按设计生效（宁可起不来，
# 也不带空凭据跑到第一次真实下单才失败），于是崩溃重启循环、health=000，
# 线上停了约 4 分钟。chown 之后立刻恢复。
#
# 那个设计是对的。错的是部署这一步少了一次「**换个身份读读看**」——
# 所以第 4 步在这里，且不是可选的。
#
# ⚠️ 私钥用 -path 而不是内联进 env：
# 环境变量会出现在 `ps -eo args`、崩溃日志、以及 env 文件的备份里。
# 文件在磁盘上，权限管得住；环境变量管不住。
set -euo pipefail

HOST="${HOST:-soukmind-tx}"
CERT_DIR="${CERT_DIR:-$HOME/cert/1117261658}"
REMOTE_DIR="${REMOTE_DIR:-/opt/ai-shop/cert}"
SVC_USER="${SVC_USER:-deploy}"

say() { printf '\033[36m›\033[0m %s\n' "$1"; }
ok()  { printf '  \033[32m✓\033[0m %s\n' "$1"; }
die() { printf '  \033[31m✗\033[0m %s\n' "$1" >&2; exit 1; }

# 只搬这三份。p12 与 README 不传 —— 服务端用不到，而每多传一份就多一处要护的东西
FILES=(apiclient_key.pem apiclient_cert.pem wxpay_pub_key.pem)

# ── ① 本地校验：是不是我们以为的那种文件 ────────────────────────────────
say "校验本地凭据（$CERT_DIR）"
[ -d "$CERT_DIR" ] || die "找不到目录：$CERT_DIR"
for f in "${FILES[@]}"; do
    [ -f "$CERT_DIR/$f" ] || die "缺少 $f"
done
# 按类型校验，不是按文件名 —— 名字对而内容放错是真会发生的
openssl pkey -in "$CERT_DIR/apiclient_key.pem" -noout 2>/dev/null \
    || die "apiclient_key.pem 不是可解析的私钥"
openssl x509 -in "$CERT_DIR/apiclient_cert.pem" -noout 2>/dev/null \
    || die "apiclient_cert.pem 不是证书"
openssl pkey -pubin -in "$CERT_DIR/wxpay_pub_key.pem" -noout 2>/dev/null \
    || die "wxpay_pub_key.pem 不是公钥"

# 私钥与证书必须是同一把 —— 配错的表现是签名被通道拒，而报错不会说「你配错了对」
K=$(openssl pkey -in "$CERT_DIR/apiclient_key.pem" -pubout 2>/dev/null | shasum -a 256 | cut -d' ' -f1)
C=$(openssl x509 -in "$CERT_DIR/apiclient_cert.pem" -noout -pubkey 2>/dev/null | shasum -a 256 | cut -d' ' -f1)
[ "$K" = "$C" ] || die "私钥与证书不配对（公钥指纹不一致）"
ok "私钥与证书配对"

SERIAL=$(openssl x509 -in "$CERT_DIR/apiclient_cert.pem" -noout -serial | cut -d= -f2)
ENDDATE=$(openssl x509 -in "$CERT_DIR/apiclient_cert.pem" -noout -enddate | cut -d= -f2)
# 到期前 30 天就该有人知道 —— 过期那天的表现是全站收不了钱
openssl x509 -in "$CERT_DIR/apiclient_cert.pem" -noout -checkend $((30*86400)) >/dev/null \
    || die "证书 30 天内到期（$ENDDATE）—— 先换证再部署"
ok "证书序列号 $SERIAL · 有效期至 $ENDDATE"

if [ "${DRY:-}" = "1" ]; then
    say "DRY=1：到此为止，没有上传"
    exit 0
fi

# ── ② 传 ────────────────────────────────────────────────────────────────
say "上传到 $HOST:$REMOTE_DIR"
ssh "$HOST" "sudo mkdir -p '$REMOTE_DIR'"
for f in "${FILES[@]}"; do
    scp -q "$CERT_DIR/$f" "$HOST:/tmp/$f"
    ssh "$HOST" "sudo mv /tmp/$f '$REMOTE_DIR/$f'"
done

# ── ③ 属主与权限 ────────────────────────────────────────────────────────
# 目录 750、文件 640，属主是服务用户。**先 chown 再 chmod** ——
# 反过来的话 chown 之后权限位还在，但那一瞬间文件是可读的
ssh "$HOST" "
    sudo chown -R '$SVC_USER:$SVC_USER' '$REMOTE_DIR'
    sudo chmod 750 '$REMOTE_DIR'
    sudo chmod 640 '$REMOTE_DIR'/*.pem
"
ok "属主 $SVC_USER · 目录 750 · 文件 640"

# ── ④ 换个身份读读看 ────────────────────────────────────────────────────
#
# **这一步不是可选的。** 上面三步都「成功」而服务读不到，正是 09-04 那次的形状：
# 权限位看着正确，而它属于 root。判据必须是「服务用户能不能读」，
# 不是「文件在不在、位对不对」。
say "以服务用户 $SVC_USER 的身份验证可读"
for f in "${FILES[@]}"; do
    ssh "$HOST" "sudo -u '$SVC_USER' test -r '$REMOTE_DIR/$f'" \
        || die "$SVC_USER 读不到 $f —— 服务起不来，且症状是崩溃重启循环 + health=000"
done
ok "三份都能读"

# ── ⑤ 内容一致（比指纹，不比内容）───────────────────────────────────────
say "比对指纹"
for f in "${FILES[@]}"; do
    L=$(shasum -a 256 "$CERT_DIR/$f" | cut -d' ' -f1)
    R=$(ssh "$HOST" "sudo sha256sum '$REMOTE_DIR/$f' | cut -d' ' -f1")
    [ "$L" = "$R" ] || die "$f 指纹不一致（传输出问题）"
done
ok "三份指纹一致"

cat <<TIP

凭据已就位。**还差 env 里的三个值**（它们不是文件，脚本不碰）：
  SHOP_PAY_WECHAT_MCHID           商户号
  SHOP_PAY_WECHAT_SERIAL_NO       $SERIAL
  SHOP_PAY_WECHAT_APIV3_KEY       ← 只有你知道，从商户平台取

以及三条指向文件的（照抄即可）：
  SHOP_PAY_WECHAT_PRIVATE_KEY_PATH=$REMOTE_DIR/apiclient_key.pem
  SHOP_PAY_WECHAT_PLATFORM_PUBLIC_KEY_PATH=$REMOTE_DIR/wxpay_pub_key.pem

改完 /opt/ai-shop/shop-app.env 后重启，并**守到 health=200 再走开** ——
缺凭据时 WechatPayChannelConfig 会拒绝装配（刻意的），
表现是崩溃重启循环，不是降级运行。
TIP
