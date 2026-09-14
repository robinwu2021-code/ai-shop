#!/usr/bin/env bash
# MySQL 9.7 LTS 待机实例 · 安装。在生产服务器上以 root 执行，**幂等**：
# 重跑不会重复初始化、不会动已有数据；改了 my.cnf 重跑即可生效（会重启本实例）。
#
#   scp -r deploy/tencent/mysql97 soukmind-tx-root:/opt/mysql/deploy
#   ssh soukmind-tx-root 'bash /opt/mysql/deploy/install.sh'
#
# **不经过 dpkg。** 这台机的 mysql-common 是 MariaDB 提供的（1:12.3.2+maria），
# apt 装 MySQL 会与之冲突，最坏情况是把 MariaDB 卸掉。所以用官方通用二进制包
# （minimal 版，80MB；完整版 936MB），整套装在 /opt/mysql 下自成一体。
set -euo pipefail

VER=9.7.2
PKG="mysql-${VER}-linux-glibc2.28-x86_64-minimal"
URL="https://cdn.mysql.com/Downloads/MySQL-${VER%.*}/${PKG}.tar.xz"
BASE=/opt/mysql
HERE="$(cd "$(dirname "$0")" && pwd)"
CNF=/etc/mysql97/my.cnf
DATA=/var/lib/mysql97
LOGD=/var/log/mysql97
PORT=3307
export LD_LIBRARY_PATH="$BASE/lib-compat"

say() { printf '\n== %s\n' "$*"; }
die() { printf '\n✗ %s\n' "$*" >&2; exit 1; }

# ── 0. 护栏 ────────────────────────────────────────────────────────────────────
[ "$(id -u)" = 0 ] || die "要以 root 执行"
id mysql >/dev/null 2>&1 || die "没有 mysql 系统用户"
# 端口被别的进程占着就停：不去猜那是谁
occupant=$(ss -ltnpH "sport = :$PORT" || true)
if [ -n "$occupant" ] && ! systemctl is-active -q mysql97; then
  die "端口 $PORT 已被占用：$occupant"
fi
# 「没碰到 MariaDB」的判据：它的进程号装前装后必须一样
MARIA_PID_BEFORE=$(systemctl show -p MainPID --value mariadb)

# ── 1. 二进制包 ────────────────────────────────────────────────────────────────
say "二进制包 $PKG"
mkdir -p "$BASE/src"
[ -f "$BASE/src/$PKG.tar.xz" ] || curl -fsS -m 600 -o "$BASE/src/$PKG.tar.xz" "$URL"
xz -t "$BASE/src/$PKG.tar.xz" || die "压缩包损坏，删掉 $BASE/src/$PKG.tar.xz 重跑"
[ -d "$BASE/$PKG" ] || tar -xJf "$BASE/src/$PKG.tar.xz" -C "$BASE"
ln -sfn "$BASE/$PKG" "$BASE/current"

# ── 2. libaio 兼容（私有目录，只对本服务生效，不碰系统库目录）───────────────
# 通用包链接 libaio.so.1，Ubuntu 24.04 改名成了 libaio.so.1t64（time_t 64 位迁移）
mkdir -p "$BASE/lib-compat"
ln -sfn /usr/lib/x86_64-linux-gnu/libaio.so.1t64 "$BASE/lib-compat/libaio.so.1"
# 先收进变量再判：`ldd | grep -q` 在 pipefail 下会因 SIGPIPE 误判
missing=$(ldd "$BASE/current/bin/mysqld" | grep "not found" || true)
[ -z "$missing" ] || die "缺库：$missing"

# ── 3. 配置与目录 ──────────────────────────────────────────────────────────────
say "配置与目录"
install -d -m 755 /etc/mysql97
install -m 644 "$HERE/my.cnf" "$CNF"
install -d -m 750 -o mysql -g mysql "$DATA" "$LOGD"
# 未知选项会让 mysqld 拒绝启动 —— 在这里先炸，不留下一个半启动的服务
"$BASE/current/bin/mysqld" --defaults-file="$CNF" --validate-config \
  || die "my.cnf 校验不过（上面是 mysqld 的原话）"

