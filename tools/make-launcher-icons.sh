#!/usr/bin/env bash
#
# Generates the legacy (API 24-25) PNG launcher icons for GeminiAssist.
#
# Only the mipmap-*/ic_launcher*.png fallbacks are produced here: API 26+ devices
# use the adaptive icon (res/mipmap-anydpi-v26/ic_launcher.xml) built from the
# vector layers in res/drawable. The geometry below mirrors
# tools/icon-source/gemini_assist_icon.svg and the vector drawables.
#
# Requires ImageMagick ("convert") on PATH. Run from the repository root:
#   tools/make-launcher-icons.sh
#
set -euo pipefail

ROOT_DIR="$(cd "$(dirname "$0")/.." && pwd)"
RES_DIR="$ROOT_DIR/app/src/main/res"
WORK_DIR="$(mktemp -d)"
trap 'rm -rf "$WORK_DIR"' EXIT

SQUARE_MASTER="$WORK_DIR/square_512.png"
ROUND_MASTER="$WORK_DIR/round_512.png"

# ---------------------------------------------------------------- square master
convert -size 512x512 xc:none \
    -fill '#14182B' -draw 'roundrectangle 0,0,511,511,112,112' \
    -fill '#1E2547' -draw 'polygon 0,512 512,0 512,512' \
    -fill '#262E58' -draw 'polygon 0,512 512,256 512,512' \
    -fill none -stroke '#FFFFFF' -strokewidth 24 \
    -draw 'stroke-linecap round stroke-linejoin round roundrectangle 121,133,391,334,59,59' \
    -draw 'stroke-linecap round stroke-linejoin round polyline 246,334 190,394 190,334' \
    -stroke none -fill '#8AB4F8' \
    -draw 'polygon 256,171 266,233 327,242 266,251 256,313 246,251 185,242 246,233' \
    "$SQUARE_MASTER"

# ----------------------------------------------------------------- round master
convert -size 512x512 xc:none \
    -fill '#14182B' -draw 'circle 256,256 256,0' \
    -fill '#1E2547' -draw 'polygon 0,512 512,0 512,512' \
    -fill '#262E58' -draw 'polygon 0,512 512,256 512,512' \
    -fill none -stroke '#FFFFFF' -strokewidth 24 \
    -draw 'stroke-linecap round stroke-linejoin round roundrectangle 121,133,391,334,59,59' \
    -draw 'stroke-linecap round stroke-linejoin round polyline 246,334 190,394 190,334' \
    -stroke none -fill '#8AB4F8' \
    -draw 'polygon 256,171 266,233 327,242 266,251 256,313 246,251 185,242 246,233' \
    \( -size 512x512 xc:black -fill white -draw 'circle 256,256 256,0' \) \
    -alpha off -compose CopyOpacity -composite \
    "$ROUND_MASTER"

# ------------------------------------------------------- density specific PNGs
emit_densities() {
    local master="$1"      # source 512px master
    local name="$2"        # ic_launcher or ic_launcher_round

    convert "$master" \
        \( +clone -resize 48x48 -write "$RES_DIR/mipmap-mdpi/$name.png" +delete \) \
        \( +clone -resize 72x72 -write "$RES_DIR/mipmap-hdpi/$name.png" +delete \) \
        \( +clone -resize 96x96 -write "$RES_DIR/mipmap-xhdpi/$name.png" +delete \) \
        \( +clone -resize 144x144 -write "$RES_DIR/mipmap-xxhdpi/$name.png" +delete \) \
        \( +clone -resize 192x192 -write "$RES_DIR/mipmap-xxxhdpi/$name.png" +delete \) \
        null:
}

emit_densities "$SQUARE_MASTER" ic_launcher
emit_densities "$ROUND_MASTER" ic_launcher_round

echo "Launcher icons written to $RES_DIR/mipmap-*"
