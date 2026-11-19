# DaveBridge protocol

DaveBridge connects a Minecraft 1.7.10 (GT New Horizons) server to an external AI agent ("Dave").

```
 player client ──(plain chat)───────────────┐
 player client ──(davebridge packet channel)─┤   Minecraft server   ──(WebSocket, JSON)──  agent daemon
   (optional DaveBridge client half)         │   (DaveBridge server half)
```

- The **server** is the only proxy. Clients never talk to the agent or the daemon directly.
- The **agent WebSocket** (section 1) carries JSON between the server and the daemon.
- The **packet channel** (section 2) carries JSON packets between the server and clients that have the
  optional client half. Players without it use Dave through plain chat; everything degrades to chat lines.

Protocol versions: WebSocket `protocol: 1`, packet channel `protocol: 1`.

---

## 1. Agent WebSocket

`ws://<bindAddress>:<port>` (default `ws://127.0.0.1:25580`). Text frames only, one JSON object per frame.
Binary frames get an `error`.

### 1.1 Authentication

Every connection must authenticate with the `authToken` from `config/davebridge.cfg`, in one of two ways:

1. **First message:** `{"type":"auth","token":"<token>"}`. Anything else first (including malformed JSON or a
   binary frame), a wrong token, or no auth within `authTimeoutSeconds` gets
   `{"type":"error","code":"unauthorized",...}` and the connection is closed (close code 1008).
2. **Handshake header:** `Authorization: Bearer <token>` or `X-Dave-Token: <token>`. A wrong header token
   fails the handshake. A correct one authenticates immediately.

On success the server sends:

```json
{"type":"hello","ts":1730000000000,"protocol":1,"mod":"davebridge","version":"0.3.0",
 "session":3,"botName":"Dave","accepts":["client_eval","describe","eval","hook_add","hook_list","hook_remove",
 "lesson","lesson_stop","list_players","ping","say","snapshot_get","snapshot_list","thinking","undo"]}
```

`accepts` lists the inbound message types this server handles, sorted. Older servers list fewer: milestone 1 had
`list_players`, `ping`, `say` and `thinking`; milestone 2 added `eval`, `describe` and `client_eval` (section 1.8);
0.2.0 added `lesson` and `lesson_stop`; 0.3.0 added `hook_add`, `hook_list`, `hook_remove` (section 1.9),
`snapshot_list`, `snapshot_get` and `undo` (section 1.10). New types are added here, so the daemon can feature-detect.

Connections beyond `maxClients` are refused at the handshake.

### 1.2 Common fields

- Every server message has `type` and `ts` (epoch milliseconds).
- Any inbound message may carry `reqId` (any JSON value except `null`, which counts as absent). Replies to that
  message (`ack`, `error`, `pong`, `players`, `description`, `eval_result`, `client_eval_result`) echo it back
  unchanged. That includes a `bad_json` error for an object without a string `type`; only a frame that is not a JSON
  object at all gets an error without `reqId`.
- An `ack` is sent only when the request carried a `reqId`. Every other reply, errors included, is sent either way
  (without `reqId` when there was none).
- Unknown fields are ignored in both directions.

### 1.3 Server to agent: events

#### `chat`

A player addressed Dave (or `forwardAllChat` is on).

```json
{"type":"chat","ts":1730000000000,"id":"8d7c...-uuid",
 "player":"Steve","playerUuid":"069a79f4-...","dim":0,"x":12.5,"y":64.0,"z":-3.25,
 "hasClientMod":false,
 "message":"dave, how do I make steel?","triggered":true,"prompt":"how do I make steel?",
 "source":"chat"}
```

| Field | Meaning |
|---|---|
| `id` | Random UUID for this event. Pass it back as `replyTo`. |
| `player`, `playerUuid`, `dim`, `x`, `y`, `z` | Sender and position (rounded to 0.01) when the message was handled. |
| `hasClientMod` | The sender runs the DaveBridge client half. |
| `message` | The chat text as the server received it. |
| `triggered` | The message addresses Dave (see below), or came from the client half's Ask or `/dave ask`. |
| `prompt` | Only when triggered: the question without Dave's name (and the `,`/`:` after a leading prefix). When nothing but the name is left (`thanks dave`, `dave?`), the whole message. |
| `source` | `"chat"` for normal chat. `"client"` for an Ask from the client half (section 2). `"console"` for the `/dave ask` test hook (section 5). |
| `context` | Only for `source:"client"`: what the player was looking at and holding (see `Ask`). **Client-reported and untrusted.** |

Chat is triggered when it addresses Dave, matched case-insensitively against `triggerPrefixes`: the message starts
with a prefix, starts with a greeting and then a prefix (`hey dave, ...`; greetings `hey`, `hi`, `hello`, `hiya`,
`yo`, `oi`, `ok`, `okay`, `thanks`, `thank you`), or ends with a prefix after a space or comma, before optional
`?!.` (`how do I make steel, dave?`, prompt `how do I make steel?`). A prefix ending in a letter must be followed by
a non-word character (an apostrophe counts as a word character), so `@dave hi` matches and `@davey hi` or
`dave's base` do not. Messages cancelled by other mods (mutes, chat filters) are not forwarded. `source:"client"` asks are shown to
every player as ordinary `<Player> text` chat, like typed chat (section 2.3). Reply publicly (`@a`) by default, or
privately (`target` = the player) when asked.

If no agent is connected, a triggered sender gets a private "Dave is not connected right now." notice
(`notifyWhenOffline`).

#### `player_join` / `player_leave`

```json
{"type":"player_join","ts":...,"player":"Steve","playerUuid":"...","dim":0,"x":...,"y":...,"z":...,"hasClientMod":false}
```

`hasClientMod` is always `false` in `player_join`, because the client half says hello a moment after login.
Watch for `client_mod`.

#### `client_mod`

A player's client half said hello. Sent once per login, shortly after `player_join`, and again (same shape) whenever
that player changes their opt-in mid-session (`client_settings`, section 2.3). Treat the latest one as current.

```json
{"type":"client_mod","ts":...,"player":"Steve","playerUuid":"...","dim":0,"x":...,"y":...,"z":...,
 "hasClientMod":true,"modVersion":"0.2.6","protocol":1,
 "capabilities":["render","ask","context","lesson","nei","eval"],"clientEvalAllowed":false}
```

`capabilities` is what the client half reported (section 2.3). `clientEvalAllowed` is the player's opt-in (client
config, `/davebridge optin|optout`) for `client_eval` (section 1.8). A client half whose packet `protocol` differs
from the server's is treated as a plain client and produces no `client_mod`.

#### `lesson_status`

A lesson (section 1.4 `lesson`) ended on the player's client. Relayed from the client half (untrusted, validated:
unknown states are dropped, unknown reasons become `other`, `id` is cut to 64 characters, at most one per 200 ms).

```json
{"type":"lesson_status","ts":...,"id":"ebf-muffler","state":"stopped","reason":"walked_away",
 "player":"Steve","playerUuid":"...","dim":0,"x":...,"y":...,"z":...,"hasClientMod":true}
```

`state` is `finished` (a non-looping lesson played to the end; `reason` `done`) or `stopped`, with `reason` one of
`player` (chat button, stop key or `/davebridge stop`), `walked_away`, `dimension`, `replaced` (a newer lesson),
`agent` (`lesson_stop`), `timeout` (10 minutes of play), `rejected` (the client could not play it) or `other`.

#### `hook_fired`, `hook_disabled`, `undo_pending`

Event hooks (section 1.9) and undo (section 1.10). Sent without `reqId`.

#### `server_stopping`

Sent to authenticated agents just before the server closes the socket (close code 1001).

### 1.4 Agent to server: requests

All game actions run on the server thread through the main-thread task queue (section 3), so a reply comes
on a later server tick, not immediately.

#### `say`

```json
{"type":"say","target":"@a","markup":"Smelt {item:minecraft:iron_ingot}\n{send:dave, more|More}","replyTo":"8d7c...","reqId":7}
```

| Field | Required | Meaning |
|---|---|---|
| `markup` | one of the three | Dave markup (section 1.5): styles, item/quest/NEI links, buttons. **The normal way to talk.** |
| `text` | one of the three | Plain text. Newlines split lines; URLs become clickable; `§` codes pass through. |
| `components` | one of the three | Escape hatch: raw 1.7.10 chat JSON, one component per line (an array) or a single component. |
| `target` | no (default `@a`) | `"@a"` (or `""`) for everyone, or a player name (case-insensitive). A named player gets a **whisper**. |
| `replyTo` | no | The `id` of the `chat` event being answered (logged). |

Exactly one of `markup`, `text` and `components` must be present (`bad_request` otherwise).

Every message is delivered as ordinary chat lines, rendered **per recipient** (section 1.6):
- Public messages start each line with `<Dave> ` (name in `botNameColor`).
- Whispers use vanilla's translated "Dave whispers to you: " prefix (gray italic); the body keeps its own style.
- Blank lines are dropped. Lines wider than the chat box (`chatWidth` pixels, measured with the default font) are
  cut at spaces into a `<Dave>` line plus continuation lines indented by two spaces, without splitting a word or a
  link, so the client never wraps them. Only a word wider than a whole line is cut.
