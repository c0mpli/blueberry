#!/bin/bash
# llama.cpp is vendored rather than submoduled — it is only needed to build the native lib.
set -euo pipefail
cd "$(dirname "$0")/.."
[ -d native/llama.cpp ] && { echo "already present: $(git -C native/llama.cpp log --oneline -1)"; exit 0; }
mkdir -p native
git clone --depth 1 https://github.com/ggml-org/llama.cpp native/llama.cpp
