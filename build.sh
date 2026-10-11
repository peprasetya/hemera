#!/bin/sh
# Build hemera.jar with nothing but a JDK (21+).
# Compiles into bin/ — the same output directory Eclipse uses.
set -e
cd "$(dirname "$0")"

javac --release 21 -d bin $(find src -name '*.java')
cp -r resources/web resources/comfyui bin/
jar --create --file hemera.jar --main-class ing.aether.hemera.Hemera -C bin .
echo "built hemera.jar ($(du -h hemera.jar | cut -f1))"