- A message stops after `maxLinesPerMessage` lines with `... (truncated)` (for `markup` and `text` alike, with a
  warning). Markup longer than one character per pixel of output (`maxLinesPerMessage` x line width in pixels) is
  cut before it is parsed, with a warning.
- A line whose JSON would exceed 30000 bytes is degraded: item hovers become text hovers, then hovers are
  removed, then it becomes plain text, each with a warning.
- Each recipient hears `replySound` (only they do). Players with the client half get `thinking{clear:true}`, which
  removes their thinking indicator.

`components` are checked like markup:
- `open_url` must be http/https (and in `urlAllowlist`).
- `run_command` must be an allowlisted command, or chat text starting with a trigger prefix.
- `open_file`, `twitch_user_info` and unknown click actions are removed (vanilla drops them when parsing; DaveBridge
  still reports each one).
- Every removal is a warning; JSON that does not parse is `bad_request`.

Reply (only when `reqId` was given):

```json
{"type":"ack","ok":true,"for":"say","recipients":1,"warnings":["{item:nope:x}: unknown item nope:x:0"],"reqId":7}
```

- `recipients` is how many players got the message (0 for `@a` on an empty server).
- `warnings` lists everything that was degraded: unknown items, fluids, quests or players; blocked links or
  commands; markup that was not understood (kept as text); truncation. Read them to correct the next message.
  Warnings are also logged on the server.
- A named `target` who is not online gets nothing: the ack says `recipients: 0` with the warning
  `player 'X' is not online; the whisper was dropped` (since 0.2.0; earlier versions answered `not_found`). The
  asker may have left, or be a `/dave ask` test name (section 5).
- Errors: `bad_request` for an empty message, for not exactly one of `markup` / `text` / `components`, or for
  `components` that do not parse.

#### `thinking`

```json
{"type":"thinking","target":"Steve","reqId":8}
```

Shows a short gray italic "Dave is thinking..." line. With the client half the line replaces any earlier
thinking line and disappears when the answer arrives (or after 60 s). Reply: `ack` (with `recipients`, 0 when the
player is not online) / `error` as for `say`.

#### `list_players`

```json
{"type":"list_players","reqId":9}
```

Reply:

```json
{"type":"players","ts":...,"reqId":9,"players":[{"player":"Steve","playerUuid":"...","dim":0,"x":...,"y":...,"z":...,"hasClientMod":true}]}
```

#### `ping`

```json
{"type":"ping","reqId":10}
```

Answered immediately on the socket thread (not via the queue):
`{"type":"pong","ts":...,"tick":123456,"queueDepth":0,"reqId":10}`. `tick` is the server tick counter at the
last tick; if it stops advancing between pings, the server thread is stuck.

#### `lesson`

Shows one player a guided demonstration ("show me"): a ghost Dave, visible only to that player, walks a route,
looks and points at blocks, holds items, shows ghost blocks and highlighted blocks with labels, then loops until the
player stops it. Nothing in the world changes. Design: `docs/plans/guided-demos.md` (repository root).

```json
{"type":"lesson","player":"Steve","reqId":12,"lesson":{
  "id":"ebf-muffler","title":"EBF muffler hatch","loop":true,"speed":0.6,"steps":[
    {"walkTo":[102,64,-18]},
    {"lookAt":[104.5,65,-18.5]},
    {"hold":"gregtech:gt.metatool.01:22"},
    {"highlight":{"pos":[104,64,-19],"color":"gold","label":"Controller goes here"}},
    {"ghost":{"pos":[104,65,-19],"block":"gregtech:gt.blockmachines","meta":1000,"facing":"north"}},
    {"swing":true},
    {"say":"Right-click with a screwdriver to set the output side"},
    {"wait":40}]}}
```

| Field | Meaning |
|---|---|
| `player` | One online player (case-insensitive). `@a` or an empty name is refused (`bad_request`). |
| `lesson.id` | Optional, 1-64 of `A-Z a-z 0-9 _ . : -`; made up otherwise. Echoed in the ack and in `lesson_status`. |
| `lesson.title` | Optional, cut to 64 characters; shown in chat and on the HUD. |
| `lesson.loop` | Default `true`: replay after a 2 s pause until the player stops it. `false`: play once, keep the last frame 5 s. |
| `lesson.speed` | Walking speed as a multiple of vanilla walking (4.3 blocks/s). Default 0.6, clamped to 0.1..2. |
| `lesson.steps` | Played in order. Each step is an object with exactly one of the keys below. |

| Step | Argument | Does | Ticks |
|---|---|---|---|
| `walkTo` | `[x,y,z]` (feet position) | Walks there from where he is. The server replaces the walk with the route a mob would walk (a `walkTo` per turn); the client interpolates between them. | distance / speed |
| `lookAt` | `[x,y,z]` | Turns head and body to the point and points at it with his right arm. | 15 |
| `hold` | `"modid:name[:meta]"` (also `@meta`), or `""` / `null` for empty hands | Holds the item. Until the first `hold`, Dave holds GregGuns' Golden Revolver if the player has GregGuns. | 10 |
| `highlight` | `{"pos":[x,y,z],"color":..,"label":..}` or just `[x,y,z]` | Pulsing colored box on the block (outline visible through walls), label above it; Dave looks and points. `color`: a chat color name (`gold` default, `red`, `green`, `aqua`, ... plus `orange`, `pink`, `magenta`, `cyan`, `lime`, `purple`, `grey`) or `#rrggbb` (the `#` is optional); an unknown color is gold, with a warning. `label` up to 80 characters. | 25 |
| `ghost` | `{"pos":[x,y,z],"block":"modid:name","meta":0,"facing":"north"}` | Translucent block, drawn by the block's own inventory renderer (for GT machines `meta` is the meta tile entity id, as on the item; without `meta`, a `:meta` on `block` is used, else 0). `facing` (`north`/`south`/`east`/`west`/`up`/`down`) turns the block's inventory front (south) that way; an unknown facing is ignored with a warning. | 25 |
| `swing` | `true` | Swings his arm. | 10 |
| `say` | text, up to 200 characters | Floating line over Dave's head until the next `say` or the end of the pass. | 40..200 (by length) |
| `wait` | ticks, 1..400 | Pause. | as given |

Highlights and ghost blocks stay up for the rest of the pass and are cleared when it restarts.

Validation (on the server thread):
- Every position must be within **256 blocks** of where the player stands (**32** for clients before 0.2.4, which
  drop farther points), in **y 0..255**, in a **loaded chunk**. The client stops the lesson when the player is 320
  blocks from that spot.
  Highlight and ghost positions are floored to block positions.
- Items and blocks must exist in the server's registries. `ore:` and numeric ids are not accepted.
- At most **64 steps** from the agent; one pass at most **180 s** (3600 ticks). Steps past either limit are dropped
  with a warning.
- Each `walkTo` is replaced by the route a mob would walk, one `walkTo` per turn. When no mob could get there, the
  walk ends at the closest point one can reach, with a warning. The routed lesson may have up to **256 steps**
  (**64** for clients before 0.2.5); beyond that the route turns are thinned evenly, with a warning.
- An invalid or unknown step (or extra keys) is dropped (or ignored) with a warning; the rest still plays. Text loses
  `§` codes and control characters.
- Dave appears with the player, two blocks in front of them (the closest spot a mob can walk to when that is inside a
  wall), so every walk, even the first, starts from the player.
- When the walks would make a pass longer than 180 s, the server raises `speed` (up to 2) so the pass fits, with a
  warning.

Reply:

```json
{"type":"ack","ok":true,"for":"lesson","id":"ebf-muffler","steps":8,"dropped":1,"passTicks":216,
 "warnings":["step 9: unknown step teleport, dropped"],"reqId":12}
```

`steps` is how many steps were sent to the client, route turns included, so it can exceed the agent's step count.
`dropped` counts the agent's steps that were not kept. `passTicks` is the length of one pass.

Errors:
- `no_client_mod`: the player has no DaveBridge client half that plays lessons (no client half, a client half older
  than 0.2.0, or another packet protocol). Answer in chat instead. Lessons do **not** need `clientEvalAllowed`:
  they are data the client draws, never code.
- `not_found`: the player is offline. `bad_request`: `player` missing, empty or `@a`; `lesson` missing or not an
  object; no `steps` array; or no step left after validation (the message lists the warnings).

A new lesson replaces the one playing (that one reports `lesson_status` `replaced`). The player stops a lesson with
the `[Stop]` chat button, the stop key (unbound by default), `/davebridge stop`, by walking more than 320 blocks from
where the lesson was made, by changing dimension, or after 10 minutes of play; `/davebridge pause` (or `[Pause]`)
toggles pause.

#### `lesson_stop`

```json
{"type":"lesson_stop","player":"Steve","id":"ebf-muffler","reqId":13}
```

Stops the lesson `id` on that player's client (or whatever is playing, without `id`). Reply: `ack`. Errors as for
`lesson`. The client answers with `lesson_status` (`stopped`, `agent`) if something was playing.

### 1.5 Dave markup