# ── 4. 初始化：只在数据目录为空时 ────────────────────────────────────────────
if [ -z "$(ls -A "$DATA")" ]; then
  say "初始化数据目录（root@localhost 暂为空密码，第 6 步立刻改认操作系统身份）"
  "$BASE/current/bin/mysqld" --defaults-file="$CNF" --initialize-insecure
else
  say "数据目录已有内容，跳过初始化"
fi

# ── 5. systemd 与日志轮转 ──────────────────────────────────────────────────────
say "systemd"
install -m 644 "$HERE/mysql97.service" /etc/systemd/system/mysql97.service
install -m 644 "$HERE/logrotate" /etc/logrotate.d/mysql97
systemctl daemon-reload
systemctl enable -q mysql97
systemctl restart mysql97
MYSQL=("$BASE/current/bin/mysql" --defaults-file="$CNF" -uroot)
for _ in $(seq 60); do
  "$BASE/current/bin/mysqladmin" --defaults-file="$CNF" -uroot ping >/dev/null 2>&1 && break
  sleep 1
done
"$BASE/current/bin/mysqladmin" --defaults-file="$CNF" -uroot ping >/dev/null 2>&1 \
  || die "60 秒内没起来，看 $LOGD/error.log 与 journalctl -u mysql97"

# ── 6. root 只认操作系统身份：本机 root 经 socket 登录，不存在任何密码 ────────
"${MYSQL[@]}" -e "ALTER USER 'root'@'localhost' IDENTIFIED WITH auth_socket;"
# 反证：换成非 root 的操作系统用户，必须被**拒绝** —— 而且要认准拒绝的原话。
# 只看「失败了」会给假通过：sudo 默认清掉 LD_LIBRARY_PATH，客户端若因缺库跑不起来，
# 同样是非零退出，看上去就像「被挡住了」。所以显式传环境变量，并要求出现 Access denied。
denied=$(sudo -u nobody env LD_LIBRARY_PATH="$LD_LIBRARY_PATH" \
  "$BASE/current/bin/mysql" --defaults-file="$CNF" -uroot -e "SELECT 1" 2>&1 || true)
case "$denied" in
  *"Access denied"*) echo "反证通过：非 root 的系统用户被拒（${denied:0:70}…）" ;;
  *) die "反证没有得到预期的拒绝，而是：$denied" ;;
esac

# ── 7. 验收 ────────────────────────────────────────────────────────────────────
say "验收"
"${MYSQL[@]}" -t -e "SELECT VERSION() AS version, @@port AS port, @@bind_address AS bind,
  @@performance_schema AS pfs, @@innodb_buffer_pool_size DIV 1048576 AS bp_mb,
  @@log_bin AS binlog, @@collation_server AS collation,
  (SELECT COUNT(*) FROM information_schema.plugins
    WHERE plugin_name = 'mysqlx' AND plugin_status = 'ACTIVE') AS mysqlx_active;"
# ↑ 不能写 @@mysqlx：mysqlx=OFF 时 X 插件根本不加载，这个变量也就不存在（第一次跑就栽在这）
echo "监听："; ss -ltnpH | awk '$4 ~ /:(3306|3307|33060)$/ {print "  "$4"  "$6}'
echo "常驻内存：$(ps -o rss= -C mysqld | awk '{s+=$1} END{printf "%.0f MB", s/1024}')"
echo "磁盘：程序 $(du -sh "$BASE/$PKG" | cut -f1) · 数据 $(du -sh "$DATA" | cut -f1)"
MARIA_PID_AFTER=$(systemctl show -p MainPID --value mariadb)
[ "$MARIA_PID_BEFORE" = "$MARIA_PID_AFTER" ] \
  || die "MariaDB 进程号变了（$MARIA_PID_BEFORE → $MARIA_PID_AFTER）—— 查它为什么重启过"
echo "MariaDB 未受影响：进程号 $MARIA_PID_AFTER 装前装后一致"
