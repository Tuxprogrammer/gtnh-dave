"""plan_chain: the best production chain for an item, as a lexicographic linear program solved with HiGHS (highspy).

1. Candidate graph. Walk backwards from the target through recipe_io: the producers of every item, filtered by the
   player's tier, coil heat, cleanroom and low gravity, bans, display-only recipes and recycling, cheapest EU per unit
   first and at most `per_item` per item. The walk stops at raw resources (raw_resources), at free inputs, and at a
   depth or size limit. An ore dictionary slot becomes 1:1 conversions from its member items.
2. LP. One rate variable per recipe (runs per second), one balance row per item:
   production - consumption + import - surplus = demand. Byproducts go to surplus; cycles need no special handling.
   Imports exist only for items the walk did not expand, each at a price by class.
3. Objective, lexicographic (each stage keeps the previous optimum within a small tolerance):
   1) EU per target unit: machine EU (EU/t x ticks per run) plus raw resources at an EU-equivalent price by class
      (free 0; cheap and costly a fixed price; anything unexpanded or unavailable at the player's tier a prohibitive
      one). Pure machine EU would make any ore-electrolysis byproduct route "best" and ignores that mining costs
      something; the price list is RAW_PRICE below. The machine EU alone is reported separately.
   2) raw-resource cost by class weight; 3) machine count at base speed; 4) total flow.
4. Steering: locks (a recipe id or a machine) in a strong form: "direct" (it makes the target, and nothing else
   does) when feasible, else "route1-3" (the target is made 1-3 steps downstream of it, from what it makes; the
   cheapest wins; empty cells and buckets do not count as a link); only if none is feasible, "weak" (it just has to
   run). The plan says which form held, free inputs (import at no cost, not expanded), bans (recipe id, machine or map, mod, or item: no import).
5. Runner-up: the same problem with the winning route's last step (the main producer of the target) banned.
6. Overclocks. The LP ranks routes at base speed: with standard overclocks EU per item goes UP, so the least-EU
   answer is the un-overclocked one, and overclocking changes machine counts, not the ranking. The plan then also
   gives each GregTech step's machine count at the player's tier (`oc_tier`, default `tier`) from GregTech's own
   OverclockCalculator, run by the exporter on our server for every recipe, tier and coil (build_db.py keeps the
   results in `recipe_oc`), so the durations are GTNHRates' as the server runs them. The step runs on the map's
   single-block machine when it has one (at the highest tier built, up to oc_tier), else on its multiblock. A
   multiblock whose overclocks would take a recipe under one tick runs extra parallels instead (subtick_parallels,
   GregTech's rule); a single block stops at one tick and still pays the overclocked EU/t. Not modelled: a
   multiblock's own parallels and speed bonuses (most GT++ and later multis), amperage and laser overclocks.
7. Export (`export=True`): the chain as compact JSON for the planner site's `plan_add_chain` tool, which matches each
   step to the site's own recipe by content (machine, EU/t, duration, inputs, outputs). Planner resource ids are ours
   with fluids bare ("nitrogen", not "fluid:nitrogen").
"""

import json
import math
import sys
from collections import defaultdict, deque
from pathlib import Path

sys.path.insert(0, str(Path(__file__).resolve().parent))
import recipedb as R  # noqa: E402

try:  # the one non-stdlib dependency (tools/recipes/requirements.txt)
    import highspy
    import numpy as np
except ImportError:  # pragma: no cover - reported by plan_chain
    highspy = None
    np = None

# EU-equivalent price of importing one unit (one item, or 1000 L of a fluid) by cost class.
RAW_PRICE = {"free": 0.0, "cheap": 2_000.0, "costly": 100_000.0, "unavailable": 10_000_000.0,
             "frontier": 10_000_000.0}
# Stage-2 weights per unit by class.
RAW_WEIGHT = {"free": 0.0, "cheap": 1.0, "costly": 10.0, "unavailable": 1000.0, "frontier": 1000.0}
REAL_FAKE_MAPS = ("gt.recipe.fakeAssemblylineProcess", "gt.recipe.rockbreaker", "gt.recipe.multiblockrockbreaker",
                  "gt.recipe.treefarm")
LOCK_RATE = 1e-3          # runs per second a locked recipe or machine must at least do
LOCK_MODES = {
    "direct": "the target is made only by the locked step",
    "route1": "the target is made one step after the locked step, from what it makes",
    "route2": "the target is made two steps after the locked step, from what it makes",
    "route3": "the target is made three steps after the locked step, from what it makes",
    "weak": "the locked step only has to run",
}
REL_TOL = 1e-5            # lexicographic tolerance: later stages may give up this much of an earlier optimum
MAX_ORE_MEMBERS = 6
# The coil a player usually has at each voltage tier (index = tier), for overclocks when no heat is given.
COIL_BY_TIER = [None, None, "cupronickel", "kanthal", "nichrome", "tpv", "hss-g", "hss-s", "naquadah",
                "naquadah-alloy", "trinium", "electrum-flux", "awakened-draconium", "infinity", "hypogen"]


def subtick_parallels(duration, heat_ocs, regular_ocs, per_oc=2.0):
    """Recipes a multiblock runs per tick once its overclocks would take a recipe under one tick: GregTech's
    OverclockCalculator.calculateMultiplierUnderOneTick (the non-laser branch), which ProcessingLogic multiplies the
    machine's parallels by. Ported line for line from gtnh-src/source/GT5-Unofficial/src/main/java/gregtech/api/util/
    OverclockCalculator.java (GTNH 2.9.0); test_recipes.py checks it against values from the live server.
    duration: base ticks (after any duration modifier); per_oc: 2, or 4 for perfect overclocks."""
    if duration <= 0 or heat_ocs + regular_ocs <= 0:
        return 1
    after_heat = duration / 4.0 ** heat_ocs
    need_heat = math.ceil(math.log(duration) / math.log(4.0))
    need_regular = math.ceil(math.log(after_heat) / math.log(per_oc))
    extra_heat = max(heat_ocs - need_heat, 0)
    extra_regular = max(regular_ocs - need_regular, 0)
    if heat_ocs >= need_heat:
        correction = 4.0 ** need_heat / duration
    elif regular_ocs >= need_regular:
        correction = per_oc ** need_regular / after_heat
    else:
        return 1
    return max(1, int(math.ceil(4.0 ** extra_heat * per_oc ** extra_regular * correction - 1e-9)))


