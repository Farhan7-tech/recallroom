#!/usr/bin/env bash
# Clears recall records so a demo starts from "no recalls yet". Keeps sentinel's Switch identity,
# its recall-log document, the pinned agent ids and the recall-id counter (ids are never reused).
# Run on the host where sentinel runs. To also clear the agents' memory of earlier runs, send
# `!reset-all-agents` in each RecallRoom room afterwards.
set -euo pipefail
cd "${RECALLROOM_HOME:-$HOME/recallroom}"
sudo systemctl stop recallroom-sentinel
rm -rf data/recalls data/alerts data/matches data/seen-batches.txt
sudo systemctl start recallroom-sentinel
echo "Recall data cleared; sentinel restarted. The recall-log document empties on its next update."