The full, prompt-ready spec is [docs/dave-markup.md](docs/dave-markup.md). In short:

- **Lines and text:** one chat line per line of markup; blank lines dropped. `# Heading` lines are bold;
  `- item` / `* item` become bullets. `\` escapes the next character.
- **Markdown spans** (inside one line): `**bold**`, `*italic*`, `__underline__`, `~~strike~~`, `` `code` ``
  (gray). A delimiter only counts with a partner later on the same line and no space inside it, so `2 * 3`
  stays literal.
- **Tag spans** (may cross lines):
  - colors: `{gold}...{/gold}`, `{color:red}`; any vanilla color name, plus aliases orange, grey, pink,
    purple, cyan, lime, magenta;
  - styles: `{b}` `{i}` `{u}` `{s}` `{obf}`;
  - closing: `{/}` closes the innermost span, `{/name}` closes that one and anything opened after it, `{/color}`
    closes the innermost color;
  - `{br}` is a line break.
- **Highlights:** `{m:name}` colors a machine name aqua and `{i:name}` an item or fluid name green (a `|label`
  replaces the name). Common-rarity `{item:}` links and `{fluid:}` links use the same green. In text without a
  color of its own, the renderer colors voltage tiers in GregTech's tier colors and numbers with units
  (`480 EU/t`, `45s`, `x16`) in yellow.
- **Tokens:** `{kind:argument|label}`. The label is optional and split off at the **last** unescaped `|`. In a
  hover text, `\n` is a newline; in other token arguments and labels it is a space.

| Token | Argument |
|---|---|
| `item`, `recipe`, `uses` | `modid:name`, `modid:name:meta`, `modid:name@meta`, `name` (minecraft), `ore:<oredict name>` (an unknown ore name is never created), numeric `id[:meta]` |
| `fluid` | fluid registry name |
| `chain` | comma-separated items, each with an optional `*amount` |
| `quest` | quest UUID, BQ's 22/24-character id, or exact quest name (case-insensitive; first match, with a warning if several; an id that names no quest is tried as a name) |
| `url` (alias `link`) | http(s) URL |
| `player` | online player name |
| `waypoint` | `x,y,z` or `x,y,z,dim` |
| `guide` | GuideNH guide id `modid:path` |
| `send` | chat text the player will say; must start with a trigger prefix, at most 100 characters, no `§` |
| `show` | what to show (`{show:the nearest chest with titanium plates}`); a light purple `[Show me in game]` button (or the label) that says `<first trigger prefix>, show me <argument> in game`, asking Dave for a guided demonstration (`lesson`). `§` and control characters are removed and the argument is cut (with a warning) so the text fits 100 characters. Client half only (section 1.6) |
| `suggest` | text put into the player's chat line (cut to 100) |
| `cmd` | `/command ...` in `commandAllowlist`, at most 100 characters |
| `hover` | hover text; markdown spans and one level of tags and tokens are allowed (`{hover:{red}Hot{/}\nmelts tin\|lava}`); the label is what is shown |

Anything not understood (unknown tag or token kind, unmatched `{`, stray `{/}`, unknown `{/name}`) stays literal
text and adds a warning. Unresolved tokens (including one whose lookup fails with an error) show their label (or argument) as plain text, keeping the surrounding style. A
`{send:}` that breaks its rules becomes a `{suggest:}` with a warning.

### 1.6 Per-recipient rendering and native formats

Each token compiles to the pack's own chat format where one exists, so links work for every GTNH player without
the client half. Research and sources: [docs/chat-integration.md](docs/chat-integration.md).

- **Items** (`item`, `recipe`, `uses`):
  - Shown as the item's own chat component (`ItemStack.func_151000_E()`; GTNHLib localizes it on each client),
    with the **show_item** hover.
  - The click is **NEI's native bookmark link**: the server first sends NEI's `CACHE_BOOKMARK_DATA` packet
    (channel `NEI`, id 25) to that recipient only, then the click runs NEI's client command
    `/nei_bookmark <payloadId>`.
  - For players **with the client half**, `recipe` / `uses` instead run `/davebridge nei recipe|uses
    <modid:name:meta>`, which opens the NEI page (NEI has no native command for that).
- **Chains:** NEI bookmark **group** payload (TODO-list view, items with amounts, no recipe ids), clicked via
  `/nei_bookmark`.
- **Quests:**
  - BetterQuesting's share token `betterquesting.msg.sharequest:<24-char id>` as one unstyled run. The BQ client
    rewrites the line into `[Quest: #id - name]` and the click `/bq_client show <id>`.
  - Because BQ replaces the whole line, a quest link never shares a line with another link.
  - The quest is validated against BQ's server-side database first.
- **Fluids:** GTNHLib's client-localized fluid name with a hover (id, temperature); no click.
- **Waypoints:** sharewhereiam's share format, clicked via `/savewaypoint <base64>` (opens JourneyMap's editor).
- **Guides:** `/guidenhc open <id>` (GuideNH).
- **Show buttons** (`show`): a `run_command` click that says the "show me ... in game" text, hover "Dave walks you
  there in game (only you see him)". Only lines sent to players who get client-command links (client half with the
  `nei` capability) carry it; everyone else gets nothing in its place, and a line left empty by that is dropped.
- **Everything else** is vanilla chat: `open_url`, `run_command`, `suggest_command`, `show_text`.

If NEI or BQ is not on the server, item links keep their hover (and the client-half click), quests become plain
text, and chains become a hover list, all with warnings.

### 1.7 Errors

```json
{"type":"error","ts":...,"code":"bad_json","message":"malformed JSON: ...","reqId":7}
```

| Code | When |
|---|---|
| `bad_json` | The frame is not a JSON object, or has no string `type`. |
| `bad_request` | A field is missing or has the wrong type; binary frame; second `auth`. |
| `unknown_type` | No handler for `type`. |
| `unauthorized` | Not authenticated (the connection is then closed). |
| `not_found` | Target player is not online (`lesson`, `lesson_stop`, `describe`, `client_eval`; `say` and `thinking` answer `recipients: 0` instead). `describe` also: dimension not loaded, no such entity. `hook_remove`: no such hook. `undo`, `snapshot_get`: no such snapshot (or its file is gone). |
| `no_client_mod` | `lesson` / `lesson_stop`: the player has no client half that plays lessons. |
| `queue_full` | The main-thread queue is at `maxQueueDepth`. Retry later. |
| `cancelled` | The server is stopping: queued actions that had not run yet, `write` evals and undos still waiting for a confirm, and any message received while it stops. Sent before `server_stopping` and the close. |
| `internal` | A handler threw. Logged on the server; the server itself is unaffected. |
| `disabled` | The feature is turned off in the server config: `eval` (`eval.evalEnabled`), `client_eval` (`eval.clientEvalRelay`), `hook_add` (`hooks.hooksEnabled`), `undo` and the snapshot requests (`undo.undoEnabled`, or no world loaded). |
| `limit` | `hook_add`: the server (`hookMaxCount`) or the owner (`hookMaxPerOwner`) already has the most hooks allowed. |
| `not_opted_in` | `client_eval` for a player whose client half has not opted in (or has no client half, or speaks another packet protocol). |
| `unsupported` | `client_eval` for a client half too old to run scripts (no `eval` capability). |

Errors never close an authenticated connection.

### 1.8 Live eval (milestone 2)

Groovy scripts from the agent, run inside the server JVM (or, for opted-in players, their own client). The trust
model is the README's: trusted players only, so arbitrary code is accepted on purpose. The safeguards below prevent
accidents (a frozen tick, a crashed server, a world change nobody saw), not attacks. Servers that support this list
`eval`, `describe` and `client_eval` in `hello.accepts`.

#### `eval`

```json
{"type":"eval","id":"e1","script":"world.getBlock(0,64,0).getLocalizedName()","mode":"read",
 "player":"Steve","params":{"item":"gregtech:gt.metaitem.01:32600"},"timeoutMs":1000,"reqId":11}
```

