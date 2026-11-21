"""MCP server "planner" (stdio, JSON-RPC 2.0): the GTNH planner website's agent API for Dave. Standard library only.

  python3 -I tools/planner/server.py

One tool per agent API endpoint (spec: https://github.com/Tuxprogrammer/gtnh-factory-flow/blob/main/docs/agent-api.md),
plus plan_import_current, which imports the plan the player attached to this turn's panel ask. Configuration comes
from the environment, read at call time:

  PLANNER_API_URL          the site's base URL on the internal network, e.g. http://planner.example.invalid:3000
  PLANNER_API_TOKEN_FILE   a file holding the bearer token (the site's AGENT_API_TOKEN)
  DAVE_BRIDGE_SOCKET       the Dave daemon's RPC socket (plan_import_current only; dave/daemon/rpc.py)

Without the URL or the token every tool answers that the planner is not configured. The token is never logged or
echoed. dave/launch/dave_cmd.py registers this server only when both are configured.
"""

import json
import os
import socket
import sys
import threading
import time
import traceback
import urllib.error
import urllib.request
from concurrent.futures import ThreadPoolExecutor

PROTOCOL = "2025-06-18"
CALL_WORKERS = 4
API_PATH = "/api/agent/v1/"
TIMEOUT = 6                 # seconds, every endpoint ...
SLOW = {"plans/solve": 10, "plans/expand-inputs": 10}    # ... except the solver's
MAX_TEXT = 16000            # characters of response JSON handed to Dave (the site itself aims at 12 KB)
MAX_BODY = 64 * 1024        # the API refuses request bodies over 64 KB ...
MAX_BODY_BY_ENDPOINT = {"plans/import": 512 * 1024}  # ... except plans/import (a plan snapshot)
DAEMON_TIMEOUT = 10

INSTRUCTIONS = (
    "The GTNH planner website's agent API: recipes from the planner's own dataset, and plans (drafts) built and "
    "solved on the site for a player. Plans take only the planner's ids: find resources with resources_search and "
    "recipes (recipeId) with recipes_search, then recipes_get for a recipe's machines and dials. A draft lives on "
    "the site for 24 h after its last change, and a site restart drops it (plan_not_found: build it again). "
    "plan_deliver stores the finished plan as a NEW plan the player opens in game with /gtnhplanner open <id>; it "
    "never changes their own designs. For the player's open plan (a GTNH Planner panel ask with an attachment) use "
    "plan_import_current, then plan_diff for a proposal they apply. Rates are per second, power in EU/t. Some plan "
    "tools may answer that the endpoint is not available on the site yet."
)

TIERS = ["ULV", "LV", "MV", "HV", "EV", "IV", "LuV", "ZPM", "UV", "UHV", "UEV", "UIV", "UMV", "UXV", "MAX"]
TIER = {"type": "string", "enum": TIERS}
ID = {"type": "string"}
PLAYER = {"type": "object", "description": "The player you act for (logged by the site for audit).",
          "properties": {"name": {"type": "string"}, "uuid": {"type": "string"}},
          "required": ["name"], "additionalProperties": False}
RESOURCE = {"description": "A resource: its planner id from resources_search (a bare id is tried as an item, then "
                           "a fluid, then an aspect), or {id, kind}.",
            "anyOf": [{"type": "string"},
                      {"type": "object", "properties": {"id": {"type": "string"},
                                                        "kind": {"type": "string",
                                                                 "enum": ["item", "fluid", "aspect"]}},
                       "required": ["id", "kind"], "additionalProperties": False}]}
CONSTRAINTS = {
    "type": "object", "additionalProperties": False,
    "description": "tierCap and heat (K) filter recipes; banned takes ids or names (recipe, map, machine); free "
                   "means 'treat as raw' when expanding and solving. cleanroom and lowgrav are not enforced by "
                   "search (the answer lists them in notEnforced).",
    "properties": {"tierCap": TIER, "heat": {"type": "number"}, "cleanroom": {"type": "boolean"},
                   "lowgrav": {"type": "boolean"}, "banned": {"type": "array", "items": {"type": "string"}},
                   "free": {"type": "array", "items": {"type": "string"}}},
}
LIMIT = {"type": "integer", "minimum": 1, "maximum": 25, "default": 8}

