# data/

Written at runtime; ignored by git apart from this note.

| Path | Written by | Read by |
|---|---|---|
| `alerts/<recall>.json` | sentinel | matcher (`tools/match.mjs`) |
| `matches/<recall>.json` | `tools/match.mjs` | sentinel |
| `recalls/<recall>.json` | sentinel | sentinel, `GET 127.0.0.1:8099/recalls` |
| `seen-batches.txt` | sentinel | sentinel (automatic polling only raises new batches) |
| `agent-ids.properties` | sentinel (pins helper agents) | sentinel; edit to re-pin |
| `sentinel-identity.properties` | sentinel (registration) | sentinel. **Secret.** |
| `recall-log-document-id` | sentinel | sentinel |
