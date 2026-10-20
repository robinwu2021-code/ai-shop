#!/usr/bin/env bash
# Deploy a validated points-only ancestor of the current canonical source commit.
set -euo pipefail
ROOT="$(git rev-parse --show-toplevel)"
cd "$ROOT"
SNAPSHOT="$(git rev-parse "${1:?snapshot commit required}")"
CANONICAL="$(git rev-parse "${2:?canonical commit required}")"
[ -z "$(git status --porcelain --untracked-files=normal)" ] || { echo 'Working tree must be clean' >&2; exit 1; }
[ "$(git rev-parse HEAD)" = "$CANONICAL" ] || { echo 'HEAD must be canonical source' >&2; exit 1; }
git merge-base --is-ancestor "$SNAPSHOT" "$CANONICAL"
[ "$(git ls-remote origin refs/heads/codex/ai-shop-codex | cut -f1)" = "$CANONICAL" ] || { echo 'Canonical source is not current remote HEAD' >&2; exit 1; }
COMMON="$(git rev-parse --git-common-dir)"
node --input-type=module - "$SNAPSHOT" "$COMMON" <<'JS'
import {execFileSync} from 'node:child_process';
import {readFileSync} from 'node:fs';
import {resolve} from 'node:path';
import {validateFiles} from './scripts/release/points-ops.mjs';
const [snapshot,common]=process.argv.slice(2);
const manifest=JSON.parse(execFileSync('git',['show',`${snapshot}:scripts/release/points-ops.json`],{encoding:'utf8'}));
const receipt=JSON.parse(readFileSync(resolve(common,'points-ops-snapshot-receipts',snapshot+'.json'),'utf8'));
if(receipt.profile!=='points-ops-snapshot'||receipt.head!==snapshot||receipt.base!==manifest.releaseAnchor)throw new Error('Missing exact snapshot validation');
const paths=execFileSync('git',['diff','--name-only','-z',manifest.releaseAnchor,snapshot],{encoding:'utf8'}).split('\0').filter(Boolean);
validateFiles(paths,manifest.allowedFiles);
console.log(`Deploy artifact ${snapshot}; live baseline ${manifest.releaseAnchor}; canonical source contains this snapshot`);
JS
HOST="${HOST:-soukmind-tx}"
BASE="$(git show "$SNAPSHOT:scripts/release/points-ops.json" | node -e 'let s="";process.stdin.on("data",d=>s+=d).on("end",()=>console.log(JSON.parse(s).releaseAnchor.slice(0,9)))')"
# Refuse to overwrite another task's newly deployed application.
for APP in shop-app pay-svc; do
  LIVE="$(ssh -o ConnectTimeout=10 "$HOST" "readlink -f /data/app/ai-shop/$APP/$APP.jar")"
  case "$LIVE" in *-"$BASE".jar|*-"${SNAPSHOT:0:9}".jar) ;; *) echo "Live $APP changed: $LIVE; stop" >&2; exit 1;; esac
done
REF="$SNAPSHOT" bash scripts/deploy-backend.sh shop-app
REF="$SNAPSHOT" bash scripts/deploy-backend.sh pay-svc
REF="$SNAPSHOT" bash scripts/deploy-frontend.sh ops-web
printf 'Canonical source: %s\nArtifact commit: %s\n' "$CANONICAL" "$SNAPSHOT"
