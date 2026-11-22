"""Item costs: what one unit of every item and fluid costs to make on its own, in EU-equivalent, per voltage tier.
Stored in the recipe database's `item_costs` table; read by the `item_cost` tool (server.py).

  python3 -I tools/recipes/item_costs.py [--db PATH] [--tiers ULV-UXV]

Needs numpy (requirements.txt). tools/recipe-export/run.sh runs it after build_db.py with $RECIPES_PYTHON (default
python3); a database without the table still works, and `item_cost` then says the pass has not been run.

The cost of an item at a tier is the cheapest way to make one unit of it (one item, or 1 L of a fluid) from scratch:

    cost(X) = min( import price of X,
                   min over recipes r making X:  (EU/t * ticks of r  +  sum of consumed inputs * their cost) / X made )

over the recipes the player can run at that tier (the planner's own filter, planner.Planner.allowed, with no bans,
cleanroom allowed and no low gravity). Amounts are expected amounts (amount x chance). An ore dictionary slot, an
item choice or a wildcard costs what its cheapest member costs. The import price is the planner's, by class
(planner.RAW_PRICE): a raw resource its class price (free 0, cheap 2,000, costly 100,000 EU per item or 1,000 L), an
item no recipe makes at all "costly", anything else the "unavailable" price. This is plan_chain's first objective
(EU per item, raw resources priced by class) for the item alone, except that byproducts earn nothing: they go to
surplus, as in a plan, but are never fed back in. So a cost is never below what plan_chain finds and is equal to it
whenever the best chain doesn't reuse its own byproducts. Solved as a fixed point (Bellman-Ford style relaxation from
the import prices down), vectorised with numpy: about a second per tier.

Why not YAFC's dual pass (maximise the sum of all prices under "no recipe makes a profit"): on GTNH's co-product-heavy
graph the dual is degenerate. It may put a centrifuge's whole cost on one output and price another at zero (it priced
titanium dust at 0 EU at HV), which is useless for ranking sources.

A cost at the "unavailable" price means the item can't be made at that tier; those rows are not stored.

Coil heat per tier: the EBF filter uses the coil a player usually has by then (COIL_BY_TIER) plus GregTech's 100 K per
tier above MV.
"""

import argparse
import json
import os
import shutil
import sqlite3
import sys
import time
from collections import defaultdict
from pathlib import Path

import numpy as np

sys.path.insert(0, str(Path(__file__).resolve().parent))
import recipedb as R  # noqa: E402
import planner as P  # noqa: E402

# The coil a player usually builds at each voltage tier (index = tier). None below MV: no EBF yet.
COIL_BY_TIER = [None, None, "cupronickel", "kanthal", "nichrome", "tpv", "hss-g", "hss-s", "naquadah",
                "naquadah alloy", "trinium", "electrum flux", "awakened draconium", "infinity", "hypogen"]
DEFAULT_TIERS = range(0, 14)          # ULV .. UXV; the MAX recipes are a handful of creative ones
CAP = P.RAW_PRICE["unavailable"]
MAX_PASSES = 2000
REL_TOL = 1e-9
TABLE = """
CREATE TABLE item_costs(
  tier INTEGER NOT NULL, item_id TEXT NOT NULL, cost REAL NOT NULL, via INTEGER, bound TEXT,
  PRIMARY KEY (tier, item_id)) WITHOUT ROWID;
"""


def tier_heat(tier):
    coil = COIL_BY_TIER[tier] if 0 <= tier < len(COIL_BY_TIER) else COIL_BY_TIER[-1]
    return R.parse_heat(coil, tier) if coil else 0