| Field | Required | Meaning |
|---|---|---|
| `script` | yes | Groovy source, at most 65536 characters. Its last expression is the result. |
| `id` | no | Echoed in the result and in `eval_pending`. |
| `mode` | no | `"read"` (default) runs at once. `"write"` waits for the asking player's Confirm click (below). Case-insensitive; anything else is `bad_request`. |
| `player` | for `write` | The asking player (the daemon fills it in from the turn). Bound as `player` when online; a named player who is not online adds a warning. |
| `summary` | for `write` | One line shown to the player: what will change. Cut to 300 characters. |
| `params` | no | JSON value, bound as `params` (numbers arrive as doubles: use `params.n as int`); missing means an empty map. |
| `timeoutMs` | no | Time limit, default `evalTimeoutMillis` (500), clamped to 10..`evalMaxTimeoutMillis` (5000). |
| `confirmTimeoutSeconds` | no | `write` only: how long to wait for the click, default `confirmTimeoutSeconds` (120), clamped to 5..`confirmMaxTimeoutSeconds` (600). |
| `snapshot` | no | `write` only (0.3.0): a region `{"dim":0,"from":[x,y,z],"to":[x,y,z]}` or an array of up to 16, saved to the undo journal right before the script runs (`dim` defaults to the asker's). At most `undoMaxRegionBlocks` blocks in all (`bad_request` otherwise). Use it when the script changes things block capture cannot see (section 1.10). |

**What a script sees.** `server` (MinecraftServer), `player` (the asker's EntityPlayerMP, or null), `asker` (name),
`world` (the asker's world, else the overworld), `params`, `log`, `out` (`println` goes to the result's `output`), and
`dave`, a helper object:

- reflection that speaks MCP names: `dave.fields(obj)`, `dave.methods(obj, "filter")`, `dave.get(obj, "name")`,
  `dave.call(obj, "name", args...)`, `dave.cls("full.ClassName")`, `dave.mcp("field_71071_by")`, `dave.srg("inventory")`;
- lookups (loaded state only; nothing loads a chunk): `dave.players()`, `dave.player(name)`, `dave.world(dim)`,
  `dave.worlds()`, `dave.te(dim,x,y,z)`, `dave.tiles(world)` / `dave.tiles()` (loaded tile entities),
  `dave.describe(dim,x,y,z)`, `dave.describe(stack)`, `dave.looking(player)`;
- formatting: `dave.item(stack)` (`id, meta, count, name, ore, nbt`), `dave.fluid(fluidStack)`, `dave.markupId(stack)`
  (for `{item:...}`), `dave.waypoint(x,y,z,dim,label)` (a `{waypoint:...}` markup link), `dave.dump(obj)`.

`undo` (since 0.3.0) adds positions to the undo journal of a confirmed write (section 1.10): `undo.save(world,x,y,z)`,
`undo.region(world,x1,y1,z1,x2,y2,z2)` (also with a dimension id instead of `world`) and `undo.active`. Outside a
confirmed write (read scripts, hooks, undo turned off) it is inactive and its calls do nothing. In a confirmed write,
`dave.te(dim,x,y,z)` also saves the position first.

Common packages are imported (`net.minecraft.{block,entity,entity.player,init,inventory,item,nbt,server,tileentity,
util,world}`, `net.minecraftforge.{common.util,fluids,oredict}`); other classes need their full name or an import.

**MCP names work at runtime.** Outside the dev environment vanilla members only exist under SRG names
(`field_71071_by`). When a property or method of a Minecraft-hierarchy class is missing, it is retried under each
SRG name of that MCP name (table generated at build time from Forge's own mapping, the names mod source uses,
plus MCP stable_12, the names in the decompiled vanilla source), so `player.inventory`, `entity.getHealth()`,
`entity.health`, `stack.getItemDamage()` / `stack.getMetadata()` and `Blocks.stone` all work. Mod classes keep their
real names. On a dedicated server some mod classes declare client-only signatures without `@SideOnly`, so Java cannot
list their members; Groovy then dispatches through their nearest listable superclass (overrides still run), and
methods only that class declares are out of reach.

`dave.items(spec)` (a fast stack matcher) and `dave.gtRecipes(item, fluid, uses, map, limit)` (every GregTech
recipe map scanned in Java, tens to a few hundred milliseconds) keep heavy lookups off Groovy's dynamic dispatch.

**Safeguards.**
- Compiled off the server thread, then run on it through the main-thread queue (section 3).
- Time limit: a check compiled into every loop, method and closure stops the script with `errorType:"timeout"`.
  Java code it calls is not interrupted, so a long call inside another mod finishes first.
- Refused at compile time (`errorType:"blocked"`): `System.exit`, `Runtime.halt`, `initiateShutdown`, `stopServer`,
  `sleep`, `wait`, thread `stop`, and `executeCommand` with a stop or restart command.
- `mode:"read"` scripts that call obvious world changers (`setBlock`, `setInventorySlotContents`, `setDead`,
  `spawnEntityInWorld`, `executeCommand`, `stackSize =`, ...) are refused with `errorType:"needs_confirm"`: resend
  as `write` with a summary. A read script that changes the world some other way runs anyway (trusted players).
- Results are converted to JSON with caps (depth 8, 500 items per collection, 8000 characters per string,
  `evalMaxResultChars` overall); anything cut sets `truncated:true`. Items, fluids, tile entities, entities and
  worlds have compact shapes; other objects become `{"$class":..., "$str": toString()}`.
- Exceptions are caught and returned; they never reach the server.

Reply:

```json
{"type":"eval_result","ts":...,"id":"e1","mode":"read","side":"server","ms":0.42,"ok":true,"result":"Stone",
 "reqId":11}
{"type":"eval_result","ts":...,"id":"e1","mode":"read","side":"server","ms":1.3,"ok":false,"errorType":"exception",
 "error":"java.lang.NullPointerException","line":3,"stack":["DaveScript7.run(DaveScript7.groovy:3)", "..."],"reqId":11}
```

`errorType` is one of `compile`, `blocked`, `needs_confirm`, `timeout`, `exception`, and for `write`: `cancelled`,
`expired`, `too_many_pending`. `ms`, `line` and `stack` are present only once the script ran (not for `compile`,
`blocked`, `needs_confirm` or the `write` outcomes without a run); `error` is cut to 2000 characters, `stack` to 12
frames. `output` is present only when the script printed something. Optional fields: `truncated`,
`outputTruncated`, `warnings`, `confirmed`, `confirmedBy`, and for a confirmed write that changed something (0.3.0)
`snapshot` (its id, e.g. `u12`), `snapshotBlocks`, `snapshotEntities` and `snapshotPartial`; journal notes (things an
undo will not cover) come as `warnings` starting with `undo:`. Request errors (`bad_request`, `disabled`, `queue_full`,
`cancelled`, `internal`) come as `error` messages.

**World changes need the asking player's confirm (`mode:"write"`).** An accident guard against the agent's mistakes:
1. The script is compiled. The asking player sees `[Dave] wants to change the world: <summary>` (hover: the script)
   and `[Confirm] [Cancel] (expires in 2 min)`. The buttons run `/dave confirm <id>` and `/dave cancel <id>`.
2. The agent at once gets an event without `reqId`:
   `{"type":"eval_pending","id":"e1","player":"Steve","playerOnline":true,"confirmId":"3fa9c0d1e2","summary":"...","expiresInSeconds":120}`.
3. The request's reply is the `eval_result` after the click (`confirmed:true`, with the run's outcome), or
   `ok:false` with `cancelled` or `expired`. So the agent's request waits up to `confirmTimeoutSeconds`.
- Only the asking player's click counts; another player's click is refused. Each confirm runs at most once. A
  player may have 5 waiting. The server console (or RCON) may answer any confirm, which is how an asker who is not
  online (a `/dave ask` test name) is confirmed.
- If `backupCommand` is set, it runs right before the confirmed script.
- Since 0.3.0 the confirmed script runs inside a change journal and what it changed is saved as an undo snapshot
  (section 1.10). The asker's "Done." line then carries an `[Undo]` button (`/dave undo <snapshot>`).

#### `describe`

```json
{"type":"describe","target":"looking","player":"Steve","reqId":12}
{"type":"describe","target":"block","dim":0,"x":10,"y":64,"z":-4,"reqId":13}
```

`target` (default `looking`): `looking` (the block the player looks at, ray-traced on the server, `reach` default
64; nothing in reach answers `{"kind":"none","note":...}`), `held` (an empty hand answers `"empty hand"`), `player`,
`block` (`x`,`y`,`z`, `dim` default the player's or 0), `entity` (`entityId`, `dim` default the player's or 0).
`looking`, `held` and `player` need `player`. Reply:

```json
{"type":"description","target":"block","description":{"kind":"block","dim":0,"x":10,"y":64,"z":-4,
 "id":"gregtech:gt.blockmachines","meta":0,"blockClass":"...","name":"Electric Blast Furnace",
 "tile":{"class":"gregtech.api.metatileentity.BaseMetaTileEntity","gregtech":{"metaTileId":1000,"owner":"Steve",
 "mteClass":"...MTEElectricBlastFurnace","metaName":"multimachine.blastfurnace","localName":"Electric Blast Furnace",
 "description":["..."],"recipeMap":"gt.recipe.blastfurnace"},"inventory":{"size":2,"slots":[]}}},"reqId":13}
```

GregTech machines add `gregtech` (MetaTileEntity id, class, names, tooltip description lines, recipe map, owner);
a held GT machine item names its machine. Item tooltips are client-side in 1.7.10 and do not exist on a dedicated
server: use `client_eval` for a player's real tooltip. An unloaded position answers `"loaded":false` (never loads
the chunk). Errors: `not_found` (player offline, dimension not loaded, no such entity), `bad_request` (unknown
`target`, missing `player` or coordinates). `describe` is not gated by `evalEnabled`.

#### `client_eval`

```json
{"type":"client_eval","player":"Steve","id":"c1","script":"mc.thePlayer.getHeldItem()?.getTooltip(mc.thePlayer, false)",
 "params":{},"timeoutMs":500,"reqId":14}
```

