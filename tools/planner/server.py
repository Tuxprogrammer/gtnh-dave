"""MCP server "planner" (stdio, JSON-RPC 2.0): the GTNH planner website's agent API for Dave. Standard library only.

  python3 -I tools/planner/server.py

One tool per agent API endpoint (spec: https://github.com/Tuxprogrammer/gtnh-factory-flow/blob/main/docs/agent-api.md),
plus plan_import_current, which imports the plan the player attached to this turn's panel ask, and plan_add_chain,
which builds a chain from the recipes server's plan_chain (its PLANNER-CHAIN export) into a draft by matching each
step to the planner's own recipe by content. Configuration comes from the environment, read at call time:

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
                   "means 'treat as raw' when expanding and solving; steam: true lets steam and primitive "
                   "machines in (default: only when tierCap is ULV; steam isn't free, so leave it off past the steam "
                   "age). cleanroom and lowgrav are not enforced by search (the answer lists them in notEnforced).",
    "properties": {"tierCap": TIER, "heat": {"type": "number"}, "cleanroom": {"type": "boolean"},
                   "lowgrav": {"type": "boolean"}, "steam": {"type": "boolean"},
                   "banned": {"type": "array", "items": {"type": "string"}},
                   "free": {"type": "array", "items": {"type": "string"}}},
}
LIMIT = {"type": "integer", "minimum": 1, "maximum": 25, "default": 8}


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
    "banned keeps machines and recipes out, free marks resources as raw on purpose, steam: true lets steam and "
    "primitive machines in (default: only at tierCap ULV). heat, cleanroom and lowgrav do nothing to a draft."))


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
                "constraints": CONSTRAINTS, "limit": LIMIT,
                "offset": {"type": "integer", "minimum": 0, "maximum": 1000, "default": 0},
                "detail": {"type": "boolean"}})),
    "recipes_get": ("recipes/get", (
        "One planner recipe in full: inputs with alternatives, outputs, and every machine that runs it with its "
        "tiers, parallels, overclock and dials (the settings plan_add_cards takes)."),
        schema({"recipeId": ID}, ["recipeId"])),
    "plan_create": ("plans/create", (
        "Start an empty draft plan on the planner site; returns {planId, name, expiresAt, constraints, cardTier}. "
        "The draft expires 24 h after its last change and a site restart drops it. Set the constraints and "
        "cardTier here: plan_solve takes none. A card added without a tier runs at the highest tier at or below "
        "cardTier its machine is built at."), schema({"name": {"type": "string"}, "constraints": DRAFT_CONSTRAINTS,
                                                        "cardTier": dict(TIER, description=(
                                                            "The tier cards run at unless given one (default: "
                                                            "tierCap): the player's own tier."))},
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
        "automatically, both ways. Machine defaults to the recipe's first multiblock within tierCap (never a steam "
        "or primitive one unless the draft allows steam) and tier to the draft's cardTier; the solver picks machine "
        "counts. At most 60 cards per draft; a refused request changes nothing."),
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
        "the machines needed, fractional; in/out are per second by resource name). Steam is not in unsupplied or "
        "euT: a warning line gives the steam the plan's steam machines burn."),
        schema(dict(PLAN, fields={"type": "array", "items": {"type": "string", "enum": [
            "targets", "unsupplied", "byproducts", "power", "warnings", "cards"]},
            "description": "Only these parts (solved and targetsMet always come)."}), ["planId"])),
    "plan_expand_inputs": ("plans/expand-inputs", (
        "What could make each input a draft still needs (it must have a target): up to max candidates per input, "
        "each actually tried in a copy of the draft and solved, with machines, euPerItem, newRaws, "
        "usesByproducts, works, steamPerSecond (steam counted at 2 L per EU in euPerItem) and a plain reason. Inputs "
        "marked raw: true may stay unsupplied; accepted lists "
        "the free ones. Up to 6 inputs per call: more: true and remaining mean page on with resources. The rank "
        "ignores leftover byproducts (named in reason), calls some never-spawning ore materials raw "
        "and sees only the first 12 recipes per input: judge it, don't just take the top one. "
        "Add your choice with plan_add_cards and its recipeId. constraints apply to this call only."),
        schema(dict(PLAN, resources={"type": "array", "items": RESOURCE, "maxItems": 16}, constraints=CONSTRAINTS,
                    max={"type": "integer", "minimum": 1, "maximum": 3}), ["planId"])),
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
        "cardCount, wireCount, warnings} and which ask the attachment came from. Then change the draft and use plan_diff for a proposal. An ask "
        "without an attachment is refused rather than falling back to an older copy; turn picks another turn's attachment on purpose."),
    "inputSchema": schema({"turn": {"type": "string", "description": "A turn id (t-0123456789); default: this "
                                                                     "turn, else the asker's newest."}}),
}

ADD_CHAIN = {
    "name": "plan_add_chain",
    "description": (
        "Build a chain worked out by the recipes tool plan_chain (called with export: true) into a draft: each step "
        "of its PLANNER-CHAIN JSON is matched to the planner's own recipe by content (machine, EU/t, duration, "
        "consumed inputs, outputs: both datasets come from our pack) and added as a card at the draft's cardTier, "
        "then the chain's target becomes the draft's target (this replaces the draft's targets; set_target: false "
        "keeps them). Make the draft first with plan_create, using the chain's constraints (tierCap, free) and the "
        "player's tier as cardTier. Returns each matched step's recipeId and cardId, and each unmatched step with "
        "the planner's nearest candidates (add the right one with plan_add_cards). Then plan_solve."),
    "inputSchema": schema({
        "planId": ID,
        "chain": {"type": "object", "required": ["steps"],
                  "description": "The JSON object after 'PLANNER-CHAIN: ' in plan_chain's answer, unchanged.",
                  "properties": {"target": {"type": "object"}, "steps": {"type": "array", "items": {"type": "object"}},
                                 "constraints": {"type": "object"}}},
        "set_target": {"type": "boolean", "default": True}}, ["planId", "chain"]),
}
CHAIN_WORKERS = 4
CHAIN_MAX_STEPS = 40
SECONDS_TOL = 0.026        # one tick, and rounding

TOOLS = [{"name": n, "description": d, "inputSchema": s} for n, (_, d, s) in ENDPOINTS.items()]
TOOLS.insert(list(ENDPOINTS).index("plan_import") + 1, IMPORT_CURRENT)
TOOLS.insert(list(ENDPOINTS).index("plan_add_cards") + 2, ADD_CHAIN)


def log(msg):
    sys.stderr.write("[planner] %s\n" % msg)
    sys.stderr.flush()


class PlannerError(Exception):
    """A tool failure whose message is safe to hand to Dave."""


def _amounts(rows):
    out = {}
    for rid, amt in rows:
        out[rid] = out.get(rid, 0.0) + float(amt)
    return out


def _close(a, b):
    return abs(a - b) <= 1e-6 * max(1.0, abs(a), abs(b))


def chain_match(step, rec):
    """(True, None) when a planner recipe is the chain step's recipe, else (False, what differs). The step's consumed
    inputs must each be in the recipe (ore dictionary slots are left out of the export; the planner shows them as one
    member with alternatives), and the outputs must be the same."""
    if int(rec.get("euT") or 0) != int(step.get("euT") or 0):
        return False, "EU/t %s, not %s" % (rec.get("euT"), step.get("euT"))
    if abs(float(rec.get("seconds") or 0) - float(step.get("seconds") or 0)) > SECONDS_TOL:
        return False, "%ss, not %ss" % (rec.get("seconds"), step.get("seconds"))
    have = _amounts((o.get("id"), o.get("amount") or 0) for o in rec.get("outputs") or [])
    want = _amounts(step.get("out") or [])
    if set(have) != set(want) or not all(_close(have[k], want[k]) for k in want):
        return False, "other outputs"
    ins = {}
    for i in rec.get("inputs") or []:
        if i.get("consumed") is False:
            continue
        for rid in [i.get("id")] + [a.get("id") if isinstance(a, dict) else a for a in i.get("alternatives") or []]:
            ins[rid] = ins.get(rid, 0.0) + float(i.get("amount") or 0)
    for rid, amt in _amounts(step.get("in") or []).items():
        if rid not in ins or not _close(ins[rid], amt):
            return False, "other inputs"
    return True, None


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
        if meta.get("earlier") and not args.get("turn"):
            # This ask came without a plan (the panel leaves it out when it is too large). An older copy could name
            # cards the player has since removed, so it is only used when asked for by turn id.
            raise PlannerError("no plan is attached to this ask (the panel leaves it out when the plan is too large). "
                               "Don't work from an older copy: answer in words, or build a new plan (plan_create ... "
                               "plan_deliver) for the player to open with /gtnhplanner open <id>. Pass turn (%s) only "
                               "when the player asks for that earlier plan." % meta.get("turn"))
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

    # ---- a plan_chain chain

    def find_step(self, step, player):
        """The planner recipe for one chain step: (recipe, None) or (None, {why, candidates})."""
        first_in = next((i[0] for i in step.get("in") or []), None)
        base = {"makes": step.get("makes"), "limit": 25}
        tries = []
        if step.get("machine"):
            if first_in:
                tries.append(dict(base, machine=step["machine"], takes=first_in))
            tries.append(dict(base, machine=step["machine"]))
        if first_in:
            tries.append(dict(base, takes=first_in))
        if not tries:
            tries.append(base)
        seen, near, errors = set(), [], []
        for body in tries:
            if player:
                body["player"] = player
            try:
                resp = self.post("recipes/search", body)
            except PlannerError as e:
                errors.append(str(e))
                continue
            for rec in resp.get("results") or []:
                if rec.get("recipeId") in seen:
                    continue
                seen.add(rec.get("recipeId"))
                ok, why = chain_match(step, rec)
                if ok:
                    return rec, None
                near.append({"recipeId": rec.get("recipeId"), "name": rec.get("name"), "euT": rec.get("euT"),
                             "seconds": rec.get("seconds"), "differs": why})
        why = errors[0] if errors and not near else "no planner recipe has the same machine, EU/t, duration, " \
                                                    "inputs and outputs"
        return None, {"why": why, "candidates": near[:3]}

    def add_chain(self, args):
        self.settings()
        chain, plan_id, player = args.get("chain"), args.get("planId"), args.get("player")
        if not plan_id:
            raise PlannerError("missing argument(s): planId")
        steps = chain.get("steps") if isinstance(chain, dict) else None
        if not isinstance(steps, list) or not steps or not all(isinstance(x, dict) for x in steps):
            raise PlannerError("chain must be plan_chain's PLANNER-CHAIN object, with a list of steps")
        if len(steps) > CHAIN_MAX_STEPS:
            raise PlannerError("the chain has %d steps; at most %d go into one draft" % (len(steps), CHAIN_MAX_STEPS))
        with ThreadPoolExecutor(CHAIN_WORKERS) as pool:
            found = list(pool.map(lambda st: self.find_step(st, player), steps))
        matched, unmatched = [], []
        for n, (st, (rec, miss)) in enumerate(zip(steps, found), 1):
            if rec:
                matched.append((n, st, rec))
            else:
                unmatched.append(dict({"step": n, "recipe": st.get("recipe"), "machine": st.get("machine"),
                                       "makes": st.get("makes")}, **miss))
        added, uniq = [], {}
        for n, st, rec in matched:          # one card per planner recipe, even if two steps found the same one
            uniq.setdefault(rec["recipeId"], (n, st, rec))
        cards = [{"recipeId": rid} for rid in uniq]
        resp = None
        if cards:
            body = {"planId": plan_id, "cards": cards}
            if player:
                body["player"] = player
            try:
                resp = self.post("plans/add-cards", body)
                made = resp.get("cards") or []
            except PlannerError as e:
                if not str(e).startswith("planner error bad_request"):
                    raise
                made = []                    # one card refused the whole batch: add them one by one
                for c in cards:
                    try:
                        r1 = self.post("plans/add-cards", dict(body, cards=[c]))
                        made += r1.get("cards") or []
                        resp = r1
                    except PlannerError as e1:
                        n, st, _ = uniq[c["recipeId"]]
                        unmatched.append({"step": n, "recipe": st.get("recipe"), "machine": st.get("machine"),
                                          "makes": st.get("makes"), "why": str(e1), "candidates": []})
            by_recipe = {}
            for c in made:
                by_recipe.setdefault(c.get("recipeId"), c)
            for rid, (n, st, rec) in uniq.items():
                c = by_recipe.get(rid)
                if c:
                    added.append({"step": n, "recipe": st.get("recipe"), "recipeId": rid, "cardId": c.get("cardId"),
                                  "name": c.get("name") or rec.get("name"), "tier": c.get("tier")})
        out = {"planId": plan_id, "steps": len(steps), "added": added,
               "unmatched": sorted(unmatched, key=lambda u: u["step"])}
        if resp and resp.get("cardCount") is not None:
            out["cardCount"] = resp["cardCount"]
        target = chain.get("target") if isinstance(chain.get("target"), dict) else None
        if args.get("set_target", True) is not False and target and target.get("id") and added:
            body = {"planId": plan_id, "targets": [{"resource": target["id"], "rate": float(target.get("rate") or 1)}]}
            if player:
                body["player"] = player
            try:
                out["targets"] = self.post("plans/set-targets", body).get("targets")
            except PlannerError as e:
                out["targetError"] = str(e)
        return out

    # ---- dispatch

    def call(self, name, args):
        """Returns the tool's text; raises PlannerError for a tool error."""
        if name == IMPORT_CURRENT["name"]:
            return fit(compact(self.import_current(args)))
        if name == ADD_CHAIN["name"]:
            return fit(compact(self.add_chain(args)))
        if name not in ENDPOINTS:
            raise PlannerError("unknown tool: %s" % name)
        endpoint, _, sch = ENDPOINTS[name]
        self.settings()
        missing = [k for k in sch["required"] if args.get(k) is None]
        if missing:
            raise PlannerError("missing argument(s): %s" % ", ".join(missing))
        body = clean(args)
        if name == "plan_add_cards":
            if not isinstance(body.get("cards"), list):
                raise PlannerError("cards must be a list")
            body["cards"] = [api_card(c) for c in body["cards"]]
        resp = self.post(endpoint, body)
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
