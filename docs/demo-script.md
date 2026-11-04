# Demo video script (about 2:45)

Setup before recording:
- Run `deploy/reset-demo.sh` on the VM.
- Open Switch Console with `#recall-hq` on screen.
- Keep the phone with the Telegram store group in hand. Its screen is mirrored, or filmed beside the laptop.
- Have printed or on-screen strips for `APRCT-2501` (see `docs/demo-props/`), or a real strip.

| Time | On screen | Say (one even pace) |
|---|---|---|
| 0:00 | CDSCO's public NSQ page; the Sep-2026 table scrolling | "In September, India's drug regulator flagged 330 medicine batches as not of standard quality. Most pharmacies find out weeks later, or never." |
| 0:12 | Console: the RecallRoom group with HQ, Pune, Andheri, and the four agents | "RecallRoom is a recall desk built on Switch. Agents from three vendors work in the chat rooms a pharmacy chain already uses." |
| 0:25 | `#recall-hq`: type `@sentinel scan Sep-2026` | "Sentinel, our own Java agent on Switch's agent protocol, reads the regulator's list and opens a recall." |
| 0:35 | The matcher's table appears; zoom on the "probable" `APRCT-25O1` line | "The matcher, a Claude Code agent, checks every store's stock. It even catches a typo, a letter O for a zero, and asks the store to check the strip." |
| 0:55 | Comms posts supplier notes and the Hindi patient message | "Comms, on Codex, drafts the supplier returns and patient messages, in Hindi too. Drafts only." |
| 1:08 | Sentinel's "waiting for the QA Head" message | "And then nothing happens. Nothing leaves HQ until the QA Head approves." |
| 1:15 | The Telegram store manager types `@sentinel approve …` and gets ⛔ Not accepted | "Someone else tries to approve. Sentinel refuses. It checks the sender's Switch identity in code, not their name, and logs the attempt." |
| 1:30 | The QA Head types `@sentinel approve R-…`; ✅ | "The QA Head approves." |
| 1:35 | Phone: the stop-sale order arrives in the Telegram group | "The stop-sale order lands on the store manager's phone, in Telegram." |
| 1:45 | The manager replies with a photo of the strip; the store runner answers "matches ✅" | "The manager replies with a photo. The store runner reads the batch number off the strip and confirms." |
| 2:00 | HQ: 📸 progress, ✅ store clear, 🏁 recall closed | "Batch by batch, store by store, until the recall closes." |
| 2:10 | The `recall-log` document | "Every step, every approval and every refused attempt is in the recall log: the audit trail an inspector asks for." |
| 2:20 | `switch/recallroom.template.yaml` scrolling; Console's "Use template" | "All of it, the agents, rooms, roles, procedure and links, comes from one Switch template." |
| 2:35 | End card: RecallRoom · built on Switch | "Remove Switch and none of this exists. RecallRoom: recalls, handled." |
