#!/usr/bin/env bash
# Reproduce the self-hosted CodeMirror 6 bundle. node_modules is NOT committed; run this to rebuild.
set -euo pipefail
cd "$(dirname "$(readlink -f "$0")")"
npm install --no-audit --no-fund --loglevel=error
OUT_DIR="../../runway-app/src/main/resources/static/vendor"
mkdir -p "$OUT_DIR"
./node_modules/.bin/esbuild entry.mjs \
  --bundle --minify --format=iife --target=es2019 --legal-comments=none \
  --outfile="$OUT_DIR/codemirror.min.js"
cp codemirror.css "$OUT_DIR/codemirror.min.css"
echo "Built codemirror.min.js ($(wc -c < "$OUT_DIR/codemirror.min.js") bytes) + codemirror.min.css into $OUT_DIR"
