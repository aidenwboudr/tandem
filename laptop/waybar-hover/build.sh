#!/usr/bin/env bash
# Build waybar-hover.so. Needs only a C compiler: GTK symbols resolve inside waybar at load time.
set -euo pipefail
cd "$(dirname "$0")"
cc -O2 -Wall -Wextra -Wno-unused-parameter -shared -fPIC -o waybar-hover.so waybar-hover.c
echo "built $(pwd)/waybar-hover.so"
