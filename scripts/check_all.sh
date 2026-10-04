#!/usr/bin/env bash
# Run everything that must stay in sync: Python tests, regenerate-and-diff the shared crypto vectors, Android tests.
set -euo pipefail
cd "$(dirname "$0")/.."
JAVA_HOME="${JAVA_HOME:-$HOME/.jdks/jbr-21.0.11}"; export JAVA_HOME

python -m pytest -q
python scripts/make_vectors.py > /tmp/crypto_vectors.new.json
if ! diff -q /tmp/crypto_vectors.new.json android/test-vectors/crypto_vectors.json >/dev/null; then
  echo "vectors changed: updating android/test-vectors and the :core test resource"
  cp /tmp/crypto_vectors.new.json android/test-vectors/crypto_vectors.json
fi
cp android/test-vectors/crypto_vectors.json android/core/src/test/resources/crypto_vectors.json
(cd android && ./gradlew :core:test)