def coil_key(name):
    return str(name).lower().replace("_", "-").replace(" ", "-")


def planner_id(iid):
    """Our item id -> the planner site's resource id: fluids are bare there."""
    return iid[6:] if iid.startswith("fluid:") else iid


class PlanError(Exception):
    pass


class _Infeasible(Exception):
    pass


def unit_scale(iid):
    """Per-unit factor: prices are per item and per 1000 L."""
    return 1e-3 if iid.startswith("fluid:") else 1.0


def default_rate(iid):
    return 1000.0 if iid.startswith("fluid:") else 1.0


def fmt_rate(iid, per_s):
    if iid.startswith("fluid:"):
        return "%s L/s" % R.fmt_num(round(per_s, 3))
    return "%s/s" % R.fmt_num(round(per_s, 4))


def fmt_unit(iid):
    return "1,000 L" if iid.startswith("fluid:") else "item"


class Planner:
    def __init__(self, con, target, rate=None, tier=None, heat=None, cleanroom=True, lowgrav=False, locks=(),
                 free=(), banned=(), max_depth=8, max_recipes=3000, per_item=25, oc_tier=None):
        if highspy is None:
            raise PlanError("the chain planner needs highspy: pip install -r tools/recipes/requirements.txt")
        self.con = con
        self.names = {}
        tid, err = R.resolve(con, target)
        if err:
            raise PlanError("target: " + err)
        self.target = tid
        self.rate = float(rate) if rate else default_rate(tid)
        if self.rate <= 0:
            raise PlanError("rate must be positive")
        self.tier = R.parse_tier(tier)
        self.heat = R.parse_heat(heat, self.tier)
        if isinstance(oc_tier, str) and oc_tier.strip().lower() in ("none", "off", "no", "base"):
            self.oc_tier = None
        else:
            self.oc_tier = R.parse_tier(oc_tier) if oc_tier not in (None, "") else self.tier
        if self.oc_tier is not None and self.tier is not None and self.oc_tier > self.tier:
            raise PlanError("oc_tier %s is above the plan's tier %s" % (R.tier_name(self.oc_tier),
                                                                       R.tier_name(self.tier)))
        self._oc_cache, self._map_cache = {}, {}
        self.want_export = False
        self.cleanroom = True if cleanroom is None else bool(cleanroom)
        self.lowgrav = bool(lowgrav)
        self.max_depth, self.max_recipes, self.per_item = int(max_depth), int(max_recipes), int(per_item)
        self.notes = []
        self.free = set()
        for f in free or ():
            iid, err = R.resolve(con, f)
            if err:
                raise PlanError("free input: " + err)
            self.free.add(iid)
        self.ban_hashes, self.ban_maps, self.ban_mods, self.ban_items = set(), set(), set(), set()
        for b in banned or ():
            self._ban(b)
        self.lock_hashes, self.lock_maps = set(), set()
        for lk in locks or ():
            self._lock(lk)

    # ---------------------------------------------------------------- steering
    def _map_ids(self, ref):
        ref = str(ref).strip()
        rows = self.con.execute("SELECT id FROM maps WHERE id = ? OR name = ? COLLATE NOCASE", (ref, ref)).fetchall()
        if not rows:
            rows = self.con.execute("SELECT DISTINCT map AS id FROM machines WHERE name = ? COLLATE NOCASE",
                                    (ref,)).fetchall()
        return {r["id"] for r in rows}

    def _ban(self, ref):
        ref = str(ref).strip()
        if not ref:
            return
        r = R.recipe_by_ref(self.con, ref) if len(ref.lstrip("#")) >= 6 else None
        if r:
            self.ban_hashes.add(r["hash"])
            return
        maps = self._map_ids(ref)
        if maps:
            self.ban_maps |= maps
            return
        low = ref.lower()
        if self.con.execute("SELECT 1 FROM items WHERE lower(mod) = ? LIMIT 1", (low,)).fetchone() or \
                self.con.execute("SELECT 1 FROM maps WHERE id LIKE ? LIMIT 1", (low + ".%",)).fetchone():
            self.ban_mods.add(low)
            self.ban_maps |= {m["id"] for m in self.con.execute("SELECT id FROM maps WHERE lower(id) LIKE ?",
                                                                  (low + ".%",))}
            return
        iid, err = R.resolve(self.con, ref)
        if err:
            raise PlanError("ban %r: not a recipe id, machine, mod or item (%s)" % (ref, err))
        self.ban_items.add(iid)

    def _lock(self, ref):
        ref = str(ref).strip()
        r = R.recipe_by_ref(self.con, ref) if len(ref.lstrip("#")) >= 6 else None
        if r:
            self.lock_hashes.add(r["hash"])
            return
        maps = self._map_ids(ref)
        if not maps:
            raise PlanError("lock %r: not a recipe id or a machine / recipe map name" % ref)
        self.lock_maps |= maps

    # ---------------------------------------------------------------- graph
    def allowed(self, r):
        if r["hash"] in self.ban_hashes or r["map"] in self.ban_maps:
            return False, "banned"
        if r["fake"] and r["map"] not in REAL_FAKE_MAPS:
            return False, "display-only"
        sp = json.loads(r["special"]) if r["special"] else {}
        if sp.get("incomplete"):
            return False, "inputs not exported"
        if self.tier is not None and r["tier"] is not None and r["tier"] > self.tier:
            return False, "needs %s" % R.tier_name(r["tier"])
        if self.heat is not None and r["heat"] and r["heat"] > self.heat:
            return False, "needs %dK coils" % r["heat"]
        if r["eut"] <= 0 and not any(x["side"] == "in" and x["consumed"] for x in self.io(r["id"])):
            # 0 EU and nothing consumed: a display of what a special machine yields (Eye of Harmony, algae pond,
            # tree growth simulator) or a crafting recipe whose inputs the exporter could not read. Its real cost is
            # not in the data, and as a free source it would win every plan.
            return False, "no modelled cost"
        if r["cleanroom"] and not self.cleanroom:
            return False, "needs a cleanroom"
        if r["lowgrav"] and not self.lowgrav:
            return False, "needs low gravity"
        return True, None

    def io(self, rid):
        if rid not in self._io:
            self._io[rid] = R.io_rows(self.con, rid)
        return self._io[rid]

    def build_graph(self):
        con = self.con
        self._io = {}
        self.recipes = {}           # hash -> recipe row
        self.convs = []             # (from member, to node)
        self.imports = {}           # item -> class
        self.why_import = {}
        self.items = set()
        locked = {}
        for h in self.lock_hashes:
            r = dict(con.execute("SELECT * FROM recipes WHERE hash = ?", (h,)).fetchone())
            ok, why = self.allowed(r)
            if not ok:
                raise PlanError("locked recipe #%s is not usable: %s" % (h, why))
            locked[h] = r
        raw = {r["item_id"]: r["class"] for r in con.execute("SELECT item_id, class FROM raw_resources")}
        queue = deque([(self.target, 0)])
        for r in locked.values():
            self.recipes[r["hash"]] = r
            for x in self.io(r["id"]):
                if x["side"] == "in" and x["consumed"]:
                    queue.append((x["item_id"], 1))
        seen = set()
        filtered_out = defaultdict(list)
        while queue:
            iid, depth = queue.popleft()
            if iid in seen:
                continue
            seen.add(iid)
            self.items.add(iid)
            if iid in self.free:
                self.imports[iid] = "free"
                self.why_import[iid] = "free (you said you have plenty)"
                continue
            if iid != self.target and iid in raw:
                if iid not in self.ban_items and raw[iid] != "never":
                    self.imports[iid] = raw[iid]
                    self.why_import[iid] = "raw resource (%s)" % raw[iid]
                continue
            kind = iid.split(":", 1)[0]
            info = R.item(con, iid) or {}
            if kind in ("ore", "choice") or info.get("kind") == "wildcard":
                members = (json.loads(info["members"]) if info.get("members") else {}).get("items") or []
                ranked = con.execute(
                    "SELECT id FROM items WHERE id IN (%s) ORDER BY (id IN (SELECT item_id FROM raw_resources)) DESC, "
                    "producers DESC LIMIT %d" % (",".join("?" * len(members)), MAX_ORE_MEMBERS),
                    members).fetchall() if members else []
                for m in ranked:
                    self.convs.append((m["id"], iid))
                    queue.append((m["id"], depth))
                if not ranked:
                    self.imports[iid] = "unavailable"
                    self.why_import[iid] = "ore dictionary slot with no known member"
                continue
            if depth >= self.max_depth or len(self.recipes) >= self.max_recipes:
                if iid != self.target:
                    self.imports[iid] = "frontier"
                    self.why_import[iid] = "not expanded (depth or size limit)"
                continue
            rows = con.execute(
                "SELECT r.*, io.amount * io.chance AS per_run FROM recipe_io io JOIN recipes r ON r.id = io.recipe_id "
                "WHERE io.item_id = ? AND io.side = 'out' AND r.recycle = 0", (iid,)).fetchall()
            cands = []
            for r in rows:
                r = dict(r)
                ok, why = self.allowed(r)
                if not ok:
                    filtered_out[iid].append(why)
                    continue
                cands.append(r)
            cands.sort(key=lambda r: (r["map"] not in self.lock_maps,
                                      (r["eut"] * r["ticks"]) / max(r["per_run"], 1e-9), r["ticks"]))
            keep = [r for r in cands if r["map"] in self.lock_maps] + \
                   [r for r in cands if r["map"] not in self.lock_maps][:self.per_item]
            for r in keep:
                if r["hash"] in self.recipes:
                    continue
                self.recipes[r["hash"]] = r
                for x in self.io(r["id"]):
                    if x["side"] == "in" and x["consumed"] and x["item_id"] not in seen:
                        queue.append((x["item_id"], depth + 1))
                    elif x["side"] == "out":
                        self.items.add(x["item_id"])
            if not keep and iid != self.target:
                self.imports[iid] = "unavailable" if rows else "costly"
                reasons = sorted(set(filtered_out.get(iid, [])))
                self.why_import[iid] = ("no usable recipe: " + ", ".join(reasons)) if reasons else \
                    "no recipe makes it"
        for iid in self.ban_items:
            self.imports.pop(iid, None)
        self.imports.pop(self.target, None)
        self.filtered_out = filtered_out
        if not any(x["side"] == "out" and x["item_id"] == self.target
                   for r in self.recipes.values() for x in self.io(r["id"])):
            reasons = sorted(set(filtered_out.get(self.target, [])))
            raise PlanError("nothing makes %s under these constraints%s" % (
                R.item_name(con, self.target, self.names), (" (" + ", ".join(reasons) + ")") if reasons else ""))

    # ---------------------------------------------------------------- LP
    def solve(self, extra_bans=()):
        recipes = [r for h, r in sorted(self.recipes.items()) if h not in extra_bans]
        items = sorted(self.items | {m for m, _ in self.convs} | {n for _, n in self.convs})
        row = {iid: i for i, iid in enumerate(items)}
        cols = []   # (kind, key, entries [(row, coef)], costs dict)
        for r in recipes:
            ent = defaultdict(float)
            for x in self.io(r["id"]):
                if x["item_id"] not in row:
                    continue
                if x["side"] == "out":
                    ent[row[x["item_id"]]] += x["amount"] * x["chance"]
                elif x["consumed"]:
                    ent[row[x["item_id"]]] -= x["amount"] * x["chance"]
            eu = float(r["eut"]) * r["ticks"]
            cols.append(("recipe", r["hash"], dict(ent), {"eu": eu, "raw": 0.0, "machines": r["ticks"] / 20.0,
                                                          "flow": 1.0, "machine_eu": eu}))
        for m, n in self.convs:
            cols.append(("conv", (m, n), {row[m]: -1.0, row[n]: 1.0},
                         {"eu": 0.0, "raw": 0.0, "machines": 0.0, "flow": 0.0, "machine_eu": 0.0}))
        for iid, cls in sorted(self.imports.items()):
            s = unit_scale(iid)
            cols.append(("import", iid, {row[iid]: 1.0}, {"eu": RAW_PRICE[cls] * s, "raw": RAW_WEIGHT[cls] * s,
                                                         "machines": 0.0, "flow": 0.0, "machine_eu": 0.0}))
        for iid in items:
            cols.append(("surplus", iid, {row[iid]: -1.0}, {"eu": 0.0, "raw": 0.0, "machines": 0.0, "flow": 0.0,
                                                            "machine_eu": 0.0}))
        h = highspy.Highs()
        h.setOptionValue("output_flag", False)
        inf = highspy.kHighsInf
        n = len(cols)
        lp = highspy.HighsLp()
        lp.num_col_, lp.num_row_ = n, len(items)
        lp.col_cost_ = np.zeros(n)
        lp.col_lower_ = np.zeros(n)
        lp.col_upper_ = np.full(n, inf)
        demand = np.zeros(len(items))
        demand[row[self.target]] = self.rate
        lp.row_lower_ = demand
        lp.row_upper_ = demand.copy()
        starts, idx, val = [0], [], []
        for _, _, ent, _ in cols:
            for k in sorted(ent):
                if ent[k]:
                    idx.append(k)
                    val.append(ent[k])
            starts.append(len(idx))
        lp.a_matrix_.format_ = highspy.MatrixFormat.kColwise
        lp.a_matrix_.start_ = np.array(starts, dtype=np.int32)
        lp.a_matrix_.index_ = np.array(idx, dtype=np.int32)
        lp.a_matrix_.value_ = np.array(val, dtype=np.float64)
        self._lp = (lp, cols)
        modes = ["weak"]
        if self.lock_hashes or self.lock_maps:
            modes = (["direct"] if self._lock_groups(cols)[1] else []) + ["route1", "route2", "route3", "weak"]
        if modes == ["weak"]:
            res = self._run(lp, cols, "weak")
            if res is not None:
                res["lock_mode"] = None
            return res
        # "direct" when the locked step can make the target and that is feasible; otherwise the route forms compete
        # on EU (the cheapest way through the locked step wins); the weak form is the last resort
        found = []
        for mode in modes[:-1]:
            try:
                res = self._run(lp, cols, mode)
            except _Infeasible:
                res = None
            if res is not None:
                res["lock_mode"] = mode
                res["stuck"] = any(c in ("unavailable", "frontier") for _, _, c in res["imports"])
                if mode == "direct" and not res["stuck"]:
                    return res
                found.append(res)
        if found:
            # a form that only works by importing what the player cannot make loses to one that does not
            return min(found, key=lambda r: (r["stuck"], r["stages"]["eu"]))
        res = self._run(lp, cols, "weak")
        if res is not None:
            res["lock_mode"] = "weak"
        return res

    def _lock_groups(self, cols):
        """Lock groups (one per lock: a set of recipe hashes in the graph) and whether any can make the target."""
        recipes = [c[1] for c in cols if c[0] == "recipe"]
        groups = [{h_} for h_ in self.lock_hashes if h_ in recipes]
        groups += [{h_ for h_ in recipes if self.recipes[h_]["map"] == mp} for mp in sorted(self.lock_maps)]
        groups = [g for g in groups if g]
        if len(groups) < len(self.lock_hashes) + len(self.lock_maps):
            raise PlanError("a locked recipe or machine has no usable recipe in this chain (%s)" % ", ".join(
                sorted(self.lock_hashes | self.lock_maps)))
        direct = any(self._makes(h_, self.target) for g in groups for h_ in g)
        return groups, direct

    def _container(self, iid):
        """Empty cells, buckets and capsules circulate; a route must not 'pass through' the locked step by them."""
        name = self.name(iid).lower()
        return name.startswith("empty ") or iid in ("minecraft:bucket", "minecraft:glass_bottle")

    def _makes(self, h_, iid):
        return any(x["side"] == "out" and x["item_id"] == iid for x in self.io(self.recipes[h_]["id"]))

    def _apply_locks(self, h, cols, mode):
        inf = highspy.kHighsInf
        groups, _ = self._lock_groups(cols)
        col_of = {(c[0], c[1]): i for i, c in enumerate(cols)}
        recipes = [c[1] for c in cols if c[0] == "recipe"]
        makes_target = {h_ for h_ in recipes if self._makes(h_, self.target)}
        banned = set()
        for g in groups:
            js = np.array(sorted(col_of[("recipe", h_)] for h_ in g), dtype=np.int32)
            h.addRow(LOCK_RATE, inf, len(js), js, np.ones(len(js)))
            if mode == "direct" and g & makes_target:
                banned |= makes_target - g
            elif mode.startswith("route"):
                banned |= self._route(h, cols, col_of, g, makes_target, int(mode[5:]))
        for h_ in banned - set().union(*groups):
            h.changeColBounds(col_of[("recipe", h_)], 0, 0)

    def _route(self, h, cols, col_of, g, makes_target, k):
        """The target is made `k` recipe steps downstream of lock group g. Level 0 is g; level i+1 the recipes
        consuming what level i makes. The target's producers are restricted to level k, and walking back from them,
        every level must make at least what the next level on the path consumes of it (a flow constraint, so a
        recipe that merely shares a byproduct with the locked step is not banned)."""
        inf = highspy.kHighsInf
        recipes = [c[1] for c in cols if c[0] == "recipe"]
        outs = {h_: {x["item_id"]: x["amount"] * x["chance"] for x in self.io(self.recipes[h_]["id"])
                     if x["side"] == "out"} for h_ in recipes}
        ins = {h_: {x["item_id"]: x["amount"] * x["chance"] for x in self.io(self.recipes[h_]["id"])
                    if x["side"] == "in" and x["consumed"]} for h_ in recipes}
        levels = [set(g)]
        for _ in range(k):
            made = {m for m in set().union(*(outs[h_] for h_ in levels[-1])) - {self.target}
                    if not self._container(m)}
            levels.append({h_ for h_ in recipes if h_ not in levels[-1] and made & set(ins[h_])})
        final = levels[k] & makes_target
        if not final:
            raise _Infeasible()
        # walk back: the recipes on a path to `final`
        path = [None] * (k + 1)
        path[k] = final
        for i in range(k - 1, -1, -1):
            needed = set().union(*(ins[h_] for h_ in path[i + 1]))
            path[i] = {h_ for h_ in levels[i] if needed & set(outs[h_])}
        for i in range(k):
            links = set().union(*(outs[h_] for h_ in path[i])) & set().union(*(ins[h_] for h_ in path[i + 1]))
            for m in (m for m in links if not self._container(m)):
                idx, val = [], []
                for h_ in path[i]:
                    if outs[h_].get(m):
                        idx.append(col_of[("recipe", h_)]); val.append(outs[h_][m])
                for h_ in path[i + 1]:
                    if ins[h_].get(m):
                        idx.append(col_of[("recipe", h_)]); val.append(-ins[h_][m])
                if idx:
                    h.addRow(0.0, inf, len(idx), np.array(idx, dtype=np.int32), np.array(val))
        return makes_target - final

    def _run(self, lp, cols, lock_mode):
        h = highspy.Highs()
        h.setOptionValue("output_flag", False)
        inf = highspy.kHighsInf
        n = len(cols)
        h.passModel(lp)
        if self.lock_hashes or self.lock_maps:
            self._apply_locks(h, cols, lock_mode)
        stages, x = {}, None
        all_idx = np.arange(n, dtype=np.int32)
        for stage, scale in (("eu", 1e-3), ("raw", 1.0), ("machines", 1.0), ("flow", 1.0)):
            cost = np.array([c[3][stage] * scale for c in cols])
            h.changeColsCost(n, all_idx, cost)
            h.run()
            status = h.getModelStatus()
            if status != highspy.HighsModelStatus.kOptimal:
                if stage == "eu":
                    return None
                # a later stage HiGHS cannot finish keeps the previous stage's optimum
                self.notes.append("stage %s not solved (%s); kept the previous optimum" % (
                    stage, h.modelStatusToString(status)))
                break
            obj = h.getInfo().objective_function_value
            x = np.array(h.getSolution().col_value)
            stages = {k: float(sum(c[3][k] * v for c, v in zip(cols, x))) for k in ("eu", "raw", "machines", "flow")}
            nz = np.nonzero(cost)[0].astype(np.int32)
            if len(nz):
                h.addRow(-inf, obj + REL_TOL * max(1.0, abs(obj)), len(nz), nz, cost[nz])
        return self._result(cols, x, stages)

    def _result(self, cols, x, stages):
        steps, imports, surplus = [], [], []
        machine_eu = 0.0
        # the lexicographic tolerance lets a later stage keep crumbs of other routes; anything this small is noise
        top = max([v for c, v in zip(cols, x) if c[0] == "recipe"] or [0.0])
        for (kind, key, ent, costs), v in zip(cols, x):
            if v <= max(1e-9, 1e-3 * top) and kind in ("recipe", "conv"):
                continue
            if v <= 1e-6:
                continue
            if kind == "recipe":
                r = self.recipes[key]
                machine_eu += costs["machine_eu"] * v
                steps.append({"recipe": r, "runs": v, "machines": v * r["ticks"] / 20.0})
            elif kind == "import" and v > 1e-5 * max(1.0, self.rate):
                imports.append((key, v, self.imports[key]))
            elif kind == "surplus" and v > 1e-6:
                surplus.append((key, v))
        steps.sort(key=lambda s: -s["machines"])
        target_producers = sorted(
            ((sum(xx["amount"] * xx["chance"] for xx in self.io(s["recipe"]["id"])
                  if xx["side"] == "out" and xx["item_id"] == self.target) * s["runs"], s["recipe"]["hash"])
             for s in steps), reverse=True)
        return {"steps": steps, "imports": imports, "surplus": surplus, "stages": stages,
                "machine_eu_per_s": machine_eu, "eu_per_unit": machine_eu / self.rate,
                "main": target_producers[0][1] if target_producers and target_producers[0][0] > 0 else None}

    # ---------------------------------------------------------------- run
    def plan(self, runner_up=True):
        self.build_graph()
        best = self.solve()
        if best is None:
            raise PlanError("no feasible chain for %s under these constraints" % R.item_name(
                self.con, self.target, self.names))
        second = None
        if runner_up and best["main"] and best["main"] not in self.lock_hashes:
            try:
                second = self.solve(extra_bans={best["main"]})
            except PlanError:      # the main step was the locked machine's only usable recipe
                second = None
        return self.add_overclocks(best), self.add_overclocks(second)

    # ---------------------------------------------------------------- overclocks
    def map_info(self, mp):
        if mp not in self._map_cache:
            row = self.con.execute("SELECT oc_profile FROM maps WHERE id = ?", (mp,)).fetchone()
            # GregTech's own machines first: a GT++ or later multiblock (Volcanus, Industrial Centrifuge) has
            # parallels and speed bonuses of its own that the counts don't model
            ms = [dict(m) for m in self.con.execute(
                "SELECT name, multiblock, tier FROM machines WHERE map = ? ORDER BY multiblock, tier, "
                "class NOT LIKE 'gregtech.%', priority, name", (mp,))]
            self._map_cache[mp] = ((row["oc_profile"] if row else None) or "standard", ms)
        return self._map_cache[mp]

    def variants(self, rid):
        if rid not in self._oc_cache:
            try:
                row = self.con.execute("SELECT variants FROM recipe_oc WHERE recipe_id = ?", (rid,)).fetchone()
            except Exception:  # a database built before recipe_oc existed
                row = None
            self._oc_cache[rid] = json.loads(row["variants"]) if row else []
        return self._oc_cache[rid]

    def pick_coil(self, coils, tier):
        """The coil a step's machine runs on: the best one the player's heat allows, else the usual one for the
        tier, else the first that runs the recipe. coils: keys as exported (cupronickel, hss_g, ...)."""
        heat_of = {c: R.COILS.get(coil_key(c)) for c in coils}
        bonus = 100 * max(0, tier - 2)
        if self.heat is not None:
            fit = [c for c in coils if heat_of[c] is not None and heat_of[c] + bonus <= self.heat]
            if fit:
                return max(fit, key=lambda c: heat_of[c]), "your heat"
        usual = COIL_BY_TIER[tier] if 0 <= tier < len(COIL_BY_TIER) else None
        for c in coils:
            if usual and coil_key(c) == usual:
                return c, "usual at %s" % R.tier_name(tier)
        return min(coils, key=lambda c: heat_of[c] or 0), "lowest that runs it"

    def overclock(self, s):
        """The step at oc_tier with GregTech's overclocks, or None (no tier, not a GT recipe, no variant)."""
        r = s["recipe"]
        t = self.oc_tier
        if t is None or r["source"] != "gregtech" or r["eut"] <= 0:
            return None
        vs = self.variants(r["id"])
        if not vs:
            return None
        profile, ms = self.map_info(r["map"])
        need = r["tier"] or 0
        singles = [m for m in ms if not m["multiblock"] and m["tier"] is not None and m["tier"] >= max(need, 1)]
        multis = [m for m in ms if m["multiblock"]]
        if singles:
            kind = "single"
            mt = min(t, max(m["tier"] for m in singles))
            at = [m for m in singles if m["tier"] == mt] or singles
            machine = at[0]["name"]
        else:
            kind = "multi" if multis else "single"
            mt = t
            machine = multis[0]["name"] if multis else (r["machine"] or r["map"])
        at_tier = [v for v in vs if v[0] == mt]
        if not at_tier:
            return None
        coil = why_coil = None
        if len(at_tier[0]) > 3:
            coil, why_coil = self.pick_coil([v[3] for v in at_tier], mt)
            at_tier = [v for v in at_tier if v[3] == coil]
        _, ticks, eut = at_tier[0][:3]
        parallel = 1
        if kind == "multi" and ticks <= 1:
            parallel = self._subtick(r, profile, mt, coil, eut)
        busy = s["runs"] * ticks / 20.0 / parallel
        return {"tier": mt, "kind": kind, "machine": machine, "ticks": ticks, "eut": eut, "parallel": parallel,
                "coil": coil, "coil_why": why_coil, "machines": busy,
                "eu_per_s": s["runs"] * eut * ticks / parallel}

    def _subtick(self, r, profile, tier, coil, eut):
        """Parallels per tick for a multiblock at the one-tick floor. The number of overclocks comes from the exported
        EU/t (each overclock is 4x the EU/t), so only GregTech's sub-tick rule itself is ported."""
        base_eut, duration = float(r["eut"]), float(r["ticks"])
        heat_ocs_max, per_oc = 0, 2.0
        level = None
        if coil is not None:
            order = ["cupronickel", "kanthal", "nichrome", "tpv", "hss-g", "hss-s", "naquadah", "naquadah-alloy",
                     "trinium", "electrum-flux", "awakened-draconium", "infinity", "hypogen", "eternal"]
            level = order.index(coil_key(coil)) if coil_key(coil) in order else 0
        if profile == "ebf-heat" and coil is not None:
            surplus = max(0, R.COILS.get(coil_key(coil), 0) + 100 * (tier - 2) - (r["heat"] or 0))
            base_eut *= 0.95 ** (surplus // 900)
            heat_ocs_max = surplus // 1800
        elif profile == "perfect-oc":
            per_oc = 4.0
        elif profile == "pyrolyse-coil" and level is not None:
            duration *= 2.0 / (1.0 + level)
        elif profile == "oil-cracker-coil" and level is not None:
            base_eut *= 1.0 - min(0.1 * (level + 1), 0.5)
        if base_eut <= 0:
            return 1
        ocs = max(0, int(round(math.log(max(eut / base_eut, 1.0)) / math.log(4.0))))
        heat_ocs = min(heat_ocs_max, ocs)
        return subtick_parallels(duration, heat_ocs, ocs - heat_ocs, per_oc)

    def add_overclocks(self, res):
        if res is None:
            return res
        for s in res["steps"]:
            s["oc"] = self.overclock(s)
        return res

    # ---------------------------------------------------------------- export
    def export(self, res):
        """The chain for the planner site's plan_add_chain: one step per recipe with what the site needs to find the
        same recipe (machine = the recipe map's name, EU/t, seconds, consumed inputs, outputs) and the tier to run it at."""
        steps = []
        for s in res["steps"]:
            r = s["recipe"]
            io = self.io(r["id"])
            outs = [x for x in io if x["side"] == "out"]
            main = next((x for x in outs if x["item_id"] == self.target), None) or \
                max(outs, key=lambda x: x["amount"] * x["chance"])
            step = {"recipe": r["hash"], "machine": r["machine"], "makes": planner_id(main["item_id"]),
                    "euT": r["eut"], "seconds": round(r["ticks"] / 20.0, 3),
                    "in": [[planner_id(x["item_id"]), x["amount"]] for x in io
                           if x["side"] == "in" and x["consumed"] and not x["item_id"].startswith(("ore:", "choice:"))],
                    "out": [[planner_id(x["item_id"]), x["amount"]] for x in outs],
                    "runs": round(s["runs"], 6)}
            if r["source"] == "gregtech" and r["tier"] is not None:
                oc = s.get("oc")
                step["tier"] = R.tier_name(oc["tier"] if oc else max(self.oc_tier or 0, r["tier"]))
            steps.append(step)
        out = {"target": {"id": planner_id(self.target), "rate": self.rate}, "steps": steps}
        cons = {}
        if self.tier is not None:
            cons["tierCap"] = R.tier_name(self.tier)
        if self.heat is not None:
            cons["heat"] = self.heat
        if not self.cleanroom:
            cons["cleanroom"] = False
        if self.lowgrav:
            cons["lowgrav"] = True
        free = sorted(planner_id(i) for i in self.free) + sorted(
            planner_id(i) for i, _, c in res["imports"] if c == "free")
        if free:
            cons["free"] = sorted(set(free))
        if cons:
            out["constraints"] = cons
        return out

    # ---------------------------------------------------------------- text
    def name(self, iid):
        return R.item_name(self.con, iid, self.names)

    def step_line(self, s):
        r = s["recipe"]
        io = self.io(r["id"])
        ins = "; ".join(R.fmt_io(self.con, x, self.names) for x in io if x["side"] == "in")
        outs = "; ".join(R.fmt_io(self.con, x, self.names) for x in io if x["side"] == "out")
        if r["source"] == "crafting":
            count = "by hand / crafter"
        else:
            count = "%sx at base speed (%s busy)" % (math.ceil(s["machines"] - 1e-9),
                                                    R.fmt_num(round(s["machines"], 2)))
            oc = s.get("oc")
            if oc:
                count += "; %s: %sx %s (%s busy) at %s%s EU/t, %ss per run%s%s" % (
                    "overclocked" if (oc["ticks"], oc["eut"]) != (r["ticks"], r["eut"]) else "no overclock",
                    math.ceil(oc["machines"] - 1e-9), oc["machine"], R.fmt_num(round(oc["machines"], 2)),
                    R.tier_name(oc["tier"]), " " + R.fmt_num(oc["eut"]), R.fmt_num(oc["ticks"] / 20.0),
                    ", %d parallel per tick" % oc["parallel"] if oc["parallel"] > 1 else "",
                    ", %s coils (%s)" % (oc["coil"].replace("_", " "), oc["coil_why"]) if oc["coil"] else "")
        return "- #%s %s (%s): %s -> %s | %s runs/s, %s" % (
            r["hash"], r["machine"], R.recipe_flags(r), ins, outs, R.fmt_num(round(s["runs"], 4)), count)

    def describe(self, res, limit=12):
        lines = ["CHAIN (%d steps):" % len(res["steps"])]
        for s in res["steps"][:limit]:
            lines.append(self.step_line(s))
        if len(res["steps"]) > limit:
            lines.append("- ... %d more small steps" % (len(res["steps"]) - limit))
        eut = res["machine_eu_per_s"] / 20.0
        per = res["eu_per_unit"] * (1000.0 if self.target.startswith("fluid:") else 1.0)
        lines.append("TOTALS: %s EU/t (machines at base speed); %s EU per %s of %s; machines by type: %s" % (
            R.fmt_num(round(eut, 2)), R.fmt_num(round(per, 1)), fmt_unit(self.target), self.name(self.target),
            ", ".join("%s %s" % (k, v) for k, v in self.machine_summary(res).items()) or "none"))
        ocs = [s for s in res["steps"] if s.get("oc")]
        if ocs:
            eu_s = sum(s["oc"]["eu_per_s"] if s.get("oc") else s["recipe"]["eut"] * s["runs"] * s["recipe"]["ticks"]
                       for s in res["steps"] if s["recipe"]["eut"] > 0)
            per_oc = eu_s / self.rate * (1000.0 if self.target.startswith("fluid:") else 1.0)
            lines.append("OVERCLOCKED at %s: %s EU/t; %s EU per %s (%s at base speed); machines by type: %s" % (
                R.tier_name(self.oc_tier), R.fmt_num(round(eu_s / 20.0, 2)), R.fmt_num(round(per_oc, 1)),
                fmt_unit(self.target), R.fmt_num(round(per, 1)),
                ", ".join("%s %s" % kv for kv in self.machine_summary(res, overclocked=True).items()) or "none"))
        lines.append("IMPORTS: " + ("; ".join("%s %s (%s)" % (fmt_rate(i, v), self.name(i), self.why_import.get(i, c))
                                              for i, v, c in sorted(res["imports"], key=lambda t: -t[1]))
                                    or "none"))
        byp = [(i, v) for i, v in res["surplus"] if i != self.target]
        lines.append("BYPRODUCTS: " + ("; ".join("%s %s" % (fmt_rate(i, v), self.name(i))
                                                 for i, v in sorted(byp, key=lambda t: -t[1])[:8]) or "none"))
        return lines

    def machine_summary(self, res, overclocked=False):
        out = defaultdict(int)
        for s in res["steps"]:
            r = s["recipe"]
            if r["source"] == "crafting":
                continue
            oc = s.get("oc") if overclocked else None
            if oc:
                out["%s (%s)" % (oc["machine"], R.tier_name(oc["tier"]))] += math.ceil(oc["machines"] - 1e-9)
            else:
                out["%s (%s)" % (r["machine"], R.tier_name(r["tier"]) if r["source"] == "gregtech" else "-")] += \
                    math.ceil(s["machines"] - 1e-9)
        return dict(sorted(out.items(), key=lambda kv: -kv[1]))

    def text(self, best, second):
        rate_txt = fmt_rate(self.target, self.rate)
        cons = ["tier %s" % (R.tier_name(self.tier) if self.tier is not None else "any"),
                "overclocks at %s" % R.tier_name(self.oc_tier) if self.oc_tier is not None else "no overclocks",
                "coil heat %s" % ("%dK" % self.heat if self.heat is not None else "any"),
                "cleanroom %s" % ("yes" if self.cleanroom else "no"),
                "low gravity %s" % ("yes" if self.lowgrav else "no")]
        if self.free:
            cons.append("free: " + ", ".join(self.name(i) for i in sorted(self.free)))
        if self.ban_hashes or self.ban_maps or self.ban_items:
            cons.append("banned: " + ", ".join(sorted(self.ban_hashes) + sorted(self.ban_maps) +
                                               [self.name(i) for i in sorted(self.ban_items)]))
        if self.lock_hashes or self.lock_maps:
            cons.append("locked: " + ", ".join(sorted(self.lock_hashes) + sorted(self.lock_maps)))
        lines = ["PLAN: %s %s (%s); %s" % (rate_txt, self.name(self.target), self.target, "; ".join(cons))]
        lines += self.describe(best)
        if second is None:
            lines.append("RUNNER-UP: none (the winning step is a locked recipe, or nothing else can make it)")
        else:
            main = self.recipes[best["main"]]
            lines.append("RUNNER-UP (without #%s %s): %s" % (main["hash"], main["machine"],
                                                             self.why_lost(best, second)))
            lines += ["  " + l for l in self.describe(second, limit=5)]
        lines.append("GRAPH: %d candidate recipes, %d items, %d imports considered%s" % (
            len(self.recipes), len(self.items), len(self.imports),
            "; some branches hit the depth/size limit" if any(c == "frontier" for c in self.imports.values()) else ""))
        if best.get("lock_mode"):
            lines.append("LOCK: held in the %s form: %s" % (best["lock_mode"], LOCK_MODES[best["lock_mode"]]))
        for n in dict.fromkeys(self.notes):
            lines.append("NOTE: " + n)
        lines.append("NOTES: least EU per item, raw resources priced by class (free 0, cheap %s, costly %s EU per "
                     "unit); routes are ranked at base speed (overclocking raises EU per item); overclocked counts "
                     "are GregTech's OverclockCalculator at that tier, one recipe per machine (a multiblock's own "
                     "parallels and speed bonuses are not counted); non-consumed inputs (circuits, molds, lenses) are "
                     "listed but not counted; slot alternatives are not substituted." % (
                         R.fmt_num(RAW_PRICE["cheap"]), R.fmt_num(RAW_PRICE["costly"])))
        if self.want_export:
            lines.append("PLANNER-CHAIN: " + json.dumps(self.export(best), separators=(",", ":")))
        return "\n".join(lines)

    def why_lost(self, best, second):
        if second is None:
            return "infeasible: nothing else can make it under these constraints"
        mul = 1000.0 if self.target.startswith("fluid:") else 1.0
        b, s = best["stages"], second["stages"]
        main2 = self.recipes[second["main"]]["machine"] if second.get("main") else "?"
        b_eu, s_eu = best["eu_per_unit"] * mul, second["eu_per_unit"] * mul
        if s.get("eu", 0) > b.get("eu", 0) * (1 + 1e-4) + 1e-9:
            extra = ""
            if s.get("raw", 0) > b.get("raw", 0) + 1e-9:
                extra = " and it uses costlier raw resources"
            return "via %s, %s EU per %s by machines vs %s (%s total EU-equivalent incl. raw resources)%s" % (
                main2, R.fmt_num(round(s_eu, 1)), fmt_unit(self.target), R.fmt_num(round(b_eu, 1)),
                "+%.0f%%" % (100.0 * (s["eu"] - b["eu"]) / b["eu"]) if b.get("eu") else "higher", extra)
        if s.get("raw", 0) > b.get("raw", 0) + 1e-9:
            return "via %s, same EU, costlier raw resources" % main2
        if s.get("machines", 0) > b.get("machines", 0) + 1e-9:
            return "via %s, same EU and raw cost, more machines (%s vs %s busy)" % (
                main2, R.fmt_num(round(s["machines"], 1)), R.fmt_num(round(b["machines"], 1)))
        return "via %s, a tie on every measure" % main2


def plan_chain(con, target, rate=None, tier=None, heat=None, cleanroom=True, lowgrav=False, locks=(), free=(),
               banned=(), max_depth=8, max_recipes=3000, runner_up=True, oc_tier=None, export=False):
    """Returns (text, error flag)."""
    try:
        p = Planner(con, target, rate, tier, heat, cleanroom, lowgrav, locks, free, banned, max_depth, max_recipes,
                    oc_tier=oc_tier)
        p.want_export = bool(export)
        best, second = p.plan(runner_up=runner_up)
        return p.text(best, second), False
    except (PlanError, ValueError) as e:
        return "plan_chain: %s" % e, True