# Steam machines read as 0 EU to the site, so its rank and its default machine choice treat them as free, and their
# dataset tiers (taken from the recipe's EU) don't let tierCap filter them. Until the site handles steam itself, these
# tools ban every steam machine unless the plan is for a steam-age player. Handler ids from the planner dataset.
STEAM_MACHINES = (
    "steam-alloy-smelter", "steam-ammo-press", "steam-blender", "steam-compressor", "steam-extractor",
    "steam-forge-hammer", "steam-furnace", "steam-fuser", "steam-grinder", "steam-hearth", "steam-macerator",
    "steam-presser", "steam-purifier", "steam-separator", "steam-squasher",
    "high-pressure-steam-alloy-smelter", "high-pressure-steam-compressor", "high-pressure-steam-extractor",
    "high-pressure-steam-forge-hammer", "high-pressure-steam-furnace", "high-pressure-steam-macerator",
)
ALLOW_STEAM = {"type": "boolean", "description": (
    "Allow steam machines (default: only when tierCap is ULV). Steam isn't free, so leave this off for any player "
    "past the steam age; these tools then ban every steam machine.")}
STEAM_TOOLS = ("plan_create", "recipes_search", "plan_expand_inputs")


def apply_steam_rule(body, default=None):
    """Strip the tools-only allowSteam flag; unless steam is allowed, add every steam machine to constraints.banned.

    Returns (body, allowed). `default` is the draft's own choice (from plan_create), used when the call gives none.
    """
    allow = body.pop("allowSteam", None)
    constraints = dict(body.get("constraints") or {})
    if allow is None:
        allow = default if default is not None else constraints.get("tierCap") == "ULV"
    if allow:
        return body, True
    banned = list(constraints.get("banned") or [])
    seen = {str(b).lower() for b in banned}
    banned += [m for m in STEAM_MACHINES if m not in seen]
    constraints["banned"] = banned
    body["constraints"] = constraints
    return body, False
CARD = {
    "type": "object", "additionalProperties": False, "required": ["recipeId"],
    "properties": {
        "recipeId": ID,
        "machine": {"type": "string", "description": "A machine id from recipes_get (default: the recipe's own)."},
        "tier": TIER,
        "dials": {"type": "object", "description": "Machine settings from recipes_get: {dialId: option key or "
                                                   "number}, e.g. {\"heatingCoil\": \"kanthal\"}.",
                  "additionalProperties": {"type": ["string", "number"]}},
    },
}
END = {"type": "object", "additionalProperties": False, "required": ["cardId", "resource"],
       "properties": {"cardId": ID, "resource": RESOURCE}}
END_TO = {"type": "object", "additionalProperties": False, "required": ["cardId"],
          "properties": {"cardId": ID, "resource": dict(RESOURCE, description=(
              "Only to pick the input slot when several could take the wire (an 'any of' slot by a member)."))}}
DRAFT_CONSTRAINTS = dict(CONSTRAINTS, description=(
    "Kept by the draft for every later add and solve: tierCap caps machine tiers (a tier above it is bad_request), "
    "banned keeps machines and recipes out, free marks resources as raw on purpose. heat, cleanroom and lowgrav do "
    "nothing to a draft."))


def schema(props, required=()):
    props = dict(props, player=PLAYER)
    return {"type": "object", "properties": props, "required": list(required), "additionalProperties": False}


PLAN = {"planId": ID}