Runs a script on one player's own client. **Only for a player who opted in** (`clientEvalAllowed`, client config or
`/davebridge optin`), because the code runs on their machine. The server checks the player's current opt-in at send
time (the latest `client_hello` / `client_settings`) and refuses otherwise (`not_opted_in`); a client half without
the `eval` capability gets `unsupported`. The client re-checks its own setting before running it. It runs on the
client thread with the same compile-time checks and time-limit mechanism, with `mc` (Minecraft), `player`
(`mc.thePlayer`), `world` (`mc.theWorld`), `params`, `out`, `log` and `dave` (the common helpers plus
`dave.tooltip(stack)`). No confirm step: it changes nothing on the server.

- `script` (required, at most 65536 characters), `player` (required), `id`, `params` (a JSON object; anything else
  is ignored), `timeoutMs` (default 500, not `evalTimeoutMillis`; the server clamps it to 10..60000 and the client to
  at most 2000).
- The client's caps are smaller than the server's: depth 8, 300 items per collection, 4000 characters per string,
  a 64 KiB result and 8 KiB of printed output.
- Not gated by `evalEnabled`; `eval.clientEvalRelay` turns it off (`disabled`).

Reply (the client's result relayed):

```json
{"type":"client_eval_result","id":"c1","player":"Steve","side":"client","ok":true,"result":["Iron Pickaxe","..."],
 "ms":0.8,"reqId":14}
```

The relayed fields are `ok`, `ms`, and when the client sent them `result`, `error` (cut to 4000 characters),
`errorType` (cut to 40), `output` (cut to `evalMaxOutputChars`) and `truncated`. `line`, `stack` and
`outputTruncated` are not relayed. The client's `errorType` is `compile`, `blocked`, `timeout`, `exception`, or
`not_opted_in` (it refused: the player opted out before it ran). If the client does not answer within
`clientEvalTimeoutSeconds` the result is `ok:false` with `errorType:"timeout"`; if the player logs out first, or the
server stops, `errorType:"disconnected"`.


### 1.9 Event hooks (0.3.0)

The agent subscribes to game events, or to a condition polled on the server thread, and gets a `hook_fired` event
when one fires; the Dave daemon turns it into a turn for Dave (`dave/ENVELOPE.md`, `kind="event"`). Hooks are kept
in the world folder (`<world>/davebridge/hooks.json`), so they survive restarts and agent reconnects.

#### `hook_add`

```json
{"type":"hook_add","reqId":20,"hook":{"event":"poll","owner":"Steve","name":"EBF done",
 "note":"tell Steve his EBF at 100,64,-20 finished its batch",
 "script":"def te = dave.te(0,100,64,-20); te != null && !te.getMetaTileEntity().isActive()",
 "everyTicks":100,"once":true}}
```

| Field | Default | Meaning |
|---|---|---|
| `event` | required | `player_join`, `player_leave`, `player_death`, `player_dimension`, `entity_death`, `block_break` (a player broke a block), `block_place` (a player placed one), `chat` (any chat line, triggered or not), `quest_complete` (BetterQuesting; refused without BQ), or `poll` |
| `owner` | required | The player the hook is for. Resolved to the online player's name; `ownerUuid` as for `/dave ask` (section 5) |
| `name` | the event | Label, up to 64 characters |
| `note` | none | Up to 500 characters, handed back in every `hook_fired`: what the agent wants to do then |
| `notify` | `owner` | Informational for the agent: `owner`, `@a` or a player name |
| `script` | none | Groovy. `poll`: the condition (required). Other events: an extra filter that must be truthy |
| `params` | `{}` | Bound as `params` in the script |
| `filter` | `{}` | All set keys must match: `player` (the acting player, case-insensitive; for `entity_death` the killer), `entity` (substring of the entity's registry name, class or custom name; also the killer's), `block` (`modid:name` or `modid:name:meta`; a bare name is `minecraft:`), `dim`, `box` (`[x1,y1,z1,x2,y2,z2]`, inclusive), `contains` (substring of the chat or death message), `quest` (a quest id or name; a name that resolves is stored as its UUID, otherwise it matches quest names containing it). Unknown keys are `bad_request` |
| `everyTicks` | `100` | `poll`: run interval, `hookMinPollTicks`..72000 |
| `once` | `true` | Remove the hook after it fires |
| `cooldownSeconds` | `hookDefaultCooldownSeconds` (10) | Minimum time between firings of this hook |
| `edge` | `true` | `poll`: fire when the condition turns from false to true; `false`: on every true run (with the cooldown) |
| `fireIfTrueAtStart` | `false` | `poll`: fire right away if the condition is already true on its first run |
| `expiresInSeconds` | `hookDefaultTtlHours` (7 days) | Removed after this; `0` = never, unless `hookMaxTtlHours` caps it |

**Scripts.** Compiled once (on add and after a restart), with eval's compile checks (section 1.8). A script that
calls a world changer is refused (`bad_request`): hooks run unattended, so they only watch. Each run has eval's
bindings (`player` is the owner when online, `asker` the owner's name) plus `hook` (`id`, `name`, `owner`,
`fireCount`, `lastFiredAt`), `state` (a map kept between runs of this hook, in memory only), and for event hooks
`event` (the Forge event) and `facts` (below). The time limit is `hookPollTimeoutMillis` (50 ms); exceptions and
timeouts are caught. After `hookMaxErrors` (5) failures in a row the hook is disabled (`hook_disabled`). A poll
condition runs once when added: if it fails then, the hook is not added (`bad_request` with the error). Poll hooks
run in the server tick within `hookTickBudgetMillis` (5 ms) per tick, most overdue first (at least one per tick); the
rest wait for the next tick. A condition's value is its Groovy truth; a non-boolean truthy value (a map, a list, a
number) is sent as `detail.result`. Polls never load chunks unless the script does: `dave.te` returns null for an
unloaded position.

Reply: `{"type":"ack","ok":true,"for":"hook_add","hook":{...the stored hook, with "id":"h3"...},"firstRun":{"truthy":false,"value":false},"warnings":[...]}`
(`firstRun` for poll hooks only). Errors: `bad_request` (validation, compile, a world-changing script, a failing first
run), `limit`, `disabled`.

#### `hook_list` / `hook_remove`

```json
{"type":"hook_list","owner":"Steve","reqId":21}
{"type":"hook_remove","id":"h3","reqId":22}
{"type":"hook_remove","owner":"Steve","reqId":23}
```

`hook_list` (all hooks without `owner`) answers `{"type":"hooks","hooks":[...],"enabled":true}`; each hook has the
fields above plus its state: `id`, `ownerUuid`, `createdAt`, `expiresAt` (0 = never), `fireCount`, `lastFiredAt`,
`lastValue` (poll), `errors` (failures in a row), `lastError`, `disabled`, `suppressed`. `hook_remove` answers
`ack` with `removed` (the ids); an unknown `id` is `not_found`.

#### `hook_fired`

```json
{"type":"hook_fired","ts":...,"hookId":"h3","name":"EBF done","event":"poll","owner":"Steve",
 "ownerUuid":"...","ownerOnline":true,"notify":"owner","note":"tell Steve ...","once":true,"fireCount":1,
 "suppressed":0,"detail":{"result":{"progress":0}},"removed":true}
```

`detail` holds the event's facts (capped JSON): `player`, `dim`, `x`, `y`, `z` for player events; `block`, `meta`
(block events); `message`, `triggered` (chat); `entity`, `entityClass`, `entityName`, `killer` or `killerEntity`,
`damage`, `message` (the death message) for deaths; `fromDim`, `toDim` (`player_dimension`); `questIds`,
`questNames` and `player` (`quest_complete`); plus `result` (a non-boolean script value), `output` (what the script
printed) and `test` (sent by `/dave hooks fire`). `suppressed` counts firings held back by the cooldown or the rate
limit since the last one sent. `removed` is true when a `once` hook was removed by this firing.

**Rate limits.** A hook fires at most once per `cooldownSeconds`; all hooks together at most `hookMaxFiresPerMinute`
(30) times a minute. A firing held back is counted in `suppressed`; a `poll` hook held back fires again on its next
true run.

**No agent connected.** A notification that reaches no agent is kept (`"queued":true` added) in an outbox of up to
`hookOutboxSize` (50, oldest dropped), saved with the hooks, and sent to the next agent that authenticates, right
after its `hello`.

Event handlers run at the lowest priority and ignore events other mods cancelled. Only enabled hooks of an event type
cost anything: without one, the handler returns at once.

#### `hook_disabled`

```json
{"type":"hook_disabled","ts":...,"hookId":"h4","name":"...","event":"poll","owner":"Steve","ownerUuid":"...",
 "ownerOnline":true,"notify":"owner","once":true,"reason":"errors","error":"its script failed 5 times in a row; last: ..."}
```

Sent when a hook's script keeps failing, or no longer compiles (or changes the world) after a restart. The hook stays
listed with `disabled:true` until removed. Expired hooks are removed silently (logged).

### 1.10 Snapshots and undo (0.3.0)

Every confirmed `write` eval (section 1.8) runs inside a change journal, and what it changed is saved as a
**snapshot** (`u1`, `u2`, ...) in the world folder (`<world>/davebridge/undo/`: `index.json` and one gzipped NBT file
per snapshot). An undo puts it back.

