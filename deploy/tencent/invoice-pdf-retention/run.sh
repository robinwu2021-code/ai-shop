#!/usr/bin/env bash
set -euo pipefail

base=/data0/app/ai-shop-v2
mode="${1:---check}"
case "$mode" in --check|--apply) ;; *) exit 2 ;; esac
exec 9>"$base/runtime/invoice-pdf-retention.lock"
/usr/bin/flock -n 9 || exit 0

"$base/runtime/d" exec -i ai-shop-v2 sh -ec '
    scratch=$(mktemp -d)
    trap '"'"'rm -rf "$scratch"'"'"' EXIT
    cat > "$scratch/InvoicePdfRetention.class"
    driver=$(unzip -l /app/shop-app.jar | awk '"'"'$NF ~ /^BOOT-INF\/lib\/mysql-connector-j-.*[.]jar$/ {print $NF}'"'"')
    test -n "$driver"
    unzip -p /app/shop-app.jar "$driver" > "$scratch/mysql.jar"
    java -cp "$scratch:$scratch/mysql.jar" InvoicePdfRetention "$1" /data/print-private
' invoice-pdf-retention "$mode" < "$base/runtime/invoice-pdf-retention/InvoicePdfRetention.class"