# tool name -> (endpoint, description, input schema)
ENDPOINTS = {
    "resources_search": ("resources/search", (
        "Find items and fluids by name in the planner's dataset: {id, kind, name}. Use it to get the ids every "
        "other planner tool takes."), schema({
            "text": {"type": "string", "minLength": 1, "maxLength": 100, "description": "Words of the name."},
            "kind": {"type": "string", "enum": ["item", "fluid"], "description": "Omit to search both."},
            "limit": LIMIT}, ["text"])),
    "recipes_search": ("recipes/search", (
        "Recipes in the planner's dataset that make and/or take a resource, or by name text (at least one of "
        "makes, takes, text): recipeId, machine, minTier, EU/t, seconds, inputs, outputs. Main products rank "
        "first. detail adds each recipe's map, machines and input alternatives. Paged by limit and offset."),
        schema({"makes": RESOURCE, "takes": RESOURCE,
                "text": {"type": "string", "minLength": 1, "maxLength": 100},
                "machine": {"type": "string", "description": "Recipe map or machine name or id, e.g. "
                                                             "'Electrolyzer'."},
                "constraints": CONSTRAINTS, "allowSteam": ALLOW_STEAM, "limit": LIMIT,
                "offset": {"type": "integer", "minimum": 0, "maximum": 1000, "default": 0},
                "detail": {"type": "boolean"}})),
    "recipes_get": ("recipes/get", (
        "One planner recipe in full: inputs with alternatives, outputs, and every machine that runs it with its "
        "tiers, parallels, overclock and dials (the settings plan_add_cards takes)."),
        schema({"recipeId": ID}, ["recipeId"])),
    "plan_create": ("plans/create", (
        "Start an empty draft plan on the planner site; returns {planId, name, expiresAt, constraints}. The draft "
        "expires 24 h after its last change and a site restart drops it. Set the constraints here: plan_solve "
        "takes none."), schema({"name": {"type": "string"}, "constraints": DRAFT_CONSTRAINTS,
                                                        "allowSteam": ALLOW_STEAM},
                                                        ["name"])),
    "plan_import": ("plans/import", (
        "Start a draft from a slim plan snapshot (version 1: plan, cards, drawers, wires, targets; see the agent "
        "API spec); returns {planId, name, cardCount, wireCount, warnings}. The draft keeps the plan's own ids; a "
        "card whose recipe the dataset lacks is kept as is (listed in warnings) and must not be changed. For the "
        "plan the player attached to this panel ask, use plan_import_current instead."),
        schema({"snapshot": {"type": "object"}}, ["snapshot"])),
    "plan_add_cards": ("plans/add-cards", (
        "Add recipe cards to a draft (recipeIds from recipes_search, machine and dials from recipes_get); returns "
        "{cards, wires, cardCount} with each new card's cardId. Wires between cards are made by resource "
        "automatically, both ways. Machine and tier default to the cheapest that runs the recipe within tierCap; the "
        "solver picks machine counts. At most 60 cards per draft; a refused request changes nothing."),
        schema(dict(PLAN, cards={"type": "array", "minItems": 1, "items": CARD}), ["planId", "cards"])),
    "plan_remove_cards": ("plans/remove-cards", (
        "Remove cards from a draft by id; returns {removed, wiresRemoved, cardCount, targets}. A target on a "
        "removed card moves to the next card that makes it."),
                          schema(dict(PLAN, cardIds={"type": "array", "items": ID, "minItems": 1}),
                                 ["planId", "cardIds"])),
    "plan_connect": ("plans/connect", (
        "Wire one card's output resource to another card's input, when the automatic wiring missed it or after "
        "plan_disconnect; returns {wire, created} (created false: it already existed)."),
        schema(dict(PLAN, **{"from": END, "to": END_TO}), ["planId", "from", "to"])),
    "plan_disconnect": ("plans/disconnect", (
        "Remove a wire from a draft by wireId; returns {removed}. Automatic wiring won't remake it."),
                        schema(dict(PLAN, wireId=ID), ["planId", "wireId"])),
    "plan_set_targets": ("plans/set-targets", (
        "Set what a draft must produce: resources and rates per second (replaces the whole list; [] clears it; at "
        "most 16). Each target sits on the card that makes it; no card making it is no_recipe. Returns {targets}."),
        schema(dict(PLAN, targets={"type": "array", "maxItems": 16, "items": {
            "type": "object", "additionalProperties": False, "required": ["resource", "rate"],
            "properties": {"resource": RESOURCE, "rate": {"type": "number", "exclusiveMinimum": 0, "description": "Per second."}}}}),
            ["planId", "targets"])),
    "plan_get": ("plans/get", (
        "A draft's cards, wires and targets, compact, with cardCount and wireCount. A big draft truncates "
        "(truncated: true): page it with fields and cardIds."), schema(dict(
            PLAN, fields={"type": "array", "items": {"type": "string", "enum": ["cards", "wires", "targets"]},
                          "description": "Only these (default all three)."},
            cardIds={"type": "array", "items": ID, "description": "Only these cards and the wires touching them."}),
            ["planId"])),
    "plan_solve": ("plans/solve", (
        "Solve a draft with the site's solver (uses the constraints given at plan_create): solved, targetsMet, "
        "targets (made vs rate), unsupplied (raw inputs per second, largest first), byproducts, euT, warnings, then "
        "cards as ROWS laid out by cardColumns: [cardId, recipe, machine, tier, count, in, out, dials?] (count is "
        "the machines needed, fractional; in/out are per second by resource name)."),
        schema(dict(PLAN, fields={"type": "array", "items": {"type": "string", "enum": [
            "targets", "unsupplied", "byproducts", "power", "warnings", "cards"]},
            "description": "Only these parts (solved and targetsMet always come)."}), ["planId"])),
    "plan_expand_inputs": ("plans/expand-inputs", (
        "What could make each input a draft still needs (it must have a target): up to max candidates per input, "
        "each actually tried in a copy of the draft and solved, with machines, euPerItem, newRaws, "
        "usesByproducts, works and a plain reason. Inputs marked raw: true may stay unsupplied; accepted lists "
        "the free ones. Up to 6 inputs per call: more: true and remaining mean page on with resources. The rank "
        "ignores leftover byproducts (named in reason), calls some never-spawning ore materials raw "
        "and sees only the first 12 recipes per input: judge it, don't just take the top one. "
        "Add your choice with plan_add_cards and its recipeId. constraints apply to this call only."),
        schema(dict(PLAN, resources={"type": "array", "items": RESOURCE, "maxItems": 16}, constraints=CONSTRAINTS,
                    allowSteam=ALLOW_STEAM, max={"type": "integer", "minimum": 1, "maximum": 3}), ["planId"])),
    "plan_diff": ("plans/diff", (
        "For a draft made by plan_import or plan_import_current: store what it ADDS to the player's plan as a "
        "proposal; returns {proposalId, basePlanId, summary, text}. The player applies it in game (/gtnhplanner "
        "apply <proposalId>, or the panel's Apply with proposalId in your <data> block), as one undoable step that "
        "only adds. Add-only: if the draft removed or changed anything the player's plan had, it answers "
        "bad_request naming what; then tell the player that change in words instead. A draft that adds nothing is "
        "bad_request too."), schema(PLAN, ["planId"])),
    "plan_deliver": ("plans/deliver", (
        "Store a finished draft for the player as a NEW plan (kept 30 days); returns {name, id, cardCount, "
        "expiresAt, link?}. Give the player the id: they open it in game with /gtnhplanner open <id>; link (when "
        "present) opens it on the site. The plan code and "
        "its site link run to thousands of characters, so they are left out unless short. It never changes the "
        "player's own designs and is not a community post. player needs name and uuid (the asker's)."),
        dict(schema(PLAN, ["planId", "player"]), properties=dict(PLAN, player=dict(
            PLAYER, required=["name", "uuid"])))),
}

