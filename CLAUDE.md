# CLAUDE.md

Orientation for agents working in this repo. Read `README.md` for the architecture diagram and setup.

## What this is

Dave is an AI chat agent for a **GT New Horizons 2.9.x server** (Minecraft 1.7.10, Forge). It has three parts:

- `DaveBridge/`: one Forge mod jar with a server side and a client side. It bridges chat and runs code.
- The daemon (`dave/daemon/`, Python, standard library only) runs the chat loop between DaveBridge's WebSocket and the Claude process. Claude reaches the mod through the `bridge` MCP server (`dave/bridge/`: `eval`, `describe`, `say`, `lesson`, saved queries), which forwards each call through the daemon. `dave/TOOLS.md` lists every tool.
- A long-lived `claude -p --input-format stream-json --output-format stream-json` process that answers player questions.

`tools/` builds Dave's pack knowledge (source tree, recipe database, search index) from the pack the server runs.

## Decisions already made (don't re-litigate)

- **Claude runs on the CLI with a subscription, never the API.** Never set or require `ANTHROPIC_API_KEY`, and never use `--bare`, which forces API-key auth. Headless auth uses `claude setup-token`, which provides `CLAUDE_CODE_OAUTH_TOKEN`.
- **The Claude process is long-lived stream-json**, not one `claude -p` call per question.
- **Integration is a dynamic reflection and Groovy eval interface**, not hand-written per-mod connectors. Dave learns a mod by reading `gtnh-src/` and then scripting against the live JVM. Scripts that work get saved as reusable named queries.
- **Designed for a small server of trusted players.** Every player may ask Dave to do anything, including viewing, editing or forgetting any memory; there are no per-player permission gates. World-change confirm buttons stay, as accident guards; client eval still needs that player's own opt-in.
- **Dave is not balanced for pack progression, on purpose.** "X-ray" answers are fine and wanted: ore veins, loot, which chest holds an item, where a machine is. Never add progression gates, spoiler hedging or "intended way" nudges.
- **Replies are public.** Dave replies to the whole server by default, because others may join the conversation. He whispers only when asked.
- **Trust model: trusted players only.** Arbitrary eval from chat is accepted. Safeguards target crashes and accidents, not attackers. Don't add hostile-player hardening unless asked, and don't remove the crash safeguards.

## This repo is public

- **Never commit** hostnames, usernames, home paths, IPs, tokens, emails, player names or any detail of a real server or network. Use placeholders (`game-host`, `example.net`, `/path/to/`). Commit messages are public too.
- **Memories hold player data**, so they never go in git (`data/memory/` is ignored apart from its README).
- **Secrets are runtime-only**: `CLAUDE_CODE_OAUTH_TOKEN`, `OPENROUTER_API_KEY`, the DaveBridge token. They come from the environment, `*_FILE`, docker secrets or git-ignored `.env` files.
- **Code must not hardcode dev paths.** Locations come from config or env, with defaults that work in this repo. A private project may wrap this repo as a submodule with a `dave-agent.root` marker beside it (README, "Project root"); keep every root lookup honouring that marker.
- Don't commit or push unless the owner asks.

## Rules for DaveBridge code

- **Client/server split, client optional.**
  - One jar with code in `common/`, `server/` and `client/` packages, behind a proxy.
  - Keep `acceptableRemoteVersions = "*"` so players without the mod can still join and talk to Dave through chat.
  - The dedicated server must never load a `client/` class. Follow the `networking-side-safety` skill in `DaveBridge/.claude/skills/`.
