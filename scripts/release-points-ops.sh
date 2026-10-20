#!/usr/bin/env bash
# Usage: scripts/release-points-ops.sh --check | --push | --deploy
set -euo pipefail
ROOT="$(git rev-parse --show-toplevel)"
cd "$ROOT"
MODE="${1:---check}"
case "$MODE" in --check|--push|--deploy) ;; *) echo 'Use --check, --push or --deploy' >&2; exit 2;; esac
# Release only committed code. Avoid discarding or silently shipping a dirty tree.
[ -z "$(git status --porcelain --untracked-files=normal)" ] || { echo 'Commit release changes first; working tree is dirty' >&2; exit 1; }
git fetch origin codex/ai-shop-codex
SHA="$(git rev-parse HEAD)"
BASE="$(git rev-parse origin/codex/ai-shop-codex)"
if [ "$MODE" = --check ]; then
  printf 'HEAD %s refs/heads/codex/ai-shop-codex %s\n' "$SHA" "$BASE" | SHOP_RELEASE_PROFILE=points-ops bash .githooks/pre-push
  exit 0
fi
if [ "$SHA" != "$BASE" ]; then
  SHOP_RELEASE_PROFILE=points-ops git push origin HEAD:refs/heads/codex/ai-shop-codex
fi
[ "$MODE" = --deploy ] || exit 0
# An up-to-date push must not let deployment bypass the check plan.
COMMON="$(git rev-parse --git-common-dir)"
node - "$COMMON/points-ops-receipts/$SHA.json" "$SHA" <<'JS'
const fs = require('node:fs');
const r = JSON.parse(fs.readFileSync(process.argv[2], 'utf8'));
if (r.profile !== 'points-ops' || r.head !== process.argv[3]) throw new Error('Missing exact-commit release receipt');
JS
REMOTE="$(git ls-remote origin refs/heads/codex/ai-shop-codex | cut -f1)"
[ "$REMOTE" = "$SHA" ] || { echo 'Remote branch moved; review the release again' >&2; exit 1; }
# The point-name contract needs both backend processes before the new form goes live.
REF="$SHA" bash scripts/deploy-backend.sh shop-app
REF="$SHA" bash scripts/deploy-backend.sh pay-svc
REF="$SHA" bash scripts/deploy-frontend.sh ops-web