IMPORT_CURRENT = {
    "name": "plan_import_current",
    "description": (
        "Import the plan the player attached to this turn's GTNH Planner panel ask as a draft; returns {planId, "
        "cardCount, wireCount, warnings} and which ask the attachment came from. Then change the draft and use plan_diff for a proposal. turn picks "
        "another turn's attachment."),
    "inputSchema": schema({"turn": {"type": "string", "description": "A turn id (t-0123456789); default: this "
                                                                     "turn, else the asker's newest."}}),
}

TOOLS = [{"name": n, "description": d, "inputSchema": s} for n, (_, d, s) in ENDPOINTS.items()]
TOOLS.insert(list(ENDPOINTS).index("plan_import") + 1, IMPORT_CURRENT)


def log(msg):
    sys.stderr.write("[planner] %s\n" % msg)
    sys.stderr.flush()


class PlannerError(Exception):
    """A tool failure whose message is safe to hand to Dave."""


def api_card(card):
    """A tool card -> the card plans/add-cards takes.

    Proposed to the site: {recipeId, machine?, tier?, dials?: {dialId: value}} with dials nested. If the site settles
    on the spec table's spread form ({recipeId, machine?, tier?, ...dials}), merge the dials into `out` instead.
    """
    if not isinstance(card, dict) or not isinstance(card.get("recipeId"), str):
        raise PlannerError("each card needs a recipeId (from recipes_search)")
    out = {k: card[k] for k in ("recipeId", "machine", "tier") if card.get(k) is not None}
    dials = card.get("dials")
    if dials:
        if not isinstance(dials, dict) or any(isinstance(v, bool) or not isinstance(v, (str, int, float))
                                              for v in dials.values()):
            raise PlannerError("dials must be an object of {dialId: option key or number}")
        out["dials"] = dict(dials)
    extra = sorted(set(card) - {"recipeId", "machine", "tier", "dials"})
    if extra:
        raise PlannerError("unknown card field(s): %s (machine settings go in dials)" % ", ".join(extra))
    return out


