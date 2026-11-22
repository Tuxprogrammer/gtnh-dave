# planner: the GTNH planner site's agent API as MCP tools

`server.py` is a stdio MCP server (standard library only, run with `python3 -I`) that gives Dave the agent API of a
self-hosted GTNH planner website: recipe lookups in the planner's own dataset, and plans (drafts) built and solved on
the site for a player, then delivered as a new plan they open in game. One tool per endpoint, plus
`plan_import_current` and `plan_add_chain`. The tool list is in [`dave/TOOLS.md`](../../dave/TOOLS.md#mcp-planner-live-when-configured-toolsplanner).

API spec: <https://github.com/Tuxprogrammer/gtnh-factory-flow/blob/main/docs/agent-api.md>. The site answers a bare
404 on `/api/agent/` until its `AGENT_API_TOKEN` is set, and every endpoint the spec marks "not yet built" answers a
bare 404 too; the tools report that as "not available on the planner site yet".

## Configuration

The server reads its environment at call time:

| Variable | Meaning |
|---|---|
| `PLANNER_API_URL` | The site's base URL on the internal network, e.g. `http://planner.example.invalid:3000`. The API path `/api/agent/v1/` is added (a URL that already ends in it works too). No default |
| `PLANNER_API_TOKEN_FILE` | A file holding the bearer token (the site's `AGENT_API_TOKEN`), read on every call and stripped of whitespace. It is never logged or echoed |
| `DAVE_BRIDGE_SOCKET` | The Dave daemon's RPC socket, for `plan_import_current` only |

Without the URL or a non-empty token file, every tool answers "the planner is not configured".

Dave's launcher sets these from its own config, and registers the server only when both are configured:

- `[planner] url` in `dave/config.toml`, or `DAVE_PLANNER_URL` (default empty);
- the `planner_api_token` secret: `PLANNER_API_TOKEN`, a file named by `PLANNER_API_TOKEN_FILE`, the docker secret
  `planner_api_token`, or `PLANNER_API_TOKEN=` in the git-ignored `dave/.env` outside a container. A value (rather
  than a file) is written to `<run>/secrets/planner_api_token` (0600) for the server.

`python3 -I dave/launch/dave_cmd.py --print` lists `planner` among the MCP servers once it is configured, and
otherwise says why it was left out.

## Behaviour

- Every call is a JSON `POST` with `Authorization: Bearer <token>`; redirects are not followed and no proxy is used
  (the API is for the internal network only). Timeouts: 10 s for `plans/solve` and `plans/expand-inputs`, 6 s for
  the rest. Request bodies over 64 KB (512 KB for `plans/import`) are refused before sending, as the site would.
- Arguments are passed through as the request body (null members dropped). The input schemas match the spec's
  request fields and refuse unknown ones, as the site does.
- `plan_add_cards` takes cards as `{recipeId, machine?, tier?, dials?: {dialId: option key or number}}` and sends
  `dials` nested. That shape is proposed to the site; `api_card()` is the one place to change if it settles on
  another.
- `plan_import_current(turn?, player?)` asks the daemon for this turn's panel attachment (the bridge RPC op
  `surface_attachment`, as the `bridge` tool of that name does), reads the JSON file and posts it as
  `{snapshot, player?}` to `plans/import`. The answer is the site's (`planId`) plus `attachment` (turn, surface,
  bytes, and `earlier: true` when it is the asker's earlier ask's).
- `plan_add_chain(planId, chain, set_target?)` builds a chain from the recipes server's `plan_chain` (called with
  `export: true`; `chain` is the JSON after `PLANNER-CHAIN: `) into a draft, so Dave's own optimizer and the site's
  solver work on the same chain. For each step it searches the site (`recipes/search`: `makes` plus the step's machine
  and first input, then looser) and takes the recipe whose EU/t, duration (within a tick), outputs and consumed
  inputs are the step's (`chain_match`; ore dictionary slots are not compared, and an "any of" slot matches by its
  alternatives). Both datasets are exported from our pack, so a step normally matches exactly. The matched recipes go
  in as cards in one `plans/add-cards` call at the draft's `cardTier` (one by one if the site refuses the batch, so
  one bad card doesn't lose the rest), then `plans/set-targets` gets the chain's target. The answer lists each
  step's `recipeId` and `cardId`, and each unmatched step with up to three near candidates and what differs. At
  most 40 steps; searches run four at a time.
- Answers come back as compact JSON. One over 16,000 characters (above the site's own 12 KB aim) is cut, with a first line that says so and suggests
  a smaller `limit`.
- Errors: the site's `{"error": {"code", "message"}}` becomes `planner error <code>: <message>`; a bare 404 becomes
  `<endpoint> is not available on the planner site yet (or the agent API is off).`; a refused connection or a
  timeout becomes `planner site unreachable: ...`.

## Tests

    python3 -I tools/planner/test_planner.py

A local fake of the site (checking the bearer token) answers with the site's own examples in `testdata/`
(`docs/agent-api/examples/` in the site's repo). The tests cover each error mapping, truncation, the
not-configured cases, the dials translation, `plan_import_current` against a fake daemon socket, the stdio
server end to end, and `dave_cmd.py`'s registration (left out until configured; the token only as a file).
