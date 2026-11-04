#!/usr/bin/env bash
# Clears recall records so a demo starts from "no recalls yet". Keeps sentinel's Switch identity,
# its recall-log document and the pinned agent ids. Run on the host where sentinel runs.
set -euo pipefail
cd "${RECALLROOM_HOME:-$HOME/recallroom}"
sudo systemctl stop recallroom-sentinel
rm -rf data/recalls data/alerts data/matches data/seen-batches.txt
sudo systemctl start recallroom-sentinel
echo "Recall data cleared; sentinel restarted. The recall-log document empties on its next update."