def clean(value):
    """Drops null members (an omitted optional), so the API's strict schemas don't refuse them."""
    if isinstance(value, dict):
        return {k: clean(v) for k, v in value.items() if v is not None}
    if isinstance(value, list):
        return [clean(v) for v in value]
    return value


LONG_FIELD = 300  # deliver's plan code and site link: thousands of characters Dave should never paste into chat


def shrink_delivery(resp):
    """plans/deliver: drop a long plan code and site link (the short id is what the player needs)."""
    if isinstance(resp, dict):
        for k in ("code", "url"):
            if isinstance(resp.get(k), str) and len(resp[k]) > LONG_FIELD:
                resp[k] = "(left out: %d characters; give the player the id)" % len(resp[k])
    return resp


def compact(obj):
    return json.dumps(obj, ensure_ascii=False, separators=(",", ":"))


def fit(text):
    if len(text) <= MAX_TEXT:
        return text
    return ("[cut: the answer is %d characters; only the first %d follow. Ask again with a smaller limit, a later "
            "offset, or without detail.]\n" % (len(text), MAX_TEXT)) + text[:MAX_TEXT]


class _NoRedirect(urllib.request.HTTPRedirectHandler):
    def redirect_request(self, *args, **kwargs):     # never carry the token to another location
        return None


OPENER = urllib.request.build_opener(urllib.request.ProxyHandler({}), _NoRedirect())


def error_text(endpoint, status, body):
    """The tool error for a non-2xx answer."""
    try:
        err = json.loads(body.decode("utf-8")).get("error")
    except (ValueError, UnicodeDecodeError, AttributeError):
        err = None
    if isinstance(err, dict) and err.get("code"):
        return "planner error %s: %s" % (err.get("code"), err.get("message") or "(no message)")
    if status == 404:
        return "%s is not available on the planner site yet (or the agent API is off)." % endpoint
    snippet = body.decode("utf-8", "replace").strip()[:200]
    return "the planner site answered HTTP %s%s" % (status, (": " + snippet) if snippet else "")


