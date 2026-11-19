#!/bin/sh
# Tests POST and GET /v1/ocr: a real hemera, built from src/, against a fake OCR engine.
# Needs a JDK (21+) and python3. Prints one line per check; exits non-zero if any failed.
set -e
cd "$(dirname "$0")/../.."
work=$(mktemp -d)
trap 'rm -rf "$work"' EXIT
javac --release 21 -d "$work/bin" $(find src -name '*.java') tests/ocr/OcrApiTest.java
java -cp "$work/bin" OcrApiTest "$work" tests/ocr/fake_ocr_engine.py
