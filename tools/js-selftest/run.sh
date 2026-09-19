#!/usr/bin/env bash
#
# Guards the JavaScript that GeminiAssist injects into the Gemini web app.
#
# The scripts are assembled from Java string concatenations, so javac cannot catch
# a broken script - this runs the real runtime text through `node --check` and then
# through a mock DOM (harness.js) that asserts the behaviour the app depends on.
#
# Needs: python3, node. Run from the repository root:
#   tools/js-selftest/run.sh
#
set -euo pipefail

ROOT_DIR="$(cd "$(dirname "$0")/../.." && pwd)"
MAIN_ACTIVITY="$ROOT_DIR/app/src/main/java/org/geminiassist/app/MainActivity.java"
OUT_DIR="$(mktemp -d)"
trap 'rm -rf "$OUT_DIR"' EXIT

python3 "$ROOT_DIR/tools/js-selftest/extract.py" "$MAIN_ACTIVITY" "$OUT_DIR"

for script in "$OUT_DIR"/*.js; do
    node --check "$script"
    echo "syntax OK: $(basename "$script")"
done

cp "$ROOT_DIR/tools/js-selftest/harness.js" "$ROOT_DIR/tools/js-selftest/run.js" "$OUT_DIR/"
(cd "$OUT_DIR" && node run.js)