**What is recorded.**
- Every block the script sets through `World.setBlock` (and so `setBlockToAir`, `breakBlock`, and mod code that calls
  them): block, metadata and tile entity NBT before the change. This uses Forge's block capture
  (`World.captureBlockSnapshots`), the mechanism behind block-place events. While capturing, the block updates of
  those changes (neighbor notifications, client updates) are deferred and replayed when the script ends, in order,
  as Forge does for a player placing a block; blocks that change because of them (a torch on a removed wall) are
  recorded too, for up to 8 rounds.
- Positions the script saves first: `undo.save`, `undo.region`, `dave.te(...)`, and the request's `snapshot` regions.
  Use them for changes capture cannot see: metadata (`setBlockMetadataWithNotify`), inventories, machine settings,
  any tile entity edited in place.
- Entities that join a world during the script (item drops, spawned mobs; not the entities of chunks it loads).

When the script ends, positions that are back to how they started are dropped; the rest keep the state the change
left (block and metadata), to spot later edits. A change bigger than `undoMaxBlocksPerSnapshot` runs anyway; its
snapshot is `partial`. Snapshots are pruned oldest first beyond `undoMaxSnapshots`, `undoMaxStorageMB` (the newest
is always kept) and `undoMaxAgeDays`.

**What undo does not cover.** Entities the script killed, moved or changed (only spawned ones are removed); items
that left the world (picked up, used, despawned); player inventories and other player data (items given to a
player are not taken back); anything changed in a later tick (falling sand, fluid
flow, machines that ran since); changes made directly to chunks or by other threads; dimensions loaded during the
script; internal state that is not in a block's metadata or its tile entity NBT (multiblock structure caches, AE2
grid state, GT pipe networks), which rebuilds as those mods normally do after a block change. A restored machine is
reloaded from its saved NBT exactly as on chunk load, so it may need a moment (or a screwdriver) to re-form. The
optional `backupCommand` remains the full fallback.

#### `snapshot_list`

```json
{"type":"snapshot_list","player":"Steve","limit":20,"reqId":30}
```

