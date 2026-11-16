#!/usr/bin/env bash
# A clean HEAD snapshot needs each workspace's private dependencies as well as hoisted ones.
set -euo pipefail
SOURCE_ROOT="$1"
TARGET_ROOT="$2"
for dependency_scope in '' b-app c-app ops-web; do
  dependency_source="$SOURCE_ROOT/$dependency_scope/node_modules"
  dependency_target="$TARGET_ROOT/$dependency_scope/node_modules"
  if [ -d "$dependency_source" ]; then
    ln -s "$dependency_source" "$dependency_target"
  fi
done