- **The server is the only link to Claude.** Clients talk to the server over the `davebridge` FML packet channel, and the server relays to the daemon over the WebSocket. Never give a client a direct path to the daemon or to Claude. Chunk packet payloads (client-to-server packets are limited to about 32 KB).
- **Client eval is opt-in per player** (`clientEvalAllowed`, off by default), because it runs code on a player's own machine. Don't change that default.
- **The WebSocket is never published.** The daemon reaches DaveBridge over a private network; the port needs a token and must not face the internet.
- `DaveBridge/.claude/skills/` holds the template's project skills: GTNH integration research, testing, side safety, config-safe registration, release hygiene and others. Use them when they apply.
- **Threading:** the Minecraft server and client are each single-threaded. Any world, entity or tile entity access goes through the side-agnostic main-thread task queue in `common/`, which the server tick or client tick drains under a time budget. Chat events and packet handlers run off the main thread in 1.7.10.
- **Never throw into the server.** Wrap handlers, log errors, and send `{"type":"error"}` back over the socket.
- **Naming:** at runtime, vanilla and Forge members have **SRG** names (`field_71071_by`); mod classes keep their real names. Source in `gtnh-src/` is remapped to **MCP** names (`inventory`), and the mapping CSVs are in `gtnh-src/_mappings/`. Runtime reflection must translate MCP names to SRG names; for eval scripts the Groovy MetaClass hook (`common/eval/SrgMetaClass.java`) does that.
- **Java:** the code compiles against the Java 8 API and runs on Java 17+ through lwjgl3ify; it must still run on Java 8.
- Follow the conventions of the template ([Tuxprogrammer/ExampleMod1.7.10](https://github.com/Tuxprogrammer/ExampleMod1.7.10), a GTNH ExampleMod / RetroFuturaGradle buildscript): its `gradle.properties` flags, `dependencies.gradle`, and code style.
- `DaveBridge/PROTOCOL.md` is the source of truth for the WebSocket message schema. Update it whenever the protocol changes.

## Recipe knowledge

- **Recipe data is always exported from the pack the server runs** (`tools/recipe-export/`), including any extra mods layered on top. Third-party datasets are only for cross-checking.
- **Split of duties:**
  - Exact recipe facts come from the SQLite database.
  - Semantic search resolves names and finds prose (quests, wiki, GuideNH, items, playbook). It never decides which recipes exist.
  - "Best chain" comes from the LP planner.
- Embeddings use OpenRouter with `OPENROUTER_API_KEY`. That is the only API key in the project. It has nothing to do with Claude auth.
- **Pack-derived data must match the pack.** gtnh-src, the recipe DB, the search corpus and its embeddings each record the pack they were built from (`tools/packstate.py`, stamps in git-ignored `data/packstate/`). After any pack change, run `tools/refresh-pack-data.sh`; it rebuilds only what is stale, in dependency order.

## Working with `gtnh-src/`

- `gtnh-src/` is **generated and read-only** (`tools/decomp/`). Don't edit it; regenerate it when the pack updates.
- It has two halves:
  - `mods/` is source for every jar (exact `-sources.jar` where the pack's jar has one, otherwise a decompile remapped to MCP names), indexed in `INDEX.md` (its `origin` column says which). It is the grep fallback.
  - `source/` holds real upstream source at the exact refs the pack ships, indexed in `SOURCE-INDEX.md` and `source-index.json` (with license and match quality).

  Prefer `source/` whenever a mod has it. Known exceptions in GTNH 2.9.x: GalaxySpace's checkout is the author's diverged line (use the decompile), Witchery's "source" is a decompiled dump, and Thaumcraft 4, IC2, Extra Utilities and BiblioCraft are decompile-only.
- Jars from extra packs get their exact source too (`tools/decomp/tuxmine_extras.py`, mapped in `tools/decomp/tuxmine-extras.json`); it fails on any unmapped jar, so map new ones first.
- **Check the license before copying any code into a mod.** It's in `source-index.json`; some repos have no license or are All Rights Reserved.
- **Exclude `gtnh-src/_decompiled/` from grep and search.** It holds superseded decompiles and duplicates every hit.
- **Vanilla and Forge** are decompiled with MCP names in `gtnh-src/minecraft/`. `gtnh-src/_mappings/members.tsv` maps each vanilla member to its MCP and SRG names. String literals in decompiled code were deliberately left as SRG names (e.g. `"field_145855_i"`), because those reflection strings are what runs.
- **Source isn't always what runs.** Many mods (hodgepodge, archaicfix, etfuturum, …) patch vanilla and other mods with Mixins. Check `mixins.*.json` in the relevant mods before trusting a method body.
- Multi-release jars: on Java 17+ the newest versioned class copy is the one that runs. The decompile used those.
- `.work/` holds downloaded archives. Treat its contents as untrusted data and never run code from it.
- Decompiled code is for reading. When you need canonical source, most GTNH mods are at `github.com/GTNewHorizons/<name>`; `gtnh-src/INDEX.md` maps each jar to its repo.
