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
# ⚠️ 是 certs 不是 cert。**线上早就是这个目录**，而 env 里那两条
# WX_*_KEY_PATH 指的就是它 —— 写成单数会安静地造出第二份私钥副本，
# 服务仍然读老的那份，于是「传上去了却不生效」，而两处内容还都是对的。
# 2026-09-04 我就这么传错过一次（本条注释是那次的产物）。
REMOTE_DIR="${REMOTE_DIR:-/opt/ai-shop/certs}"
SVC_USER="${SVC_USER:-deploy}"

say() { printf '\033[36m›\033[0m %s\n' "$1"; }
ok()  { printf '  \033[32m✓\033[0m %s\n' "$1"; }
die() { printf '  \033[31m✗\033[0m %s\n' "$1" >&2; exit 1; }

# 只搬这两份 —— **服务端只用它们**（env 里就这两条 _PATH）。
# apiclient_cert.pem 服务端用不到：APIv3 签名用私钥，验签用微信支付公钥，
# 商户证书只在本地算序列号时用。p12 与 README 同理不传。
# 每多传一份就多一处要护的东西。
FILES=(apiclient_key.pem wxpay_pub_key.pem)
# 只在本地用，不上传（算序列号与验配对）
LOCAL_ONLY_CERT="apiclient_cert.pem"

# ── ① 本地校验：是不是我们以为的那种文件 ────────────────────────────────
say "校验本地凭据（$CERT_DIR）"
[ -d "$CERT_DIR" ] || die "找不到目录：$CERT_DIR"
for f in "${FILES[@]}" "$LOCAL_ONLY_CERT"; do
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
ok "${#FILES[@]} 份都能读"

# ── ⑤ 内容一致（比指纹，不比内容）───────────────────────────────────────
say "比对指纹"
for f in "${FILES[@]}"; do
    L=$(shasum -a 256 "$CERT_DIR/$f" | cut -d' ' -f1)
    R=$(ssh "$HOST" "sudo sha256sum '$REMOTE_DIR/$f' | cut -d' ' -f1")
    [ "$L" = "$R" ] || die "$f 指纹不一致（传输出问题）"
done
ok "${#FILES[@]} 份指纹一致"

# ── ⑥ env 里那几个非文件的值：只报「填没填」，不报值 ──────────────────
say "核对 env（只看填没填，不取值）"
for k in WX_MCHID WX_SERIAL_NO WX_APIV3_KEY; do
    n=$(ssh "$HOST" "sudo grep -E '^$k=' /opt/ai-shop/shop-app.env 2>/dev/null | head -1 | cut -d= -f2- | wc -c")
    [ "${n:-0}" -gt 1 ] && ok "$k 已填（$((n-1)) 位）" || printf '  \033[33m!\033[0m %s 是空的\n' "$k"
done
# 路径必须指到我们刚传的地方 —— 指到别处的话「传上去了却不生效」
for k in WX_PRIVATE_KEY_PATH WX_PLATFORM_PUBLIC_KEY_PATH; do
    v=$(ssh "$HOST" "sudo grep -E '^$k=' /opt/ai-shop/shop-app.env 2>/dev/null | head -1 | cut -d= -f2-")
    case "$v" in
        "$REMOTE_DIR"/*) ok "$k → $v" ;;
        "") printf '  \033[33m!\033[0m %s 没配\n' "$k" ;;
        *) die "$k 指向 $v，不是本次上传的 $REMOTE_DIR —— 传了也不生效" ;;
    esac
done
# 序列号必须与证书对得上：换了证书没改这一行，通道会拒签而报错不提序列号
RS=$(ssh "$HOST" "sudo grep -E '^WX_SERIAL_NO=' /opt/ai-shop/shop-app.env | head -1 | cut -d= -f2-")
[ -z "$RS" ] || [ "$RS" = "$SERIAL" ] || die "env 里的 WX_SERIAL_NO 与证书不符（证书是 $SERIAL）"
[ "$RS" = "$SERIAL" ] && ok "序列号与证书一致"

cat <<TIP

凭据已就位。改动过 env 的话记得重启，并**守到 health=200 再走开** ——
缺凭据时 WechatPayChannelConfig 会拒绝装配（刻意的），
表现是崩溃重启循环，不是降级运行。
TIP
