#!/bin/bash
set -euo pipefail
repo_root=$(cd "$(dirname "$0")/.." && pwd)
mysql_bin=${MYSQL_BIN:-/opt/homebrew/opt/mysql@9.7/bin}
"$mysql_bin/mysqld" --version | python3 -c 'import sys; v=sys.stdin.read(); assert "MariaDB" not in v and "Ver 9." in v, "MySQL 9 is required"'
task_dir=$(mktemp -d "${TMPDIR:-/tmp}/svc-b07-mysql.XXXXXX")
task_sock="$task_dir/mysql.sock"
task_port=$(python3 - <<'PY'
import socket
with socket.socket() as sock:
    sock.bind(('127.0.0.1',0))
    print(sock.getsockname()[1])
PY
)
cleanup() {
    "$mysql_bin/mysqladmin" --no-defaults --socket="$task_sock" -uroot shutdown >/dev/null 2>&1 || true
    if [ -n "${task_pid:-}" ]; then wait "$task_pid" 2>/dev/null || true; fi
    rm -rf -- "$task_dir"
}
trap cleanup EXIT
"$mysql_bin/mysqld" --no-defaults --initialize-insecure --datadir="$task_dir/data" --log-error="$task_dir/init.log"
"$mysql_bin/mysqld" --no-defaults --datadir="$task_dir/data" --socket="$task_sock" --port="$task_port" --bind-address=127.0.0.1 --skip-log-bin --default-time-zone=+00:00 --mysqlx=0 --log-error="$task_dir/run.log" &
task_pid=$!
for i in {1..100}; do
    if "$mysql_bin/mysqladmin" --no-defaults --socket="$task_sock" -uroot ping --silent >/dev/null 2>&1; then break; fi
    if ! kill -0 "$task_pid" 2>/dev/null; then cat "$task_dir/run.log"; exit 1; fi
    sleep 0.25
done
"$mysql_bin/mysql" --no-defaults --socket="$task_sock" -uroot -e "CREATE DATABASE b07_app CHARACTER SET utf8mb4 COLLATE utf8mb4_bin"
python3 "$repo_root/backend/scripts/gen-service-entitlement-native-fixture.py" "$repo_root" "$task_dir/platform.sql"
"$mysql_bin/mysql" --no-defaults --socket="$task_sock" -uroot b07_app < "$task_dir/platform.sql"
python3 - "$repo_root" "$task_dir/pom.xml" <<'PYXML'
from pathlib import Path
import sys
import os
from xml.sax.saxutils import escape
root = Path(sys.argv[1]).resolve()
modules = ''.join('<module>' + escape(os.path.relpath(root / p, Path(sys.argv[2]).parent.resolve())) + '</module>'
                  for p in ['backend', 'backend-plugins/shop-industry-beauty'])
Path(sys.argv[2]).write_text('<project><modelVersion>4.0.0</modelVersion><groupId>ai.neargo.validation</groupId>'
    '<artifactId>service-entitlement</artifactId><version>1</version><packaging>pom</packaging>'
    '<modules>' + modules + '</modules></project>')
PYXML

B07_NATIVE_APP_URL="jdbc:mysql://127.0.0.1:$task_port/b07_app?useSSL=false&allowPublicKeyRetrieval=true&connectionTimeZone=UTC" \
BEAUTY_NATIVE_MYSQL_PASSWORD='' \
"${MVN:-mvn}" -f "$task_dir/pom.xml" -Pbeauty-embedded -pl :shop-app -am test \
    '-Dtest=WorkOrderEntitlementEmbeddedMysqlTest,WorkOrderEntitlementIT,BeautySchemaMigrationIT#migratesValidatesAndDoesNotRepeat+v9WithoutV10RemainsBaseReady+installedV10PreservesBaseNineReadiness+v16SubmissionReadsDoNotRequireEntitlementColumns,ServiceSubmissionIT#openDraftDoesNotPurchaseAndExplicitSelectionIsExact' \
    -Dsurefire.failIfNoSpecifiedTests=false \
    "-Dbeauty.test.admin-url=jdbc:mysql://127.0.0.1:$task_port/mysql?useSSL=false&allowPublicKeyRetrieval=true&connectionTimeZone=UTC"
