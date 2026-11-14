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
  printf 'HEAD %s refs/heads/codex/ai-shop-codex %s\n' "$SHA" "$BASE" | SHOP_RELEASE_PROFILE=points-ops bash .githooks/pre-push
  [ "$(git rev-parse HEAD)" = "$SHA" ] || { echo 'HEAD moved during checks; stop release' >&2; exit 1; }
  SHOP_RELEASE_PROFILE=points-ops git push origin "$SHA":refs/heads/codex/ai-shop-codex
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
# Remote work may have been integrated while only our script changes were pushed.
# Never deploy that extra application scope under the points-only profile.
node --input-type=module - "$SHA" <<'JS'
import { readFileSync } from 'node:fs';
import { execFileSync } from 'node:child_process';
import { validateFiles } from './scripts/release/points-ops.mjs';
const manifest = JSON.parse(readFileSync('scripts/release/points-ops.json', 'utf8'));
const files = execFileSync('git', ['diff', '--name-only', '-z', '--no-renames', manifest.releaseAnchor, process.argv[2]], { encoding: 'utf8' }).split('\0').filter(Boolean);
validateFiles(files);
JS
REF="$SHA" bash scripts/deploy-backend.sh shop-app
REF="$SHA" bash scripts/deploy-backend.sh pay-svc
REF="$SHA" bash scripts/deploy-frontend.sh ops-web