class Graph:
    """Every recipe and io row loaded once; each tier is a filter over them."""

    def __init__(self, con):
        t0 = time.time()
        self.recipes = [dict(r) for r in con.execute(
            "SELECT id, hash, map, source, tier, eut, ticks, heat, cleanroom, lowgrav, fake, recycle, special "
            "FROM recipes WHERE recycle = 0")]
        io = defaultdict(list)
        for rid, side, iid, amount, chance, consumed in con.execute(
                "SELECT recipe_id, side, item_id, amount, chance, consumed FROM recipe_io"):
            io[rid].append((side, iid, amount * chance, consumed))
        self.io = io
        self.raw = {r[0]: r[1] for r in con.execute("SELECT item_id, class FROM raw_resources")}
        self.made = {r[0] for r in con.execute(
            "SELECT DISTINCT io.item_id FROM recipe_io io JOIN recipes r ON r.id = io.recipe_id "
            "WHERE io.side = 'out' AND r.recycle = 0")}
        self.members = {}
        for iid, members in con.execute("SELECT id, members FROM items WHERE members IS NOT NULL AND members != ''"):
            items = (json.loads(members) or {}).get("items") or []
            if items:
                self.members[iid] = items
        self.kind = dict(tuple(r) for r in con.execute("SELECT id, kind FROM items"))
        self.load_seconds = time.time() - t0

    def allowed(self, r, tier, heat):
        """planner.Planner.allowed with no bans, cleanroom allowed and low gravity not."""
        if r["fake"] and r["map"] not in P.REAL_FAKE_MAPS:
            return False
        if r["special"] and json.loads(r["special"]).get("incomplete"):
            return False
        if r["tier"] is not None and r["tier"] > tier:
            return False
        if r["heat"] and r["heat"] > heat:
            return False
        if r["lowgrav"]:
            return False
        if r["eut"] <= 0 and not any(side == "in" and consumed for side, _, _, consumed in self.io[r["id"]]):
            return False
        return True

    def ceiling(self, iid):
        """(import price per unit, class): what the planner would charge to import it."""
        s = P.unit_scale(iid)
        cls = self.raw.get(iid)
        if cls and cls != "never":
            return P.RAW_PRICE[cls] * s, cls
        if iid not in self.made and iid not in self.members:
            return P.RAW_PRICE["costly"] * s, "no recipe"
        return CAP * s, "unavailable"


def solve_tier(g, tier, log=lambda s: None):
    """{item_id: (cost per unit, via recipe id or None, import class or None)} for one tier."""
    t0 = time.time()
    heat = tier_heat(tier)
    rows = [r for r in g.recipes if g.allowed(r, tier, heat)]
    items = {}

    def ix(iid):
        j = items.get(iid)
        if j is None:
            j = items[iid] = len(items)
        return j

    in_k, in_j, in_a, out_k, out_j, out_a, eu = [], [], [], [], [], [], []
    for k, r in enumerate(rows):
        eu.append(max(0.0, float(r["eut"]) * r["ticks"]))
        for side, iid, amt, consumed in g.io[r["id"]]:
            j = ix(iid)
            if side == "out":
                if amt > 0:
                    out_k.append(k), out_j.append(j), out_a.append(amt)
            elif consumed and amt > 0:
                in_k.append(k), in_j.append(j), in_a.append(amt)
    conv_node, conv_member = [], []
    for node, mem in g.members.items():
        if node in items:
            for m in mem:
                conv_node.append(items[node]), conv_member.append(ix(m))
    n = len(items)
    ids = [None] * n
    for iid, j in items.items():
        ids[j] = iid
    ceil_, cls = np.zeros(n), [None] * n
    for j, iid in enumerate(ids):
        ceil_[j], cls[j] = g.ceiling(iid)
    in_k, in_j, in_a = np.array(in_k, np.int64), np.array(in_j, np.int64), np.array(in_a)
    out_k, out_j, out_a = np.array(out_k, np.int64), np.array(out_j, np.int64), np.array(out_a)
    conv_node, conv_member = np.array(conv_node, np.int64), np.array(conv_member, np.int64)
    eu = np.array(eu)
    cost = ceil_.copy()
    passes = 0
    for passes in range(1, MAX_PASSES + 1):
        rcost = eu + np.bincount(in_k, weights=in_a * cost[in_j], minlength=len(rows))
        new = ceil_.copy()
        np.minimum.at(new, out_j, rcost[out_k] / out_a)
        if len(conv_node):
            np.minimum.at(new, conv_node, cost[conv_member])
        new = np.minimum(new, cost)
        done = np.all(cost - new <= REL_TOL * np.maximum(1.0, cost))
        cost = new
        if done:
            break
    rcost = eu + np.bincount(in_k, weights=in_a * cost[in_j], minlength=len(rows))
    route = rcost[out_k] / out_a
    best = np.full(n, np.inf)
    np.minimum.at(best, out_j, route)
    via = np.full(n, -1, np.int64)
    tight = route <= best[out_j] * (1 + 1e-12)
    via[out_j[tight]] = out_k[tight]
    out = {}
    for j, iid in enumerate(ids):
        c = float(cost[j])
        if via[j] >= 0 and best[j] <= c * (1 + 1e-6) + 1e-9 and best[j] < ceil_[j]:
            out[iid] = (c, rows[via[j]]["id"], None)
        else:
            out[iid] = (c, None, cls[j] if c >= ceil_[j] * (1 - 1e-9) else "slot")
    log("tier %s (heat %s K): %d recipes, %d items, %d slot links, %d passes, %.1fs" % (
        R.tier_name(tier), heat, len(rows), n, len(conv_node), passes, time.time() - t0))
    if passes == MAX_PASSES:
        log("tier %s: not fully converged after %d passes (a slow gain cycle); costs are upper bounds" % (
            R.tier_name(tier), MAX_PASSES))
    return out


