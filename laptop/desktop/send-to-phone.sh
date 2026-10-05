#!/bin/sh
# Nautilus script (right click > Scripts > Send to phone): sends the selected files with Tandem.
exec "$HOME/.local/bin/tandem" send "$@"
