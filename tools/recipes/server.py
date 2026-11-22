"""MCP server (stdio, JSON-RPC 2.0) giving Dave exact recipe facts from OUR pack and the chain planner.

  python3 -I tools/recipes/server.py [--db PATH]

The database is $DAVE_RECIPES_DB (production: the read-only copy baked into the image, e.g.
/dave/ro/recipes.sqlite), else $DAVE_ROOT/data/recipes.sqlite, else <repo>/data/recipes.sqlite. Build it with
tools/recipe-export/run.sh. Standard library only, except plan_chain, which needs highspy and numpy
(requirements.txt); without them the other tools still work and plan_chain says what is missing. A rebuilt database
file is picked up between calls.
"""

import json
import sys
import threading
import traceback
from concurrent.futures import ThreadPoolExecutor
from pathlib import Path

sys.path.insert(0, str(Path(__file__).resolve().parent))
import recipedb as R  # noqa: E402

CALL_WORKERS = 4
MAX_LIMIT = 40

INSTRUCTIONS = (
    "Exact recipe facts from this server's own pack export (GTNH 2.9.0 plus our mods, GregGuns included): every "
    "GregTech machine recipe, crafting table, furnace and Thaumcraft recipe, with EU/t, duration, voltage tier, "
    "programmed circuit, cleanroom, low gravity, coil heat, chanced outputs and non-consumed inputs. Use these tools "
    "whenever you quote a recipe; never guess one. Item arguments take an id from item_find or gtnh_search "
    "(gregtech:gt.metaitem.01@11305, fluid:nitrogen) or an exact in-game name. Recipe ids (#hash) are stable for this "
    "pack version. plan_chain picks the best chain (least EU per item, then cheapest raw resources, then fewest "
    "machines) with locks, free inputs and bans, gives the runner-up and why it lost, and counts machines both at "
    "base speed and overclocked at the player's tier. item_cost answers 'what's the best source of X' as a ranked "
    "lookup: what one unit costs at a tier and the cheapest recipes that make it, each with its cost broken down."
)

ITEM = {"type": "string", "description": "Item or fluid: an id (gregtech:gt.metaitem.03@32075, fluid:nitrogen) or "
                                         "its exact in-game name ('NAND Chip', 'Nitrogen')."}
FILTERS = {
    "tier": {"type": "string", "description": "Only recipes this voltage tier can run: ULV, LV, MV, HV, EV, IV, LuV, "
                                              "ZPM, UV, UHV, ... (omit for all)."},
    "heat": {"type": "string", "description": "Coil heat available, in kelvin or a coil name (cupronickel, kanthal, "
                                              "nichrome, ...). Hides recipes that need hotter coils."},
    "cleanroom": {"type": "boolean", "description": "false hides recipes that need a cleanroom."},
    "machine": {"type": "string", "description": "Only this machine or recipe map (e.g. 'Laser Engraver', "
                                                 "'gt.recipe.laserengraver')."},
    "limit": {"type": "integer", "minimum": 1, "maximum": MAX_LIMIT, "default": 12},
    "offset": {"type": "integer", "minimum": 0, "default": 0, "description": "Skip this many (paging)."},
    "include_fake": {"type": "boolean", "default": False,
                     "description": "Also show display-only (NEI) recipes such as scanner or research entries."},
}

