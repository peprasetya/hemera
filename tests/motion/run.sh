#!/bin/sh
# Tests /v1/motion/generations: a real hemera, built from src/, against a fake motion engine.
# Needs a JDK (21+) and python3. Prints one line per check; exits non-zero if any failed.
set -e
cd "$(dirname "$0")/../.."
work=$(mktemp -d)
trap 'rm -rf "$work"' EXIT
javac --release 21 -d "$work/bin" $(find src -name '*.java') tests/motion/MotionApiTest.java
java -cp "$work/bin" MotionApiTest "$work" tests/motion/fake_motion_engine.py
