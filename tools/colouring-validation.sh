#!/usr/bin/env bash
# Supply a v160.7 desktop jar for sprite extraction and server jar for headless validation.
set -euo pipefail
: "${MINDUSTRY_JAR:?Set MINDUSTRY_JAR to Mindustry v160.7 desktop jar}"
: "${SERVER_JAR:?Set SERVER_JAR to Mindustry v160.7 server-release.jar}"
ROOT="$(cd "$(dirname "$0")/.." && pwd)"
CLASSES="$(mktemp -d)"
trap 'rm -rf "$CLASSES"' EXIT
JAVAC="${JAVA_HOME:+$JAVA_HOME/bin/}javac"
JAVA="${JAVA_HOME:+$JAVA_HOME/bin/}java"
"$JAVAC" -cp "$SERVER_JAR:$ROOT/config/mods/coolplugin.jar" -d "$CLASSES" "$ROOT/tools/VerifyColouringGeneration.java"
"$JAVA" -Xmx1200m -Dcoolplugin.colouring.vanillaJar="$MINDUSTRY_JAR" \
  -Dcoolplugin.colouring.testPaletteLimit="${PALETTE_LIMIT:-2147483647}" \
  -Dcolourtest.output="${OUTPUT_DIR:-$ROOT/.cache/colouring-validation}" \
  -cp "$CLASSES:$SERVER_JAR:$ROOT/config/mods/coolplugin.jar" VerifyColouringGeneration "$ROOT"
if [ "${MEASURE_ATLAS:-0}" = "1" ]; then
  "$JAVAC" -cp "$SERVER_JAR" -d "$CLASSES" "$ROOT/tools/MeasureColouringAtlas.java"
  "$JAVA" -Xmx128m -cp "$CLASSES:$SERVER_JAR:$MINDUSTRY_JAR" MeasureColouringAtlas \
    "${OUTPUT_DIR:-$ROOT/.cache/colouring-validation}/assets/sprites"
fi
