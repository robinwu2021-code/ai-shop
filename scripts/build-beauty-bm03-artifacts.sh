#!/usr/bin/env bash
set -euo pipefail
cd "$(dirname "$0")/.."
export JAVA_HOME="${BEAUTY_JAVA_HOME:-/opt/homebrew/opt/openjdk@21/libexec/openjdk.jdk/Contents/Home}"
output="${BEAUTY_BM03_ARTIFACT_DIR:-/tmp/beauty-bm03-artifacts}"
mkdir -p "$output"
maven=(mvn -o -Dmaven.repo.local=/tmp/codex-beauty-bm01-m2)
"${maven[@]}" -f backend/pom.xml -pl shop-store-mybatis -am install -DskipTests > "$output/shared.log" 2>&1
"${maven[@]}" -f backend-plugins/shop-industry-beauty/pom.xml install -Dtest=BookingServerWiringTest,BeautyJobTargetTest -Dsurefire.failIfNoSpecifiedTests=false > "$output/beauty.log" 2>&1
cp backend-plugins/shop-industry-beauty/beauty-server/target/beauty-server-0.1.0-SNAPSHOT.jar "$output/beauty-server.jar"
for shape in embedded standalone retail; do
  if [[ "$shape" == retail ]]; then
    "${maven[@]}" -f backend/pom.xml -pl shop-app -am package -DskipTests > "$output/$shape-build.log" 2>&1
  else
    "${maven[@]}" -f backend/pom.xml -pl shop-app -am package -DskipTests -P"beauty-$shape" > "$output/$shape-build.log" 2>&1
  fi
  cp backend/shop-app/target/shop-app-0.1.0-SNAPSHOT.jar "$output/$shape.jar"
done
"${maven[@]}" -f backend/pom.xml -pl job/job-worker -am package -DskipTests > "$output/worker-build.log" 2>&1
cp backend/job/job-worker/target/job-worker-0.1.0-SNAPSHOT.jar "$output/job-worker.jar"
shasum -a 256 "$output"/*.jar > "$output/SHA256SUMS"
cat "$output/SHA256SUMS"
