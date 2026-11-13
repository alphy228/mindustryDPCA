#!/usr/bin/env bash
# Fast Mindustry v160.7 OpenGL UI + World Unit Preview Renderer (~3.3s for all 4 PNGs).
# Usage:
#   ./tools/render_previews.sh [output_dir]
# Optional env vars:
#   MINDUSTRY_JAR=/path/to/Mindustry.jar   (default: /tmp/Mindustry.jar or ./Mindustry.jar)
#   PLUGIN_JAR=/path/to/coolplugin.jar     (default: <repo>/config/mods/coolplugin.jar)
#   JAVA_HOME=/path/to/jdk17               (default: /tmp/jdk17 if present, else PATH java/javac)
set -euo pipefail

SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
if [ -d "${SCRIPT_DIR}/../config/assets" ]; then
    DEFAULT_REPO="$(cd "${SCRIPT_DIR}/.." && pwd)"
else
    DEFAULT_REPO="/tmp/mindustryDPCA"
fi

OUT_DIR="${1:-/home/user}"
REPO_DIR="${REPO_DIR:-$DEFAULT_REPO}"
PLUGIN_JAR="${PLUGIN_JAR:-$REPO_DIR/config/mods/coolplugin.jar}"

if [ -z "${MINDUSTRY_JAR:-}" ]; then
    if [ -f "/tmp/Mindustry.jar" ]; then
        MINDUSTRY_JAR="/tmp/Mindustry.jar"
    elif [ -f "$REPO_DIR/Mindustry.jar" ]; then
        MINDUSTRY_JAR="$REPO_DIR/Mindustry.jar"
    else
        echo "ERROR: Set MINDUSTRY_JAR=/path/to/Mindustry.jar (Mindustry v160.7 desktop jar)." >&2
        exit 1
    fi
fi

if [ -n "${JAVA_HOME:-}" ] && [ -x "${JAVA_HOME}/bin/javac" ]; then
    JAVAC="${JAVA_HOME}/bin/javac"
    JAVA="${JAVA_HOME}/bin/java"
elif [ -x "/tmp/jdk17/bin/javac" ]; then
    JAVAC="/tmp/jdk17/bin/javac"
    JAVA="/tmp/jdk17/bin/java"
else
    JAVAC="javac"
    JAVA="java"
fi

BUILD_DIR="/tmp/fastpreview-classes"
mkdir -p "$BUILD_DIR" "$OUT_DIR"

if [ ! -f "$BUILD_DIR/FastPreviewTool.class" ] \
    || [ "$SCRIPT_DIR/FastPreviewTool.java" -nt "$BUILD_DIR/FastPreviewTool.class" ] \
    || [ "$PLUGIN_JAR" -nt "$BUILD_DIR/FastPreviewTool.class" ]; then
    LC_ALL=C.UTF-8 "$JAVAC" -encoding UTF-8 -cp "$PLUGIN_JAR:$MINDUSTRY_JAR" -d "$BUILD_DIR" "$SCRIPT_DIR/FastPreviewTool.java"
fi

if [ -z "${DISPLAY:-}" ] && command -v xvfb-run >/dev/null 2>&1; then
    LC_ALL=C.UTF-8 xvfb-run -a -s "-screen 0 1400x900x24" \
        "$JAVA" -cp "$BUILD_DIR:$PLUGIN_JAR:$MINDUSTRY_JAR" FastPreviewTool "$REPO_DIR" "$OUT_DIR"
else
    LC_ALL=C.UTF-8 "$JAVA" -cp "$BUILD_DIR:$PLUGIN_JAR:$MINDUSTRY_JAR" FastPreviewTool "$REPO_DIR" "$OUT_DIR"
fi