TOOLS = [
    {
        "name": "recipe_producers",
        "description": "Recipes that MAKE an item or fluid: machine, voltage tier, EU/t, seconds, total EU, "
                       "programmed circuit, cleanroom, coil heat, every input and output with amounts and chances. "
                       "Real production first, recycling last, then cheapest EU per unit. Filter by tier, heat, "
                       "cleanroom or machine.",
        "inputSchema": {"type": "object", "properties": dict(item=ITEM, **FILTERS), "required": ["item"]},
    },
    {
        "name": "recipe_uses",
        "description": "Recipes that CONSUME (or need, unconsumed) an item or fluid, same format and filters as "
                       "recipe_producers.",
        "inputSchema": {"type": "object", "properties": dict(item=ITEM, **FILTERS), "required": ["item"]},
    },
    {
        "name": "recipe_get",
        "description": "One recipe in full by its id (the #hash from the other tools), with its raw GregTech "
                       "metadata (special value, fusion start EU, research, ...).",
        "inputSchema": {"type": "object", "properties": {"id": {"type": "string", "description": "Recipe #hash."}},
                        "required": ["id"]},
    },
    {
        "name": "item_find",
        "description": "Find items and fluids by name (exact first, then word-prefix full-text search): ids, kinds, "
                       "mods, ore dictionary names and how many recipes make and use each. A cheap exact-name "
                       "fallback beside gtnh_search.",
        "inputSchema": {"type": "object", "properties": {
            "name": {"type": "string"},
            "k": {"type": "integer", "minimum": 1, "maximum": 30, "default": 10},
            "kind": {"type": "string", "enum": ["item", "fluid", "essentia", "oredict"], "description": "Optional."}},
            "required": ["name"]},
    },
    {
        "name": "plan_chain",
        "description": "Best production chain for an item: an LP over the recipe graph (HiGHS), least EU per item "
                       "first (raw resources priced by class: free, cheap, costly), then raw-resource cost, machine "
                       "count and flow. Returns the chosen recipes with runs/s and machine counts, EU/t total, EU per "
                       "item, imports, byproducts, and the runner-up route with why it lost.",
        "inputSchema": {"type": "object", "properties": {
            "target": ITEM,
            "rate": {"type": "number", "description": "Wanted output per second (items, or litres for a fluid). "
                                                      "Default 1 item/s or 1000 L/s."},
            "tier": FILTERS["tier"], "heat": FILTERS["heat"],
            "cleanroom": {"type": "boolean", "default": True, "description": "false bans cleanroom recipes."},
            "lowgrav": {"type": "boolean", "default": False, "description": "true allows low-gravity recipes."},
            "locks": {"type": "array", "items": {"type": "string"},
                      "description": "Recipe #hashes (must run, sole supplier of their outputs) or machine names "
                                     "(one of its recipes must run), e.g. 'Vacuum Freezer'."},
            "free": {"type": "array", "items": {"type": "string"},
                     "description": "Inputs the player has plenty of: imported at no cost, not produced."},
            "banned": {"type": "array", "items": {"type": "string"},
                       "description": "Recipe #hashes, machines, mods ('Thaumcraft') or items (no import)."},
            "max_depth": {"type": "integer", "minimum": 1, "maximum": 15, "default": 8},
            "runner_up": {"type": "boolean", "default": True},
            "oc_tier": {"type": "string",
                        "description": "Tier the machines run at for the overclocked counts (GregTech's own "
                                       "overclock results for this server). Default: tier. 'none' for base speed "
                                       "only."},
            "export": {"type": "boolean", "default": False,
                       "description": "Also return the chain as PLANNER-CHAIN JSON, for the planner tool "
                                      "plan_add_chain (builds it as cards on the planner site)."}},
            "required": ["target"]},
    },
    {
        "name": "item_cost",
        "description": "What one unit of an item or fluid costs to make on its own at a voltage tier, in EU (machine "
                       "EU, with raw resources priced as plan_chain prices them: free 0, cheap 2,000, costly 100,000 "
                       "per item or 1,000 L), and the cheapest recipes that make it, ranked, each broken down into "
                       "machine EU and inputs at their own costs. 'Best source of X' as one lookup, and why one "
                       "route beats another. Byproducts are not credited; for a full chain with byproduct reuse, "
                       "locks and machine counts use plan_chain.",
        "inputSchema": {"type": "object", "properties": {
            "item": ITEM,
            "tier": {"type": "string", "description": "Player's tier (ULV ... UXV). Default: the highest computed."},
            "limit": {"type": "integer", "minimum": 1, "maximum": 12, "default": 5}},
            "required": ["item"]},
    },
]


def log(msg):
    print("[recipes] " + msg, file=sys.stderr, flush=True)