class Service:
    def __init__(self, env=None, opener=OPENER):
        self.env = os.environ if env is None else env
        self.opener = opener
        self.draft_steam = {}   # planId -> whether plan_create allowed steam machines (tools-side; see STEAM_MACHINES)

    def settings(self):
        """(api base, token), read at call time; raises PlannerError when either is missing."""
        url = (self.env.get("PLANNER_API_URL") or "").strip().rstrip("/")
        token_file = (self.env.get("PLANNER_API_TOKEN_FILE") or "").strip()
        if not url or not token_file:
            raise PlannerError("the planner is not configured (%s is not set)" % (
                "PLANNER_API_URL" if not url else "PLANNER_API_TOKEN_FILE"))
        if not url.startswith(("http://", "https://")):
            raise PlannerError("the planner is not configured (PLANNER_API_URL must start with http:// or https://)")
        try:
            with open(token_file, encoding="utf-8") as f:
                token = f.read().strip()
        except OSError:
            token = ""
        if not token:
            raise PlannerError("the planner is not configured (the token file is missing or empty)")
        if url.endswith(API_PATH.rstrip("/")):
            url = url[:-len(API_PATH.rstrip("/"))]
        return url + API_PATH, token

    def post(self, endpoint, body):
        base, token = self.settings()
        data = json.dumps(body, ensure_ascii=False, separators=(",", ":")).encode("utf-8")
        limit = MAX_BODY_BY_ENDPOINT.get(endpoint, MAX_BODY)
        if len(data) > limit:
            raise PlannerError("the request is %d bytes; the planner's agent API takes at most %d here" % (
                len(data), limit))
        req = urllib.request.Request(base + endpoint, data=data, method="POST", headers={
            "Content-Type": "application/json", "Accept": "application/json", "Authorization": "Bearer " + token})
        timeout = SLOW.get(endpoint, TIMEOUT)
        t0 = time.monotonic()
        outcome = "?"
        try:
            try:
                with self.opener.open(req, timeout=timeout) as resp:
                    raw, outcome = resp.read(), resp.status
            except urllib.error.HTTPError as e:
                try:
                    raw = e.read() or b""
                except Exception:                    # noqa: BLE001 - no body
                    raw = b""
                finally:
                    e.close()
                outcome = e.code
                raise PlannerError(error_text(endpoint, e.code, raw))
            except (TimeoutError, socket.timeout):
                outcome = "timeout"
                raise PlannerError("planner site unreachable: no answer within %d s" % timeout)
            except urllib.error.URLError as e:
                if isinstance(e.reason, (TimeoutError, socket.timeout)):
                    outcome = "timeout"
                    raise PlannerError("planner site unreachable: no answer within %d s" % timeout)
                outcome = "unreachable"
                raise PlannerError("planner site unreachable: %s" % (e.reason,))
            except (ConnectionError, OSError) as e:
                outcome = "unreachable"
                raise PlannerError("planner site unreachable: %s" % (e.strerror or type(e).__name__))
        finally:
            log("%s -> %s in %d ms" % (endpoint, outcome, (time.monotonic() - t0) * 1000))
        try:
            return json.loads(raw.decode("utf-8"))
        except (ValueError, UnicodeDecodeError):
            raise PlannerError("the planner site sent an answer that is not JSON")

    # ---- the attached plan

    def attachment(self, turn):
        """The daemon's surface_attachment answer for this turn (dave/daemon/rpc.py)."""
        path = self.env.get("DAVE_BRIDGE_SOCKET")
        if not path or not os.path.exists(path):
            raise PlannerError("cannot find this turn's attachment: the Dave daemon is not reachable")
        args = {"turn": turn} if turn else {}
        s = socket.socket(socket.AF_UNIX, socket.SOCK_STREAM)
        s.settimeout(DAEMON_TIMEOUT)
        try:
            s.connect(path)
            s.sendall((json.dumps({"id": 1, "op": "surface_attachment", "args": args}) + "\n").encode("utf-8"))
            buf = b""
            while not buf.endswith(b"\n"):
                chunk = s.recv(65536)
                if not chunk:
                    break
                buf += chunk
        except OSError as e:
            raise PlannerError("cannot find this turn's attachment: the Dave daemon did not answer (%s)" % (
                e.strerror or type(e).__name__))
        finally:
            s.close()
        try:
            reply = json.loads(buf)
        except ValueError:
            raise PlannerError("cannot find this turn's attachment: the Dave daemon sent no answer")
        if not reply.get("ok"):
            raise PlannerError("no plan is attached to this turn (%s). The player attaches their open plan by "
                               "asking from the GTNH Planner panel." % (reply.get("error") or "unknown error"))
        return reply["result"]

    def import_current(self, args):
        self.settings()                              # "not configured" before anything else
        meta = self.attachment(args.get("turn"))
        try:
            with open(meta["path"], encoding="utf-8") as f:
                snapshot = json.load(f)
        except (OSError, ValueError, KeyError, TypeError):
            raise PlannerError("the attached plan could not be read (it may be gone: only the newest 50 are kept)")
        body = {"snapshot": snapshot}
        if args.get("player"):
            body["player"] = args["player"]
        result = self.post("plans/import", clean(body))
        source = {k: meta.get(k) for k in ("turn", "surface", "bytes", "earlier") if meta.get(k) is not None}
        return dict(result, attachment=source) if isinstance(result, dict) else result

    # ---- dispatch

    def call(self, name, args):
        """Returns the tool's text; raises PlannerError for a tool error."""
        if name == IMPORT_CURRENT["name"]:
            return fit(compact(self.import_current(args)))
        if name not in ENDPOINTS:
            raise PlannerError("unknown tool: %s" % name)
        endpoint, _, sch = ENDPOINTS[name]
        self.settings()
        missing = [k for k in sch["required"] if args.get(k) is None]
        if missing:
            raise PlannerError("missing argument(s): %s" % ", ".join(missing))
        body = clean(args)
        steam = None
        if name in STEAM_TOOLS:
            body, steam = apply_steam_rule(body, self.draft_steam.get(body.get("planId")))
        if name == "plan_add_cards":
            if not isinstance(body.get("cards"), list):
                raise PlannerError("cards must be a list")
            body["cards"] = [api_card(c) for c in body["cards"]]
        resp = self.post(endpoint, body)
        if name == "plan_create" and isinstance(resp, dict) and isinstance(resp.get("planId"), str):
            self.draft_steam[resp["planId"]] = steam
        if name == "plan_deliver":
            resp = shrink_delivery(resp)
        return fit(compact(resp))


