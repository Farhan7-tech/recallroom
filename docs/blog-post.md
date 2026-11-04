---
title: "Building a drug-recall desk on Switch: a Java agent, Claude Code and Codex in Mattermost and Telegram"
subtitle: "What I learned wiring three vendors' agents into one workflow with Switch, including a custom agent on the raw protocol"
tags: switch, ai-agents, java, hackathon, telegram
---

# Building a drug-recall desk on Switch

In September 2026, India's drug regulator, CDSCO, published its monthly list of medicine samples that failed quality testing. It has **330 batches** on it, including paracetamol, ibuprofen and telmisartan. A pharmacy chain is supposed to find those batches in its stores, pull them off the shelf, and tell patients who bought them. In practice the list is a web table that someone may read weeks later.

For the WeMakeDevs × DevTools Academy **Flip the Switch** hackathon, I built **RecallRoom**: a recall desk where AI agents and people handle that job together, in the chat apps the chain already uses. This post walks through how it's built on [Switch](https://github.com/sandbox-quantum/switch), with the code and the setup traps I hit, so your first Switch project goes faster than mine did.

Code: `github.com/Farhan7-tech/recallroom`

## What Switch gives you

Switch puts AI agents into team chat. A Slack, Teams, Discord, Telegram or Mattermost channel becomes a **room**. A room has instructions every agent reads when it joins, roles agents can take, documents, and links to other rooms. Agents from different vendors join the same room and talk to each other the same way people do: by mentioning each other.

The parts I leaned on:

- **Switch Console:** the desktop app that runs (or connects to) a Switch server and starts Claude Code, Codex or OpenCode agents for you.
- **Templates:** one YAML file that creates agents, rooms, roles, documents, links and kickoff messages in a single run.
- **The agent protocol:** HTTP plus a WebSocket, so any program can join as an agent. That's how my Java agent works.

## The workflow

RecallRoom has four agents and two kinds of people:

| Who | Built on | Job |
|---|---|---|
| **sentinel** | My own Java service, on the agent protocol | Reads the CDSCO and openFDA feeds, opens recalls, holds every stop-sale order until the QA Head approves, sends orders to stores, keeps the audit log |
| **matcher** | Claude Code | Checks flagged batches against each store's stock and posts the impact table |
| **comms** | Codex | Drafts supplier return notes and patient messages (English and Hindi). Drafts only. |
| **store-runner** | Claude Code | Takes orders into each store's room and checks shelf photos |
| QA Head | A person, in Mattermost | The only one who can release a recall |
| Store managers | People, in Telegram and Mattermost | Pull the stock and photograph it |

One recall looks like this in the rooms:

1. `@sentinel scan Sep-2026` in `#recall-hq`. Sentinel fetches 331 flagged batches and asks the matcher to check them.
2. The matcher runs a small script against each store's stock and posts: *7 lines, 162 units, two stores*. It also adds judgement I didn't code: "Pune's record says `APRCT-25O1` with a letter O; check the strip before pulling it."
3. Sentinel posts the summary and waits. **Nothing leaves HQ until the QA Head writes `@sentinel approve R-…`.**
4. Sentinel sends a stop-sale order into each store's room. On Telegram, the manager sends a photo of the strip, the store runner reads the batch number off it, and reports each batch back to sentinel.
5. When every batch in every store is confirmed, sentinel closes the recall, and comms marks the patient message ready for the pharmacist.

## One template creates the whole thing

This is the part I'd show a first-time user first. The whole setup (three agents, a room group, roles, the procedure document, room links and the kickoff messages) comes from one file. Trimmed:

```yaml
version: 1
params:
  machine: { type: machine, default: [$first], input: advanced }
  repo_dir: { type: directory, default: /home/ubuntu/recallroom }
  sentinel: { type: agent, default: sentinel }
  bridge: { type: bridge, default: [$first] }

agents:
  - name: matcher
    provider: claude
    machine: "{machine}"
    directory: "{repo_dir}"
    addressing: owner-agents
    instructions: |
      When sentinel opens a recall, run `node tools/match.mjs data/alerts/<recall>.json`,
      post the table it prints, then tell sentinel `matched <recall>`...
  - name: comms
    provider: codex
    # ...
  - name: store-runner
    provider: claude
    addressing: anyone
    # ...

group:
  name: RecallRoom
rooms:
  - name: recall-hq
    bridge: "{bridge}"
    agents: ["{sentinel}", matcher, comms]
    users: ["{$creator}"]
    roles:
      - { name: impact-analyst, exclusive: false, instructions: "..." }
    docs:
      - { name: recall-sop, description: "...", instructions: "...", content: "..." }
    kickoff: |
      @matcher @comms read the room instructions and the recall-sop document,
      take your role, then say in one line what you'll do when sentinel opens a recall.
  - name: store-pune
    agents: ["{sentinel}", store-runner]
links:
  - { from: store-pune, to: recall-hq, label: reports to }
```

When it runs, the kickoff wakes the agents. Within seconds they had each posted `🎭 assumed the impact-analyst role` and a one-line plan. Worth knowing:

- `addressing` decides who can talk to an agent: `owner` (the default), `owner-agents` (you and your agents, so agents can hand off to each other), or `anyone`.
- Telegram rooms can't come from a template, because Telegram bots can't create chats. You create the group, add the bot, and Switch adopts it as a room.

## A custom agent on the raw protocol, in Java

Sentinel isn't run by Console; it's a Spring Boot service that speaks Switch's agent protocol directly. Three things make up the whole protocol client.

**1. Register once.** You need a registration key from the Gateway (**API Keys → Create Registration Key**). The API key in the response is shown only once, so save it.

```java
Map<String, Object> body = Map.of(
    "name", "sentinel",
    "description", "Watches recall feeds and gates stop-sale orders behind the QA Head",
    "connector_type", "external",
    "integration_profile", Map.of(
        "connection_model", "always_on",
        "message_exchange", true,
        "pre_invocation_mediation", List.of(),
        "post_invocation_mediation", List.of(),
        "event_reporting", List.of()));
// POST /agents with "Authorization: Bearer <registration key>"  ->  {"id": ..., "api_key": ...}
```

**2. Keep one WebSocket open, and answer pings fast.** Open `/agents/{id}/connection/ws?connection_id=<uuid>&scope=all` with the agent's API key. `scope=all` covers every room the agent is in. The server sends `{"event":"ping"}` every 2 seconds and drops a connection that's silent for 6. So the pong must never wait behind slow work:

```java
case "ping" -> {
    // java.net.http allows one send at a time, so pongs queue behind each other
    String pong = "{\"type\":\"pong\",\"cursor\":" + cursor.get() + "}";
    sending = sending.thenCompose(previous -> ws.sendText(pong, true));
}
case "message" -> handlers.submit(() -> onMessage.accept(RoomMessage.from(data))); // never on the socket thread
```

Reuse the same `connection_id` when you reconnect, and resume from your last cursor.

**3. Act through HTTP.**
- `POST /agents/{id}/message` with `{room_id, content}` posts into a room, and `@name` in the text addresses that participant.
- Operations such as `create_room_document` or `invite_agent_to_room` are `POST /agents/{id}/ops/{name}`. Room-scoped ones need the `X-Switch-Connection-Id` header and a room bound with `connect_to_room`.
- `GET /agents/{id}/ops` lists them all; my agent got 57.

A message event carries more than the docs list, and two fields made my safety design possible:

```json
"payload": {
  "addressed": true,
  "sender": "@switch-mattermost-2d58b1ae-…-user:localhost",
  "sender_name": "user",
  "on_behalf_of": null,
  "sender_role": "owner",
  "body": "@sentinel approve R-20261104-2"
}
```

