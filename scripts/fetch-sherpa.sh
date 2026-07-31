#!/bin/bash
# sherpa-onnx is not published to Maven Central; the AAR comes from GitHub releases.
set -euo pipefail
cd "$(dirname "$0")/.."
V=1.13.4
OUT=app/libs/sherpa-onnx-$V.aar
[ -f "$OUT" ] && { echo "already present: $OUT"; exit 0; }
mkdir -p app/libs
# static-link build bundles ONNX Runtime, so this single artifact is everything.
curl -L -o "$OUT" \
  "https://github.com/k2-fsa/sherpa-onnx/releases/download/v$V/sherpa-onnx-static-link-onnxruntime-$V.aar"
ls -la "$OUT"