def handle(service, msg):
    """Returns the JSON-RPC response for one message, or None for notifications."""
    if not isinstance(msg, dict):
        return {"jsonrpc": "2.0", "id": None, "error": {"code": -32600, "message": "invalid request: expected an object"}}
    method, mid, params = msg.get("method"), msg.get("id"), msg.get("params")
    params = params if isinstance(params, dict) else {}
    if mid is None:
        return None
    if method == "initialize":
        result = {"protocolVersion": params.get("protocolVersion", PROTOCOL), "capabilities": {"tools": {}},
                  "serverInfo": {"name": "planner", "version": "1.0.0"}, "instructions": INSTRUCTIONS}
    elif method == "ping":
        result = {}
    elif method == "tools/list":
        result = {"tools": TOOLS}
    elif method == "tools/call":
        args = params.get("arguments")
        try:
            text, is_error = service.call(params.get("name"), args if isinstance(args, dict) else {}), False
        except PlannerError as e:
            text, is_error = str(e), True
        except Exception as e:                      # noqa: BLE001 - every failure goes back to Dave
            log(traceback.format_exc())
            text, is_error = "error: %s" % type(e).__name__, True
        result = {"content": [{"type": "text", "text": text}], "isError": is_error}
    else:
        return {"jsonrpc": "2.0", "id": mid, "error": {"code": -32601, "message": "method not found: %s" % method}}
    return {"jsonrpc": "2.0", "id": mid, "result": result}


def serve(service, stdin, stdout, workers=CALL_WORKERS):
    """One JSON-RPC message per line; tools/call runs on a worker pool (replies matched by id)."""
    out_lock = threading.Lock()

    def reply(msg):
        try:
            r = handle(service, msg)
        except Exception as e:                      # noqa: BLE001
            log(traceback.format_exc())
            r = {"jsonrpc": "2.0", "id": msg.get("id") if isinstance(msg, dict) else None,
                 "error": {"code": -32603, "message": "internal error: %s" % type(e).__name__}}
        if r is not None:
            with out_lock:
                stdout.write(json.dumps(r) + "\n")
                stdout.flush()

    with ThreadPoolExecutor(workers) as pool:
        for line in stdin:
            line = line.strip()
            if not line:
                continue
            try:
                msg = json.loads(line)
            except ValueError:
                log("ignored a line that is not JSON")
                continue
            if isinstance(msg, dict) and msg.get("method") == "tools/call" and msg.get("id") is not None:
                pool.submit(reply, msg)
            else:
                reply(msg)


def main():
    service = Service()
    try:
        service.settings()
        log("agent API configured")
    except PlannerError as e:
        log(str(e))
    serve(service, sys.stdin, sys.stdout)


if __name__ == "__main__":
    main()
