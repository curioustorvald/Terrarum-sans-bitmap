#!/bin/bash
set -e

SCRIPT_DIR="$(cd "$(dirname "$0")" && pwd)"
PROJECT_DIR="$(cd "$SCRIPT_DIR/.." && pwd)"
ASSETS_DIR="$PROJECT_DIR/src/assets"
OUTPUT_DIR="$SCRIPT_DIR"
BITSNPICAS_JAR="$SCRIPT_DIR/bitsnpicas_runtime/BitsNPicas.jar"

# Output paths
KBITX_OUTPUT="$OUTPUT_DIR/TerrarumSansBitmap.kbitx"
TTF_OUTPUT="$OUTPUT_DIR/TerrarumSansBitmap.ttf"

echo "=== Terrarum Sans Bitmap OTF Build Pipeline ==="
echo "Project: $PROJECT_DIR"
echo "Assets:  $ASSETS_DIR"
echo ""

# Step 1: Compile the builder
echo "--- Step 1: Compiling OTFbuild module ---"
COMPILE_CLASSPATH="$BITSNPICAS_JAR"
SRC_DIR="$SCRIPT_DIR/src"
OUT_DIR="$SCRIPT_DIR/out"

mkdir -p "$OUT_DIR"

# Find all Kotlin source files
SRC_FILES=$(find "$SRC_DIR" -name "*.kt" | tr '\n' ' ')

# Try to find Kotlin compiler
if command -v kotlinc &> /dev/null; then
    KOTLINC="kotlinc"
    KOTLIN_STDLIB=""
else
    # Try IntelliJ's bundled Kotlin
    IDEA_CACHE="$HOME/.cache/JetBrains"
    KOTLIN_DIST=$(find "$IDEA_CACHE" -path "*/kotlin-dist-for-ide/*/lib/kotlin-compiler.jar" 2>/dev/null | sort -V | tail -1)
    if [ -n "$KOTLIN_DIST" ]; then
        KOTLIN_LIB="$(dirname "$KOTLIN_DIST")"
        KOTLINC_CP="$KOTLIN_LIB/kotlin-compiler.jar:$KOTLIN_LIB/kotlin-stdlib.jar:$KOTLIN_LIB/trove4j.jar:$KOTLIN_LIB/kotlin-reflect.jar:$KOTLIN_LIB/kotlin-script-runtime.jar:$KOTLIN_LIB/kotlin-daemon.jar:$KOTLIN_LIB/annotations-13.0.jar"
        KOTLIN_STDLIB="$KOTLIN_LIB/kotlin-stdlib.jar:$KOTLIN_LIB/kotlin-stdlib-jdk7.jar:$KOTLIN_LIB/kotlin-stdlib-jdk8.jar"
        echo "Using IntelliJ's Kotlin from: $KOTLIN_LIB"
    else
        echo "ERROR: kotlinc not found. Please install Kotlin compiler or build via IntelliJ IDEA."
        echo ""
        echo "Alternative: Build the OTFbuild module in IntelliJ IDEA, then run:"
        echo "  java -cp \"$OUT_DIR:$COMPILE_CLASSPATH\" net.torvald.otfbuild.MainKt \"$ASSETS_DIR\" \"$KBITX_OUTPUT\""
        exit 1
    fi
fi

if [ -n "$KOTLIN_STDLIB" ]; then
    # Use IntelliJ's bundled Kotlin via java
    java -cp "$KOTLINC_CP" org.jetbrains.kotlin.cli.jvm.K2JVMCompiler \
        -cp "$COMPILE_CLASSPATH:$KOTLIN_STDLIB" -d "$OUT_DIR" $SRC_FILES
else
    kotlinc -cp "$COMPILE_CLASSPATH" -d "$OUT_DIR" $SRC_FILES
    KOTLIN_STDLIB=""
fi

# Step 2: Run the builder to generate KBITX
echo ""
echo "--- Step 2: Generating KBITX ---"
RUNTIME_CP="$OUT_DIR:$COMPILE_CLASSPATH"
if [ -n "$KOTLIN_STDLIB" ]; then
    RUNTIME_CP="$RUNTIME_CP:$KOTLIN_STDLIB"
fi
java -cp "$RUNTIME_CP" net.torvald.otfbuild.MainKt "$ASSETS_DIR" "$KBITX_OUTPUT"

# Step 3: Convert KBITX to TTF via BitsNPicas
echo ""
echo "--- Step 3: Converting KBITX to TTF ---"
java -jar "$BITSNPICAS_JAR" convertbitmap \
    -f ttf -o "$TTF_OUTPUT" \
    "$KBITX_OUTPUT"

echo ""
echo "=== Build complete ==="
echo "  KBITX: $KBITX_OUTPUT"
echo "  TTF:   $TTF_OUTPUT"
