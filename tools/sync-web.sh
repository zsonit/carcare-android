#!/usr/bin/env bash
# 从 iOS 工程（或上游仓库）同步网页资源到 Android assets
# 用法： ./tools/sync-web.sh [源目录，默认 ../carcare-ios/Resources/web]
set -e
cd "$(dirname "$0")/.."
SRC="${1:-../carcare-ios/Resources/web}"
DST="app/src/main/assets/web"
for f in index.html manifest.json sw.js icon-192.png icon-512.png; do
  cp "$SRC/$f" "$DST/$f"
  echo "synced $f"
done