class Service:
    def __init__(self, path=None):
        self.path = Path(path) if path else R.default_db_path()
        self.local = threading.local()

    def con(self):
        """One read-only connection per worker thread, reopened when the database file is replaced."""
        try:
            mtime = self.path.stat().st_mtime_ns
        except OSError:
            raise FileNotFoundError("recipe database not found at %s (build it with tools/recipe-export/run.sh)"
                                    % self.path)
        c = getattr(self.local, "con", None)
        if c is None or self.local.mtime != mtime:
            if c is not None:
                c.close()
            self.local.con, self.local.mtime = R.connect(self.path), mtime
        return self.local.con

    def filters(self, args):
        tier = R.parse_tier(args.get("tier"))
        f = {"tier": tier, "heat": R.parse_heat(args.get("heat"), tier),
             "include_fake": bool(args.get("include_fake")), "machine": args.get("machine") or None}
        if args.get("cleanroom") is False:
            f["cleanroom"] = False
        return f

    def touching(self, args, side):
        con = self.con()
        iid, err = R.resolve(con, args.get("item"))
        if err:
            return err, True
        limit = max(1, min(MAX_LIMIT, int(args.get("limit") or 12)))
        offset = max(0, int(args.get("offset") or 0))
        rows, total = R.recipes_touching(con, iid, "out" if side == "producers" else "in", limit, offset,
                                         **self.filters(args))
        info = R.item(con, iid)
        head = "%s (%s): %d recipe(s) %s it%s" % (
            info["name"], iid, total, "make" if side == "producers" else "use",
            "" if total == len(rows) else "; showing %d-%d" % (offset + 1, offset + len(rows)))
        if not rows:
            hint = " Try without filters." if total == 0 and any(args.get(k) for k in FILTERS) else ""
            raw = con.execute("SELECT class, note FROM raw_resources WHERE item_id = ?", (iid,)).fetchone()
            if side == "producers" and raw:
                hint += " It is a raw resource (%s: %s)." % (raw["class"], raw["note"])
            return head + "." + hint, False
        names = {}
        return head + ":\n" + "\n".join(R.format_recipe(con, r, names) for r in rows), False

    def call(self, name, args):
        if name in ("recipe_producers", "recipe_uses"):
            return self.touching(args, "producers" if name == "recipe_producers" else "uses")
        if name == "recipe_get":
            con = self.con()
            r = R.recipe_by_ref(con, args.get("id") or "")
            if not r:
                return "no recipe with id %r" % args.get("id"), True
            text = R.format_recipe(con, r)
            text += "\n  map: %s (%s); source: %s" % (r["map"], r["machine"], r["source"])
            if r["special"]:
                text += "\n  special: " + r["special"]
            machines = con.execute("SELECT name, tier, multiblock FROM machines WHERE map = ? ORDER BY multiblock, "
                                   "tier", (r["map"],)).fetchall()
            if machines:
                text += "\n  machines: " + ", ".join(
                    m["name"] + ("" if m["multiblock"] or m["tier"] is None else " (%s)" % R.tier_name(m["tier"]))
                    for m in machines[:12])
            return text, False
        if name == "item_find":
            con = self.con()
            k = max(1, min(30, int(args.get("k") or 10)))
            hits = R.find(con, args.get("name"), k, args.get("kind"))
            if not hits:
                return "no item or fluid matches %r" % args.get("name"), False
            return "\n".join("%s | %s | %s%s | made by %d, used in %d%s" % (
                h["id"], h["name"], h["kind"], (" | " + h["mod"]) if h["mod"] else "", h["producers"], h["uses"],
                (" | ore: " + ", ".join(json.loads(h["oredict"])[:4])) if h.get("oredict") else "") for h in hits), False
        if name == "plan_chain":
            import planner
            a = args
            return planner.plan_chain(self.con(), a.get("target"), a.get("rate"), a.get("tier"), a.get("heat"),
                                      a.get("cleanroom", True), a.get("lowgrav", False), a.get("locks") or (),
                                      a.get("free") or (), a.get("banned") or (), int(a.get("max_depth") or 8),
                                      runner_up=a.get("runner_up", True) is not False, oc_tier=a.get("oc_tier"),
                                      export=bool(a.get("export")))
        if name == "item_cost":
            return self.item_cost(args)
        return "unknown tool: %s" % name, True

    def item_cost(self, args):
        con = self.con()
        if not R.has_costs(con):
            return ("item costs are not in this recipe database (run tools/recipes/item_costs.py on it); use "
                    "recipe_producers or plan_chain instead"), True
        iid, err = R.resolve(con, args.get("item"))
        if err:
            return err, True
        tiers = R.cost_tiers(con)
        tier = R.parse_tier(args.get("tier"))
        label = R.tier_name(tier) if tier is not None else "any tier (%s)" % R.tier_name(tiers[-1])
        if tier is None:
            tier = tiers[-1]
        elif tier not in tiers:
            tier = max([t for t in tiers if t <= tier] or [tiers[0]])
        limit = max(1, min(12, int(args.get("limit") or 5)))
        names = {}
        name = R.item_name(con, iid, names)
        mul, unit = R.unit_of(iid)
        info = R.meta(con).get("item_costs") or {}
        heat = (info.get("heat_by_tier") or {}).get(R.tier_name(tier)) or 0
        row = con.execute("SELECT cost, via, bound FROM item_costs WHERE tier = ? AND item_id = ?",
                          (tier, iid)).fetchone()
        if row is None:
            first = con.execute("SELECT MIN(tier) FROM item_costs WHERE item_id = ?", (iid,)).fetchone()[0]
            return "%s (%s) can't be made at %s%s." % (
                name, iid, label, "; it can from %s" % R.tier_name(first) if first is not None else
                "; no tier up to %s makes it" % R.tier_name(tiers[-1])), False
        lines = ["%s (%s) at %s: %s EU per %s, made on its own (machine EU plus raw resources priced by class; "
                 "byproducts not credited)." % (name, iid, label, R.fmt_num(round(row["cost"] * mul, 2)), unit)]
        raw = con.execute("SELECT class, note FROM raw_resources WHERE item_id = ?", (iid,)).fetchone()
        if row["bound"]:
            lines.append("That is its import price: %s." % (
                "raw resource, %s (%s)" % (raw["class"], raw["note"]) if raw else
                "nothing makes it" if row["bound"] == "no recipe" else row["bound"]))
        srcs, total = R.cost_sources(con, iid, tier, heat, limit)
        if srcs:
            lines.append("Cheapest recipes that make it at %s (%d in all), per %s:" % (R.tier_name(tier), total, unit))
        for n, sv in enumerate(srcs, 1):
            r = sv["recipe"]
            ins = sorted(sv["inputs"], key=lambda t: -(t[1] * (t[2] or 0)))
            parts = []
            for i, amt, c in ins[:4]:
                each = amt / sv["made"] * mul
                parts.append("%s %s (%s)" % (R.fmt_amount(i, each), R.item_name(con, i, names),
                                             "can't be made at this tier" if c is None else
                                             "%s EU" % R.fmt_num(round(amt * c / sv["made"] * mul, 2))))
            more = " +%d more" % (len(ins) - 4) if len(ins) > 4 else ""
            head = "%s EU" % R.fmt_num(round(sv["per_unit"] * mul, 2)) if sv["per_unit"] is not None else \
                "not possible here"
            also = ", ".join(R.item_name(con, i, names) for i, _ in sv["others"][:3])
            lines.append("%d. %s: #%s %s (%s) = machine %s EU + inputs: %s%s%s" % (
                n, head, r["hash"], r["machine"] or r["map"], R.recipe_flags(r),
                R.fmt_num(round(sv["machine"] * mul, 2)), "; ".join(parts) or "none", more,
                (" | also makes " + also + (" ..." if len(sv["others"]) > 3 else "")) if also else ""))
        if not srcs and not row["bound"]:
            lines.append("No recipe makes it at this tier.")
        lines.append("Costs assume the tier's usual EBF coils (%s K at %s), cleanroom yes, no low gravity." % (
            R.fmt_num(heat), R.tier_name(tier)))
        return "\n".join(lines), False

    def status_line(self):
        try:
            m = R.meta(self.con())
            return "recipes: pack %s, exported %s, %s recipes" % (m.get("pack_version"), m.get("exported_at"),
                                                                   m.get("counts", {}).get("recipes"))
        except Exception as e:
            return "recipes: %s" % e