One surprise: an agent **can't** change its own settings. Calling `update_agent_detail` with its own key returns 403: "Your owner changes them on your page in Switch Console."

## The approval is code, not a prompt

`sender` is the participant id Switch gives an account. It's tied to the account on the messaging app, not to the display name. I tested this by renaming my Telegram account to "QA Head" between two messages: the sender id didn't change.

So the release check is a string comparison, not an instruction to an LLM:

```java
if (message.onBehalfOf() != null)              return refuse("posted by an agent on someone's behalf");
if (!qaHeads.contains(message.sender()))       return refuse("only the QA Head can approve");
if (!message.roomId().equals(hqRoomId))        return refuse("approvals count only in HQ");
if (recall.state != State.AWAITING_APPROVAL)   return refuse("not waiting for approval");
```

In the live test, a store manager typed `@sentinel approve …` from Telegram. Sentinel refused and wrote the attempt, with the sender id, into the `recall-log` room document. Separately, when I asked the Codex agent to post an approval "for an audit test", it refused on its own and cited the room's procedure. That's nice, but the design doesn't depend on it.

The same idea covers the agents' reports. A `matched` or `confirm` message only counts if it comes from that agent's own `@switch-agent-…` id (pinned the first time it reports correctly), in the right room, at the right step.

## Setup traps (Console 0.40.1 on Windows)

These cost me the most time. Hopefully they save you some.

1. **Console on Windows can't run agents locally.** Every provider showed "Not supported here". Console's code marks all providers unavailable on `win32`, with the reason "SDK sessions require a POSIX SSH execution host". The fix is a Linux machine Console reaches over SSH. I used a 4 GB cloud VM for both the server and the agents (**Run or join a server on a remote host**).
2. **`spawn ssh ENOENT` or "SSH agent socket not found" when adding the host.** Console expands `~` using the `HOME` environment variable, which Windows doesn't set, so `IdentityFile ~/.ssh/key` pointed nowhere. Use an absolute path in `~/.ssh/config`. Also make sure `C:\Windows\System32\OpenSSH` is on Console's PATH.
3. **The built-in Mattermost showed "Messaging apps: 0".** Turning Mattermost off and on (Admin → Status) re-ran its setup and connected it.
4. **The server "failed to start" after a reboot,** with "set up from another account". Console compares a stored database timestamp with Docker's, as text. I'd set the VM's clock to India time, which changed the format Docker reports (`+05:30` versus `Z`). With the VM back on UTC, it matched again.
5. **Telegram:**
   - Turn **Group Privacy** off in BotFather *before* adding the bot to a group.
   - Make sure the server Console has selected is the one you mean before you click Connect. My first connection went to the wrong server.
   - A plain *reply* to an agent's message didn't reach the agent in my test, while a photo caption that mentions it (`@store-runner APRCT-2501`) worked every time I tried.
6. **The managed-agent controller doesn't survive a reboot by itself,** and nor does the stack. I added two small systemd units for them.

## What I'd tell a first-time Switch user

- **Start from a template, not clicks.** It's reproducible, and a judge, a teammate or future-you gets the same rooms and agents in one run.
- **Put rules where the agent will read them at the moment it acts.** Room instructions are good. The message that wakes the agent is better: when I moved "only count photos sent after this order" into the order itself, the store runner stopped re-using old photos.
- **Let agents use judgement, but keep decisions that matter in code.** The matcher noticing a typo and the store runner asking for a recheck made the workflow better. The approval, though, is a string comparison.
- **Log every event your agent receives.** A message lost during a restart is a mystery until you can see what arrived. Sentinel now also reads room history after it reconnects and says if it missed a message meant for it.

RecallRoom ran end to end on real data: the CDSCO September list, a Telegram store on my phone, a Mattermost store, and a recall that closed with 162 units confirmed off the shelves. The code, the template and a demo script are in the repo.

*Built for Flip the Switch by WeMakeDevs and DevTools Academy. #HowISwitch*
