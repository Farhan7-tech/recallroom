#!/usr/bin/env bash
# Installs a new sentinel.jar and restarts the service.
#   deploy/install-sentinel.sh /tmp/sentinel.jar
# The jar is swapped in with a rename, never copied over the file the running JVM has open:
# overwriting it in place breaks class loading in the old process (it fails on shutdown with
# NoClassDefFoundError, or mid-run if it loads a class before the restart).
set -euo pipefail
new="${1:?usage: install-sentinel.sh <path to new sentinel.jar>}"
target="${RECALLROOM_HOME:-$HOME/recallroom}/sentinel/target/sentinel.jar"
mkdir -p "$(dirname "$target")"
cp "$new" "$target.new"
mv -f "$target.new" "$target"
sudo systemctl restart recallroom-sentinel
echo "sentinel.jar installed and the service restarted."