Answers `{"type":"snapshots","snapshots":[...],"total":13}`, newest first (`limit` 1..200, default 20). Each:
`id`, `kind` (`eval`, or `undo`: the record of an undo, which undoing re-applies the change), `player`, `summary`,
`script` (first 2000 characters), `evalId`, `createdAt`, `blocks`, `entities`, `bytes` (0 until written), `dims`,
`box` (`[minX,minY,minZ,maxX,maxY,maxZ]`), `partial`, `warnings`, and once undone `undoneAt`, `undoneBy`, `redo` (the
id of the undo's own snapshot); for `kind:"undo"` also `undoOf`.

#### `snapshot_get`

```json
{"type":"snapshot_get","snapshot":"u12","reqId":31}
{"type":"snapshot_get","snapshot":"last","of":"Steve","reqId":32}
```

Answers `{"type":"snapshot","snapshot":{...the fields above..., "changes":[{"dim":0,"x":..,"y":..,"z":..,
"before":"minecraft:chest","beforeTile":"Chest","after":"minecraft:air"}, ...], "changedSince":0, "unloaded":0}}`:
the first 30 changes, how many positions in loaded chunks no longer hold what the change left (an undo skips those
unless forced), and how many are in chunks that are not loaded (not checked). `last` is the newest snapshot not yet
undone of `of` (anyone without it).

#### `undo`

```json
{"type":"undo","snapshot":"u12","player":"Steve","force":false,"reqId":33}
```

| Field | Required | Meaning |
|---|---|---|
| `player` | yes | Who confirms it (the daemon fills in the asker) |
| `snapshot` | no | An id, or `last` (default): the newest snapshot not yet undone of `of` |
| `of` | no | For `last`: whose (default `player`; `*` = anyone's) |
| `force` | no | Restore positions changed since too; also undo a snapshot that was already undone |
| `confirmTimeoutSeconds` | no | As for `write` |

An undo changes the world, so it goes through the same confirm as a write: the player sees `[Dave] wants to undo:
<summary> (u12, 5 blocks)` with `[Confirm] [Cancel]`, and the agent gets
`{"type":"undo_pending","snapshot":"u12","player":"Steve","playerOnline":true,"confirmId":"...","summary":"...","expiresInSeconds":120}`.
On confirm the snapshot is restored on the server thread, `undoTickBudgetMillis` (10 ms) per tick (at least one
position), newest change first: entities the change spawned are removed if still loaded, then each position gets its
block, metadata and tile entity back (the tile entity is recreated from its NBT, as on chunk load). Item drops while
restoring are suppressed (Forge's `restoringBlockSnapshots`), so restoring over a chest does not spill a copy of its
contents. Chunks and dimensions that are not loaded are loaded, as the change itself did. A position whose block or
metadata is no longer what the change left is skipped as a conflict unless `force`. The state each position had just
before the undo is saved as a new snapshot (`kind:"undo"`), so the undo can itself be undone (a redo).

Reply (after the click):

```json
{"type":"undo_result","ts":...,"snapshot":"u12","ok":true,"restored":5,"conflicts":0,"failed":0,
 "removedEntities":2,"missingEntities":0,"ticks":1,"ms":3,"redo":"u13","confirmed":true,"confirmedBy":"Steve",
 "warnings":["..."],"reqId":33}
```

`ok` is false when a position failed (a block that no longer exists in the registry, a tile entity that could not be
recreated). Cancelled or expired: `{"type":"undo_result","snapshot":"u12","ok":false,"errorType":"cancelled"|"expired"|"too_many_pending","error":"..."}`.
Errors: `bad_request` (no `player`; already undone without `force`), `not_found`, `disabled`.

---

## 2. Packet channel `davebridge` (server <-> client half)

An FML `SimpleNetworkWrapper` channel named `davebridge`. Only clients with the DaveBridge client half use it.

### 2.1 Framing and chunking

1.7.10 rejects client-to-server custom payloads of 32767 bytes or more, so every packet is serialized to
UTF-8 JSON (vanilla Gson) and sent as one or more **frames** of at most 30000 data bytes:

| Discriminator | Direction | Frame |
|---|---|---|
| 0 | client to server | `Frame.ToServer` |
| 1 | server to client | `Frame.ToClient` |

Frame layout: `byte version (1)`, `utf8 kind`, `varint streamId`, `varint index`, `varint total`,
`varint length`, `length bytes`. The receiver reassembles a stream when all `total` chunks have arrived (in any
order), then decodes the JSON as the packet class registered for `kind`.

Limits on the receiving side (clients are untrusted): a reassembled client packet is at most 256 KiB (so at
most 9 chunks may be announced), a client may have 4 streams open at once and an unfinished stream expires
after 30 s. On the client, server packets are capped at 8 MiB, with 8 open streams and a 60 s expiry. Either side
drops a frame whose version is not 1, whose `kind` is longer than 64 characters or whose `length` is over 30000.
Malformed frames, unknown kinds and kinds sent in the wrong direction are logged and dropped, never kick or crash
anyone.

### 2.2 Handshake

1. After joining (once the player entity exists), the client half sends **`client_hello`**.
2. On its main thread the server replies with **`server_hello`**. If the hello's `protocol` equals the server's, it
   then records the player as having the client mod and tells the agent (`client_mod` event). A client with a
   different `protocol` is not recorded: the server treats it as a plain client.
3. Until `server_hello` arrives the client assumes the server does **not** run DaveBridge and falls back to
   plain chat. It also stays on plain chat (and says so in one chat line) when `server_hello.protocol` differs from
   its own. So both sides agree: either both use the packet channel or neither does. If the server cannot answer (its
   task queue is full), it records nothing and both sides stay on plain chat.

The client says hello again on every new connection. The server forgets a player on logout.

### 2.3 Packets

| Kind | Direction | Fields |
|---|---|---|
| `client_hello` | C->S | `modVersion`, `protocol`, `capabilities` (string[]: `render`, `ask`, `context`, `lesson` (plays lessons, section 2.6), `eval` (can run `client_eval_request`), and `nei` when NEI is installed), `clientEvalAllowed` (bool) |
| `ask` | C->S | `text` (string), `context` (object) |
| `client_settings` | C->S | `clientEvalAllowed` (bool): the player changed their opt-in mid-session |
| `client_eval_result` | C->S | `id`, `ok`, `result` (any JSON), `error`, `errorType`, `output`, `ms`, `truncated`: the outcome of a `client_eval_request` (section 1.8). Results the server did not ask for are dropped. |
| `server_hello` | S->C | `modVersion`, `protocol`, `botName`, `triggerPrefixes` (string[]), `maxAskLength` (int; 0 or missing from older servers) |
| `dave_message` | S->C | **Unused since rich chat.** `botName`, `text`, `replyTo`. Dave's replies now arrive as normal chat for everyone; clients still render it if received. |
| `thinking` | S->C | `botName`, `clear` (bool): show the indicator, or remove it because the answer arrived |
| `client_eval_request` | S->C | `id` (server-chosen, `ce<n>`; the result must echo it), `script`, `mode` (`"groovy"`), `timeoutMs` (10..60000; the client uses at most 2000, and 500 for 0), `params` (JSON object or null). Sent only to players whose client reported `clientEvalAllowed` and the `eval` capability; the client re-checks its own opt-in before running it (section 1.8). |
| `lesson` | S->C | A validated lesson (section 2.6): `id`, `title`, `loop`, `speed`, `dim`, `anchor` (x,y,z), `start` (x,y,z), `steps` |
| `lesson_stop` | S->C | `id` (null or empty: whatever is playing) |
| `lesson_status` | C->S | `id`, `state` (`finished`/`stopped`), `reason` (see `lesson_status` in 1.3) |

Server-side validation of client packets:

- `lesson_status`: only from players whose client half said hello; relayed as described in 1.3.
- `client_eval_result`: relayed only when its `id` is one this server sent to that same player and is still
  waiting for; anything else is dropped.
- `client_hello`: only the first one per login counts; version and capability strings are clipped to 64
  characters, at most 32 capabilities. Chat lines use client-command links (`/davebridge nei ...`) only for players
  whose client reported the `nei` capability; everyone else gets the plain-chat formats.
- `client_settings`: only accepted after `client_hello`; a value that did not change is dropped, a change is
  announced to the agent as a fresh `client_mod` event. Servers older than 0.1.2 log it as an unknown kind and
  ignore it (the opt-in then reaches them on the next login).
- `ask`: only accepted after `client_hello`; `text` must be 1..`maxAskLength` characters and must contain a question
  after its trigger prefix (a bare `dave` or `dave,` is refused); at most one per `askCooldownMillis` per player. Refused asks
  get a gray notice saying why (empty, too long, or "slow down"). `context` is dropped unless it is a JSON object of at
  most 16 KiB. An accepted ask is shown to **every player** as an ordinary chat line, `<Player> text`, exactly like
  vanilla chat and with the trigger prefix kept, because Dave's replies are public and others may join in. It is
  not posted as a `ServerChatEvent` (that would relay it twice), so other chat mods do not see it. It is relayed to the
  agent as a `chat` event with `source:"client"`, `triggered:true` and `prompt` = text without a trigger prefix
  (if it had one).

### 2.4 Ask context

The client half captures context when the player presses the **Ask Dave** key (unbound by default; set it in
Controls). The key opens a chat line pre-filled with `askPrefix` (default `dave, `). On a DaveBridge server
the line is sent as an `ask` packet, up to the server's `maxAskLength` (1000 if the server does not say), and everyone
sees the question as normal chat (see 2.3); on any other server it is sent as ordinary chat. A `/command` typed
into that line still goes through the normal command path; one longer than vanilla's 100 characters is refused with
a message (unless it is a client command), since the chat packet would cut it short.

```json
{"dim":0,"biome":"Plains",
 "heldItem":{"id":"minecraft:iron_pickaxe","meta":0,"count":1,"hasNbt":false,"name":"Iron Pickaxe"},
 "lookingAt":{"kind":"block","x":10,"y":64,"z":-4,"side":1,"id":"gregtech:gt.blockmachines","meta":0,
              "name":"Basic Macerator","tileEntity":"gregtech.api.metatileentity.BaseMetaTileEntity"}}
```

`lookingAt` is either `{"kind":"block",...}` as above or
`{"kind":"entity","entityId":..,"class":"...","type":"Zombie","name":"...","uuid":"..."}`.
If a lookup throws, the field is left out (or `captureError` is set). Not captured yet: NEI's hovered item, and
context from inside GUIs (keys pressed in a GUI do not reach the keybind in 1.7.10, so the key only works
in-world). An `openGui` field (screen class name) is reserved for that.

### 2.5 What the client half adds to chat

- **Thinking indicator.** `thinking` shows one gray italic line that replaces itself on repeat; `thinking` with
  `clear:true` (sent right after Dave's reply) removes it. It also expires after 60 s.
- **Client command `/davebridge nei <recipe|uses> <modid:name[:meta]>`.** Registered with `ClientCommandHandler`,
  so a `run_command` click reaches it. On the next client tick it closes chat and opens NEI's recipe or usage page
  (deferred so a typed command is not closed again by the chat screen). The client reports it as the `nei`
  capability when NEI is installed. It only opens GUIs and
  never changes game or client state. Server-rendered links use it for players with the client half (see 1.6).
- Everything else (styles, NEI bookmarks, quest links, buttons) is plain chat that every client renders.

### 2.6 Lessons on the client half

Client halves from 0.2.0 report the `lesson` capability in `client_hello`; the server sends lessons only to them.

The `lesson` packet carries the steps in normalized form, one object per step: `op` (the step name), `pos`
(x,y,z), `id` (item or block), `meta`, `facing`, `color` (0xRRGGBB int), `text` (label or line) and `ticks` (the
step's duration, computed by the server). The client still checks everything again (client-bound packets are decoded
defensively): unknown ops, positions that are not finite or more than 258 blocks from `anchor` (the 256-block
radius plus 2; 34 for clients before 0.2.4), and steps past 256 (64 for clients before 0.2.5) or past a 3600-tick
pass are dropped; a `start` that is missing or out of range is replaced by `anchor`. A lesson with nothing left, or
for another dimension, is answered with `lesson_status` `rejected`.

The ghost Dave is a client-only object, never an entity in the world: nothing is synced and no other player can see
it. Labels, the HUD bar (`Title · Step 3/7 · [K] stop`) and the chat line with `[Pause]` / `[Stop]` buttons come from
the client's lang file, never from the server.

---

## 3. Main-thread task queue

All world and player actions from the agent run on the server thread, during `ServerTickEvent` (END phase).
Socket threads submit `Callable`s to a bounded queue (`maxQueueDepth`) and get a `CompletableFuture`; each
tick the queue runs tasks until `tickBudgetMillis` is used up, always at least one. Exceptions complete the
future and become `error` replies; they never reach the tick loop. The same queue class is drained on the client
tick by the client half. Eval, `describe`, `client_eval` and lessons submit their work to the same queue.

---

## 4. Configuration

### `config/davebridge.cfg` (server, also used by singleplayer)

| Category | Key | Default | Meaning |
|---|---|---|---|
| network | `enabled` | `true` | Start the WebSocket at all. |
| network | `startOnIntegratedServer` | `false` | Also open the socket for singleplayer/LAN worlds. |
| network | `bindAddress` | `127.0.0.1` | Address to bind. `127.0.0.1` for dev; `0.0.0.0` in Docker (see section 6). |
| network | `port` | `25580` | TCP port. |
| network | `authToken` | random | Shared secret. Generated (48 hex chars) on first run if empty. |
| network | `authTimeoutSeconds` | `10` | Drop connections that have not authenticated by then. |
| network | `maxClients` | `4` | Maximum simultaneous WebSocket connections. |
| network | `maxMessageBytes` | `1048576` | Largest inbound WebSocket frame. |
| chat | `triggerPrefixes` | `dave` `@dave` `!dave` | Case-insensitive prefixes that address Dave. A prefix ending in a letter must be followed by a space or punctuation (`Dave how…`, `dave, …`), never another letter or an apostrophe (`davey`, `dave's`). Plain chat also counts with a greeting first (`hey dave, …`) or the name last (`how do I make steel, dave?`); clickable `{send:}` text must still start with a prefix. |
| chat | `forwardAllChat` | `false` | Forward every chat message (`triggered:false` for the rest). |
| chat | `botName` | `Dave` | Name in `<Dave>`. |
| chat | `botNameColor` | `aqua` | Minecraft color name for the plain-chat name. |
| chat | `chatWidth` | `320` | Players' chat box width in pixels (vanilla default at 100%). Replies are cut into lines this wide. |
| chat | `maxLinesPerMessage` | `40` | Plain-chat lines per `say` before truncation. |
| chat | `notifyWhenOffline` | `true` | Tell a player when they address Dave and no agent is connected. |
| chat | `askCooldownMillis` | `1000` | Minimum time between asks from one client. |
| chat | `maxAskLength` | `2000` | Longest ask text accepted. |
| chat | `urlAllowlist` | (empty) | Hosts `{url:}` may link to, matched as domain suffixes; empty allows any http/https host. |
| chat | `commandAllowlist` | `/dave` `/davebridge` `/bq_client` `/nei_bookmark` `/savewaypoint` `/guidenhc` | Commands a `{cmd:}` button or a raw `run_command` may run. |
| chat | `replySound` | `random.orb` | Sound each recipient hears when Dave replies; empty disables. |
| chat | `replySoundVolume` | `0.5` | 0 to 1. |
| chat | `replySoundPitch` | `1.4` | 0.5 to 2. |
| queue | `tickBudgetMillis` | `5` | Time per tick for queued tasks. |
| queue | `maxQueueDepth` | `1000` | Further tasks are rejected (`queue_full`). |
| eval | `evalEnabled` | `true` | Accept `eval` (`disabled` otherwise). `describe` and `client_eval` do not check it. |
| eval | `evalTimeoutMillis` | `500` | Default time limit of a script (it holds the server tick while it runs). |
| eval | `evalMaxTimeoutMillis` | `5000` | Largest `timeoutMs` a request may ask for. |
| eval | `evalMaxResultChars` | `65536` | Approximate cap on a result's JSON size. |
| eval | `evalMaxOutputChars` | `16384` | Printed output kept per script. |
| eval | `confirmTimeoutSeconds` | `120` | Default wait for a Confirm click. |
| eval | `confirmMaxTimeoutSeconds` | `600` | Longest `confirmTimeoutSeconds` a request may ask for. |
| eval | `backupCommand` | (empty) | Server command run right before a confirmed write, e.g. `backup start` (ServerUtilities; it copies in the background, so the backup may finish after the change). |
| eval | `clientEvalRelay` | `true` | Relay `client_eval` to opted-in players at all. |
| eval | `clientEvalTimeoutSeconds` | `30` | How long to wait for a client's result. |
| hooks | `hooksEnabled` | `true` | Accept `hook_add` and fire hooks. Off: hooks are kept but never fire. |
| hooks | `hookMaxCount` | `64` | Most hooks on the server. |
| hooks | `hookMaxPerOwner` | `16` | Most hooks one player may own. |
| hooks | `hookMinPollTicks` | `20` | Shortest `everyTicks` of a poll hook. |
| hooks | `hookPollTimeoutMillis` | `50` | Time limit of one hook script run (at most `evalMaxTimeoutMillis`). |
| hooks | `hookTickBudgetMillis` | `5` | Time per tick for poll conditions (at least one runs). |
| hooks | `hookMaxFiresPerMinute` | `30` | Notifications per minute, all hooks together. |
| hooks | `hookDefaultCooldownSeconds` | `10` | Default `cooldownSeconds`. |
| hooks | `hookMaxErrors` | `5` | Script failures in a row before a hook is disabled. |
| hooks | `hookDefaultTtlHours` | `168` | Lifetime of a hook without `expiresInSeconds`. |
| hooks | `hookMaxTtlHours` | `0` | Longest lifetime a hook may ask for; 0 = no limit (`expiresInSeconds: 0` then means never). |
| hooks | `hookOutboxSize` | `50` | Notifications kept while no agent is connected. |
| undo | `undoEnabled` | `true` | Journal confirmed writes and accept `undo`. |
| undo | `undoMaxSnapshots` | `100` | Snapshots kept; the oldest go first. |
| undo | `undoMaxStorageMB` | `64` | Disk space for all snapshots (compressed). |
| undo | `undoMaxAgeDays` | `30` | Snapshots older than this are deleted; 0 = never. |
| undo | `undoMaxBlocksPerSnapshot` | `100000` | Positions one snapshot records; beyond it the snapshot is `partial`. |
| undo | `undoMaxRegionBlocks` | `65536` | Volume of a script's explicit snapshot regions. |
| undo | `undoTickBudgetMillis` | `10` | Time per tick spent restoring during an undo. |

`/dave reload` re-reads the file. The socket restarts only if `bindAddress`, `port`, `authToken`,
`maxClients` or `maxMessageBytes` changed (which also drops connected agents), or if `enabled` changed.

### `config/davebridge-client.cfg` (client half only)

| Category | Key | Default | Meaning |
|---|---|---|---|
| client | `clientEvalAllowed` | `false` | Opt in to letting Dave run code on this computer (`client_eval`, section 1.8). Same as `/davebridge optin` / `optout`. |
| client | `optInReminder` | `true` | Show the opt-in nudge when joining a DaveBridge server while not opted in. `/davebridge optout` turns it off. |
| client | `askPrefix` | `dave, ` | Text pre-filled by the Ask Dave key. |

## 5. Commands

`/dave status` and `/dave reload`, permission level 3 (ops). Status shows the socket state, connected and
authenticated agents, queue depth and timing, how many online players have the client half, how many changes
wait for a confirm, how many hooks there are and whether undo is on.

**Confirms (any player, or the console).**

- `/dave confirm <id>` / `/dave cancel <id>`: what the `[Confirm]` and `[Cancel]` buttons of a world-changing
  script or an undo run (sections 1.8, 1.10). A player may answer only their own confirm; the console (or RCON) may
  answer any.

**Test hooks (ops and the console).** These exist so the whole Dave loop can be exercised from `rcon-cli` with nobody
online:

- `/dave ask <asName> [@x,y,z[,dim]] <question...>`: sends the agent a `chat` event exactly like a triggered chat
  line from `asName`: `triggered:true`, `prompt` = the question without a trigger prefix (if it has one),
  `source:"console"`, `hasClientMod:false`. `playerUuid` is the online player's, else the one Forge's username cache
  remembers for that name, else the offline-mode UUID derived from the name (stable). The position defaults to the
  online player's, else the overworld spawn (dim 0). Replies need no player online: a public reply is broadcast as
  usual, a whisper to an absent name is dropped with a warning in the `say` ack.
- `/dave pending`: lists the changes waiting for a confirm (`id`, asking player, seconds left, summary).
- `/dave hooks [owner]`: lists the hooks (id, event, owner, name, interval, once or repeating, fire count, errors,
  expiry) and how many notifications wait for an agent. `/dave hooks remove <id>` removes one. `/dave hooks fire
  <id>` sends a test `hook_fired` (`detail.test:true`; the hook is not removed or counted against the rate limit).
- `/dave snapshots [player]`: the newest 15 snapshots (id, player, age, blocks, entities, undone and redo, summary)
  and the disk space used.

**Undo (the console, ops, or the player whose change it was).**

- `/dave undo <id|last> [player] [force]`: restores a snapshot (section 1.10) at once: typing it, or clicking the
  `[Undo]` button after a confirmed change, is the confirmation. `last` is the newest snapshot not yet undone (of
  `player` for ops and the console, else the sender's own). A player who is not an op may undo only their own
  changes. Snapshots of up to 4096 positions are restored inside the command and the result is the reply; bigger
  ones run over the next ticks and report to the player when done (the console: in the log and `/dave snapshots`).

`DaveBridge/tools/eval_check.py` runs these against a test server (agent socket plus RCON: reads, timeouts, blocked
calls, describe, a confirmed and a cancelled write, an expiry, `/dave ask`, the starter queries; `--gt` adds the
GregTech checks). It places one glass block 40 above spawn and removes it again. `dave/daemon/e2e_bridge_check.py`
does the same through the real daemon and the `bridge` MCP server, with a fake Claude (no model calls).
`DaveBridge/tools/hooks_undo_check.py` checks hooks and undo (login, chat and poll hooks, refused and failing scripts,
confirmed writes with snapshots, undo and redo, a chest's contents and drops, conflicts, persistence over a restart,
the outbox) in two phases around a restart; with `--op-bot` an op'd scripted player stands in for RCON.

Client half only:

- `/davebridge nei <recipe|uses> <modid:name[:meta]>` (section 2.5), the target of Dave's recipe and usage links.
- `/davebridge stop`, `/davebridge pause`: stop or pause/resume the lesson playing (section 2.6), the targets of the
  lesson's chat buttons. The **Stop Dave's demonstration** key (Controls, unbound by default) also stops it.
- `/davebridge optin`, `/davebridge optout`, `/davebridge status` (also bare `/davebridge`): the client-eval opt-in.
  Saved to `davebridge-client.cfg` and sent to the server at once (`client_settings`). Opting out also turns off
  the reminder.

The client options are also editable in game: Mods > DaveBridge > Config.

**Opt-in nudge.** About five seconds after joining a server that answers `server_hello`, a client that is not opted
in (and has `optInReminder` on) shows one `[Dave]` line explaining what opting in gives, with clickable
`[Opt in]` (`/davebridge optin`) and `[No thanks]` (`/davebridge optout`) buttons. The text is in the client's
lang file (`davebridge.optin.*`), so it never comes from the server.

## 6. Docker deployment

Dave runs as his own container in the same Docker stack as the GTNH server and connects over the stack's
internal network, e.g. `ws://gtnh:25580` (the server's service name).

- **Server container:** set `bindAddress=0.0.0.0` in `config/davebridge.cfg`. Loopback (`127.0.0.1`) is not
  reachable from other containers.
- **Do not publish the port.** Leave 25580 out of the server's `ports:` (publish only the Minecraft port), and
  don't use `network_mode: host`. The bridge is then reachable only from containers on the stack network, never
  from the host or the LAN.
- **Token as a secret.** The server generates `authToken` into `config/davebridge.cfg` on first start (or set
  your own value there before starting). Give the same value to Dave's container as a Docker secret (e.g.
  mounted at `/run/secrets/dave_token`), not as a plain environment variable in the compose file or image. Dave
  sends it in the `auth` message or the `Authorization: Bearer` header. Changing it takes `/dave reload` (which
  drops connected agents) and a matching secret update.
- Check with `/dave status`: it should say `listening on 0.0.0.0:25580`.

```yaml
services:
  gtnh:
    # ... GTNH server image; config/davebridge.cfg has bindAddress=0.0.0.0
    ports:
      - "25565:25565"        # Minecraft only; 25580 stays internal
  dave:
    # ... agent image; connects to ws://gtnh:25580
    secrets:
      - dave_token
secrets:
  dave_token:
    file: ./secrets/dave_token   # same value as authToken in davebridge.cfg
```