def build(db_path, tiers=DEFAULT_TIERS, log=lambda s: None):
    """Computes every tier and writes item_costs (replacing the table) plus pack_meta.item_costs. Works on a copy that
    replaces the database at the end, so a running server keeps reading a complete file."""
    t0 = time.time()
    db_path = Path(db_path)
    tmp = db_path.with_name(db_path.name + ".costs.tmp")
    shutil.copyfile(db_path, tmp)
    con = sqlite3.connect(str(tmp))
    con.row_factory = sqlite3.Row
    g = Graph(con)
    log("loaded %d recipes in %.1fs" % (len(g.recipes), g.load_seconds))
    rows, stored = [], {}
    for tier in tiers:
        cnt = 0
        for iid, (c, via, src) in solve_tier(g, tier, log).items():
            if src == "unavailable" or g.kind.get(iid) not in ("item", "fluid"):
                continue
            rows.append((tier, iid, c, via, src))
            cnt += 1
        stored[R.tier_name(tier)] = cnt
    con.execute("DROP TABLE IF EXISTS item_costs")
    con.executescript(TABLE)
    con.executemany("INSERT INTO item_costs VALUES (?,?,?,?,?)", rows)
    info = {"tiers": [R.tier_name(t) for t in tiers], "items_per_tier": stored, "built_at": int(time.time()),
            "heat_by_tier": {R.tier_name(t): tier_heat(t) for t in tiers}, "seconds": round(time.time() - t0, 1),
            "import_prices": dict(P.RAW_PRICE), "method": "standalone cost, byproducts unvalued"}
    con.execute("INSERT OR REPLACE INTO pack_meta VALUES ('item_costs', ?)", (json.dumps(info),))
    con.commit()
    con.execute("VACUUM")
    con.close()
    os.replace(tmp, db_path)
    log("item_costs: %d rows in %.1fs" % (len(rows), time.time() - t0))
    return info


def parse_tiers(text):
    a, _, b = str(text).partition("-")
    a = R.parse_tier(a)
    return range(a, (R.parse_tier(b) if b else a) + 1)


def main(argv=None):
    ap = argparse.ArgumentParser(description=__doc__.split("\n")[0])
    ap.add_argument("--db", default=None)
    ap.add_argument("--tiers", default="ULV-UXV", help="a tier or a range, e.g. HV or ULV-UXV")
    a = ap.parse_args(argv)
    db = Path(a.db) if a.db else R.default_db_path()
    if not db.is_file():
        print("item_costs: no database at %s" % db, file=sys.stderr)
        return 1
    info = build(db, parse_tiers(a.tiers), log=lambda m: print("[item_costs] " + m, file=sys.stderr, flush=True))
    print(json.dumps(info["items_per_tier"]))
    return 0


if __name__ == "__main__":
    sys.exit(main())
