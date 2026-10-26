#!/usr/bin/env bash
# A full zero-failure receipt may be reused only for identical backend/build-gate contents.
# Read-only: this script never creates or updates a verification receipt.
set -euo pipefail
task_repo="${1:-$(git rev-parse --show-toplevel)}"
task_receipt="$(git -C "$task_repo" rev-parse --absolute-git-dir)/gate-verified-sha"
[ -f "$task_receipt" ] || exit 1
task_verified="$(head -n 1 "$task_receipt")"
[[ "$task_verified" =~ ^[0-9a-f]{40}$ ]] || exit 1
# Match the full-suite receipt, not a compile-only stamp or tolerated known failures.
grep -Eq '编译 \+ 全量 ([89][0-9]{2}|[1-9][0-9]{3,}) 跑 / 0 红' "$task_receipt" || exit 1
# Receipts written before the dining step existed did not test backend-plugins; never reuse them.
grep -Eq '餐饮 ([89][0-9]{2}|[1-9][0-9]{3,}) 跑 / 0 红' "$task_receipt" || exit 1
git -C "$task_repo" merge-base --is-ancestor "$task_verified" HEAD || exit 1
git -C "$task_repo" diff --quiet "$task_verified" HEAD -- \
    backend backend-plugins scripts/check-head-compiles.sh pom.xml mvnw .mvn || exit 1
printf '%s\n' "$task_verified"
