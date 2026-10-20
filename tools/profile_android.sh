#!/usr/bin/env bash
# Black-box comparison on a disposable emulator, with the actual optimized APKs.
set -euo pipefail
python3 "$(dirname "$0")/profile_android.py" "$@"
