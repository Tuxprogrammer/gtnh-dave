# DaveBridge

A Forge mod for Minecraft 1.7.10 / GT New Horizons that connects the server to an external AI agent ("Dave").

- **Server half (required on the server):** a local WebSocket the agent connects to, chat triggers
  (`dave` `@dave` `!dave`, so `Dave how…` and `dave, …` both work), player join/leave events, Dave's replies as rich chat (item tooltips, NEI bookmark
  and quest links, buttons, rendered per recipient), `/dave` (status, reload, confirms, test hooks), a
  main-thread task queue, live Groovy eval and introspection (`eval`, `describe`, MCP names mapped to SRG at
  runtime; world-changing scripts wait for an in-game confirm and can be undone from a journaled snapshot), event
  hooks that tell the agent when something happens in the game, and guided demonstrations sent to the client half.
- **Client half (optional on clients):** a thinking indicator, links that open NEI recipe/usage pages, an
  "Ask Dave" key that sends the question with what you are looking at and holding, guided demonstrations (a ghost
  Dave only that player sees), and client eval relayed by the server for players who opt in (off by default).
  Clients never talk to the agent; everything goes through the server. Players without the mod use Dave through plain chat.

One jar for both sides (`acceptableRemoteVersions = "*"`). Built for the GTNH 2.9.x dedicated server on modern
Java (17+ via lwjgl3ify); the code also runs on Java 8.

See [PROTOCOL.md](PROTOCOL.md) for the WebSocket and packet protocol and every config key,
[docs/dave-markup.md](docs/dave-markup.md) for the chat markup Dave writes (prompt-ready), and
[docs/chat-integration.md](docs/chat-integration.md) for how it maps onto the pack's native chat features
(BetterQuesting quest links, NEI bookmark links, GTNHLib item names, sharewhereiam waypoints).

## Install

1. Put `build/libs/davebridge-<version>.jar` into the server's `mods/` folder (and optionally clients').
2. Start the server once. It writes `config/davebridge.cfg` with a random `authToken`.
3. Point the agent at `ws://127.0.0.1:25580` and send `{"type":"auth","token":"<authToken>"}` first.

In Docker (Dave in his own container on the same stack network), set `bindAddress=0.0.0.0`, don't publish
port 25580, and pass the token to Dave's container as a secret. See "Docker deployment" in
[PROTOCOL.md](PROTOCOL.md).

## Package layout

| Package | Loaded on | Contents |
|---|---|---|
| `com.tuxprogrammer.davebridge` | both | `@Mod` class |
| `...common` | both | proxy base, server config, packet DTOs, packet channel and chunking, main-thread queue, Groovy script engine and SRG name mapping, lesson limits |
| `...server` | both (logical server) | agent WebSocket, chat hooks, relay and markup, `/dave` command, eval, describe and confirms, event hooks (`hooks`), snapshots and undo (`undo`), client-eval relay, lessons, client-mod tracking, NEI/BQ/GTNHLib compat |
| `...client` | client only | `ClientProxy`, rendering, Ask key and chat line, context capture, client eval and opt-in, lesson playback (ghost Dave), client config and GUI |

Nothing outside `client` references client classes; `SideSafetyTest` checks the compiled classes.

## Build and test

```sh
./gradlew spotlessApply build    # format, compile, unit + side-safety tests
./gradlew runServer21            # dev dedicated server on Java 21 with lwjgl3ify
uv run tools/dave_client.py --config run/server/config/davebridge.cfg selftest
uv run tools/e2e_chat_test.py --mc-port <server-port> --commands --stop
```

The e2e test needs `online-mode=false` on the dev server and, for `--commands`/`--stop`, the test player
`PlainBot` in `run/server/ops.json`. Dev runs load NEI, CodeChickenCore and BetterQuesting (pinned to the pack's
versions); for the quest-link checks, copy the pack's `config/betterquesting/DefaultQuests` into
`run/server/config/betterquesting/` so BetterQuesting loads the real quest database.

`tools/` scripts are for local dev servers only.

The version comes from git tags (GTNH buildscript). This repo has no tags, so set it when building a release:
`VERSION=<x.y.z> ./gradlew build` (without it the jar is named after the commit).

## Template

Built on the GTNH ExampleMod buildscript (RetroFuturaGradle). Do not edit `build.gradle.kts` or
`settings.gradle.kts`; use `gradle.properties`, `dependencies.gradle` and `repositories.gradle`. Buildscript
help: [docs/FAQ.md](docs/FAQ.md). Agent guidance: [CLAUDE.md](CLAUDE.md) and `.claude/skills/`.

## License

MIT, Copyright (c) 2026 Spencer Callicott. See [LICENSE](LICENSE). Notices for the template and the shaded libraries are in [THIRD-PARTY-NOTICES.md](THIRD-PARTY-NOTICES.md); both files also ship inside the jar.