def handle(service, msg):
    """Returns the JSON-RPC response for one message, or None for notifications."""
    if not isinstance(msg, dict):
        return {"jsonrpc": "2.0", "id": None, "error": {"code": -32600, "message": "invalid request: expected an object"}}
    method, mid, params = msg.get("method"), msg.get("id"), msg.get("params")
    params = params if isinstance(params, dict) else {}
    if mid is None:
        return None
    if method == "initialize":
        result = {
            "protocolVersion": params.get("protocolVersion", "2025-06-18"),
            "capabilities": {"tools": {}},
            "serverInfo": {"name": "recipes", "version": "1.0.0"},
            "instructions": INSTRUCTIONS,
        }
    elif method == "ping":
        result = {}
    elif method == "tools/list":
        result = {"tools": TOOLS}
    elif method == "tools/call":
        try:
            args = params.get("arguments")
            text, is_error = service.call(params.get("name"), args if isinstance(args, dict) else {})
        except (ValueError, FileNotFoundError) as e:
            text, is_error = "error: %s" % e, True
        except Exception as e:
            log(traceback.format_exc())
            text, is_error = "error: %s" % e, True
        result = {"content": [{"type": "text", "text": text}], "isError": is_error}
    else:
        return {"jsonrpc": "2.0", "id": mid, "error": {"code": -32601, "message": "method not found: %s" % method}}
    return {"jsonrpc": "2.0", "id": mid, "result": result}


def safe_handle(service, msg):
    try:
        return handle(service, msg)
    except Exception as e:
        log(traceback.format_exc())
        mid = msg.get("id") if isinstance(msg, dict) else None
        return {"jsonrpc": "2.0", "id": mid, "error": {"code": -32603, "message": "internal error: %s" % e}}


def serve(service, stdin, stdout, workers=CALL_WORKERS):
    """One JSON-RPC message per line; tools/call runs on a worker pool (replies matched by id)."""
    out_lock = threading.Lock()

    def reply(msg):
        r = safe_handle(service, msg)
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


def main(argv=None):
    argv = sys.argv[1:] if argv is None else argv
    service = Service(argv[argv.index("--db") + 1] if "--db" in argv else None)
    log(service.status_line())
    serve(service, sys.stdin, sys.stdout)


if __name__ == "__main__":
    main()
