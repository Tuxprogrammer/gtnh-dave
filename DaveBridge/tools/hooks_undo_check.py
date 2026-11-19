#!/usr/bin/env python3
"""Live check of event hooks and undo (PROTOCOL.md 1.9 and 1.10) against a LOCAL dev server. Never a real server.

Uses the agent WebSocket, a console (RCON, or with --op-bot a scripted op player who is then also the asking player
and clicks its own confirms) and scripted 1.7.10 clients from e2e_chat_test.py (for the login and chat hooks). It
changes blocks around a point 40 above spawn and puts them back.

    python3 -I DaveBridge/tools/hooks_undo_check.py --config run/server/config/davebridge.cfg \\
        --rcon 127.0.0.1:25575 --rcon-password-file <file> --mc-port 25565 --phase before --stop
    (start the server again)
    python3 -I DaveBridge/tools/hooks_undo_check.py ... --phase after

    or, where RCON does not work (the dev run tasks' RCON drops every connection after login):
    python3 -I DaveBridge/tools/hooks_undo_check.py --config ... --op-bot GymBot --mc-port 25565 --phase before
    (GymBot in run/server/ops.json; its offline UUID is a v3 UUID of "OfflinePlayer:GymBot")

--phase before: hooks (login, chat, poll on a block, refused scripts, a failing script that gets disabled), confirmed
writes with snapshots (blocks, a chest with items, a chest broken so it drops them, an inventory edit through
dave.te), undo through the agent's confirm and through /dave undo, conflicts, redo. It leaves one poll hook and one
snapshot behind for --phase after, which checks they survived the restart, that a notification fired while no agent
was connected is delivered on connect, and undoes the saved snapshot from disk. Needs online-mode=false (and
enable-rcon=true without --op-bot). Prints PASS/FAIL lines; exits 1 on any failure.
"""

import argparse
import asyncio
import json
import os
import sys
from pathlib import Path

sys.path.insert(0, os.path.dirname(os.path.abspath(__file__)))
from e2e_chat_test import Bot, flatten  # noqa: E402
from eval_check import RESULTS, Agent, Rcon, WebSocket, check, read_token  # noqa: E402

ASKER = "Gym"   # the asking player; with --op-bot, the bot itself (it clicks its own confirms)


class RconConsole:
    def __init__(self, rcon):
        self.rcon = rcon

    async def command(self, cmd):
        return self.rcon.command(cmd)


class BotConsole:
    """Console commands typed by an op'd scripted player, for servers where RCON is not usable. Returns the chat
    lines the command answered with."""

    def __init__(self, bot):
        self.bot = bot

    async def command(self, cmd, wait=1.0):
        while not self.bot.chat.empty():
            self.bot.chat.get_nowait()
        await self.bot.say("/" + cmd)
        await asyncio.sleep(wait)
        lines = []
        while not self.bot.chat.empty():
            lines.append(flatten(self.bot.chat.get_nowait()))
        return "\n".join(lines)


async def connect(url, token):
    ws = await WebSocket.connect(url, headers={"Authorization": "Bearer " + token})
    hello = json.loads(await ws.recv())
    return ws, hello, Agent(ws)


async def confirmed_write(a, rcon, script, summary, params=None, **extra):
    """A write eval confirmed from the console; returns its eval_result."""
    eid = "w-%d" % (len(RESULTS) + 1)
    task = asyncio.create_task(a.eval(script, mode="write", player=ASKER, summary=summary, params=params or {},
                                      id=eid, **extra))
    pending = await a.wait_event(lambda m: m.get("type") == "eval_pending" and m.get("id") == eid)
    await rcon.command("dave confirm " + pending["confirmId"])
    return await task


async def read(a, script, params=None):
    r = await a.eval(script, params=params or {})
    if not r.get("ok"):
        raise AssertionError("read failed: %s" % r)
    return r["result"]


STATE = """
def w = world
def at = { int x, int y, int z -> def b = w.getBlock(x, y, z); def n = Block.blockRegistry.getNameForObject(b)
           n == null ? 'minecraft:air' : n.toString() }
def p = params
def te = w.getTileEntity(p.x as int, p.y as int, (p.z as int) + 4)
def bb = AxisAlignedBB.getBoundingBox(p.x - 4, p.y - 4, p.z - 4, p.x + 5, p.y + 8, p.z + 9)
[glass: at(p.x as int, p.y as int, p.z as int),
 stone: (1..3).collect { at(p.x as int, p.y as int, (p.z as int) + it) },
 chest: at(p.x as int, p.y as int, (p.z as int) + 4),
 slot0: te instanceof IInventory && te.getStackInSlot(0) != null ? [te.getStackInSlot(0).getItem().getUnlocalizedName(),
         te.getStackInSlot(0).stackSize] : null,
 items: w.getEntitiesWithinAABB(net.minecraft.entity.item.EntityItem, bb).size()]
"""


async def before(a, rcon, args, P):
    accepts = set(a.hello.get("accepts") or [])
    need = {"hook_add", "hook_list", "hook_remove", "snapshot_list", "snapshot_get", "undo"}
    check("hello advertises hooks and undo", need <= accepts, sorted(accepts))
    check("server is DaveBridge 0.3.0 or newer", a.hello.get("version", "") >= "0.3.0", a.hello.get("version"))

    # ---- hooks: refusals
    r = await a.request({"type": "hook_add", "hook": {"event": "poll", "owner": ASKER, "script": "world.setBlockToAir(0,1,0)"}})
    check("a hook script that changes the world is refused", r.get("code") == "bad_request"
          and "must not change the world" in r.get("message", ""), r)
    r = await a.request({"type": "hook_add", "hook": {"event": "poll", "owner": ASKER, "script": "null.foo()"}})
    check("a condition that fails on its first run is refused", r.get("code") == "bad_request"
          and "first run" in r.get("message", ""), r)
    r = await a.request({"type": "hook_add", "hook": {"event": "poll", "owner": ASKER, "script": "true", "everyTicks": 1}})
    check("a poll faster than hookMinPollTicks is refused", r.get("code") == "bad_request", r)

    # ---- login and chat hooks with a scripted client
    r = await a.request({"type": "hook_add", "hook": {"event": "player_join", "owner": ASKER, "name": "bot joins",
                                                      "note": "greet the bot", "filter": {"player": "HookBot"}}})
    join_id = (r.get("hook") or {}).get("id")
    check("hook_add player_join", r.get("type") == "ack" and join_id, r)
    r = await a.request({"type": "hook_add", "hook": {"event": "chat", "owner": ASKER, "once": False,
                                                      "cooldownSeconds": 0, "filter": {"contains": "pineapple"},
                                                      "script": "facts.message.length() > 3"}})
    chat_id = (r.get("hook") or {}).get("id")
    check("hook_add chat with a Groovy filter", r.get("type") == "ack" and chat_id, r)
    bot = Bot("127.0.0.1", args.mc_port, "HookBot")
    await bot.connect()
    ev = await a.wait_event(lambda m: m.get("type") == "hook_fired" and m.get("hookId") == join_id, 20)
    check("player_join hook fires on login", ev.get("detail", {}).get("player") == "HookBot" and ev.get("removed")
          and ev.get("note") == "greet the bot" and ev.get("owner") == ASKER and ev.get("ownerUuid"), ev)
    await asyncio.sleep(1)
    await bot.say("I like pineapple pizza")
    ev = await a.wait_event(lambda m: m.get("type") == "hook_fired" and m.get("hookId") == chat_id, 20)
    check("chat hook fires on a matching message", "pineapple" in ev.get("detail", {}).get("message", "")
          and ev.get("removed") is False, ev)
    await bot.say("no fruit here")
    await asyncio.sleep(1.5)
    check("chat hook ignores other messages",
          not any(m.get("hookId") == chat_id for m in a.events if m.get("type") == "hook_fired"))
    await bot.close()
    r = await a.request({"type": "hook_remove", "id": chat_id})
    check("hook_remove", r.get("type") == "ack" and r.get("removed") == [chat_id], r)

    # ---- poll hook on a block
    cond = "world.getBlock(params.x as int, params.y as int, params.z as int) == Blocks.gold_block"
    r = await a.request({"type": "hook_add", "hook": {
        "event": "poll", "owner": ASKER, "name": "gold placed", "note": "tell Gym the gold block is there",
        "script": cond, "params": {"x": P["x"] + 2, "y": P["y"], "z": P["z"]}, "everyTicks": 20, "once": False,
        "cooldownSeconds": 0, "expiresInSeconds": 3600}})
    poll_id = (r.get("hook") or {}).get("id")
    check("hook_add poll runs the condition once", poll_id and r.get("firstRun", {}).get("truthy") is False, r)
    w = await confirmed_write(a, rcon, "world.setBlock(params.x as int, params.y as int, params.z as int, Blocks.gold_block)",
                              "Put a gold block in the sky", {"x": P["x"] + 2, "y": P["y"], "z": P["z"]})
    ev = await a.wait_event(lambda m: m.get("type") == "hook_fired" and m.get("hookId") == poll_id, 20)
    check("poll hook fires when the condition turns true", ev.get("event") == "poll" and ev.get("fireCount") == 1, ev)
    await asyncio.sleep(2.5)
    check("an edge hook does not fire again while it stays true",
          not any(m.get("hookId") == poll_id for m in a.events if m.get("type") == "hook_fired"))
    gold_snapshot = w.get("snapshot")

    # ---- a hook whose condition starts failing gets disabled
    r = await a.request({"type": "hook_add", "hook": {
        "event": "poll", "owner": ASKER, "name": "flaky", "everyTicks": 20,
        "script": "if (state.n == null) { state.n = 1; return false }; throw new RuntimeException('boom')"}})
    flaky = (r.get("hook") or {}).get("id")
    ev = await a.wait_event(lambda m: m.get("type") == "hook_disabled" and m.get("hookId") == flaky, 30)
    check("a hook that keeps failing is disabled", "boom" in ev.get("error", ""), ev)
    lst = await a.request({"type": "hook_list", "owner": ASKER})
    hooks = {h["id"]: h for h in lst.get("hooks", [])}
    check("hook_list shows the poll hook and the disabled one", poll_id in hooks and hooks.get(flaky, {}).get("disabled"),
          list(hooks))
    out = await rcon.command("dave hooks")
    check("/dave hooks lists them", poll_id in out and "DISABLED" in out, out.strip()[:300])
    await rcon.command("dave hooks remove " + flaky)

    # ---- undo: blocks, a chest with items, a broken block
    base = await confirmed_write(a, rcon, "world.setBlock(params.x as int, params.y as int, params.z as int, Blocks.glass); 'ok'",
                                 "Put a glass block in the sky", P)
    check("a confirmed write records a snapshot", base.get("ok") and base.get("snapshot") and base.get("snapshotBlocks") == 1, base)
    script = """
def x = params.x as int, y = params.y as int, z = params.z as int
(1..3).each { world.setBlock(x, y, z + it, Blocks.stone) }
world.setBlock(x, y, z + 4, Blocks.chest)
world.getTileEntity(x, y, z + 4).setInventorySlotContents(0, new ItemStack(Items.diamond, 5))
world.setBlockToAir(x, y, z)
'built'
"""
    w = await confirmed_write(a, rcon, script, "Build three stone, a chest of diamonds, remove the glass", P)
    snap = w.get("snapshot")
    check("the build is journaled (5 positions)", w.get("ok") and snap and w.get("snapshotBlocks") == 5, w)
    st = await read(a, STATE, P)
    check("the build happened", st["glass"] == "minecraft:air" and st["stone"] == ["minecraft:stone"] * 3
          and st["chest"] == "minecraft:chest" and st["slot0"] and st["slot0"][1] == 5, st)
    g = await a.request({"type": "snapshot_get", "snapshot": snap})
    s = g.get("snapshot") or {}
    check("snapshot_get previews the changes", s.get("blocks") == 5 and len(s.get("changes", [])) == 5
          and s.get("changedSince") == 0, g)
    lst = await a.request({"type": "snapshot_list", "player": ASKER})
    check("snapshot_list lists it first", lst.get("snapshots", [{}])[0].get("id") == snap, lst)

    task = asyncio.create_task(a.request({"type": "undo", "snapshot": snap, "player": ASKER}, timeout=60))
    pend = await a.wait_event(lambda m: m.get("type") == "undo_pending" and m.get("snapshot") == snap)
    await rcon.command("dave confirm " + pend["confirmId"])
    res = await task
    check("undo through the confirm restores 5 blocks", res.get("type") == "undo_result" and res.get("ok")
          and res.get("restored") == 5 and res.get("confirmed") and res.get("redo"), res)
    st = await read(a, STATE, P)
    check("the world is back (glass, no stone, no chest) and nothing was dropped", st["glass"] == "minecraft:glass"
          and st["stone"] == ["minecraft:air"] * 3 and st["chest"] == "minecraft:air" and st["items"] == 0, st)
    redo = res.get("redo")
    again = await a.request({"type": "undo", "snapshot": snap, "player": ASKER})
    check("an undone snapshot is not undone twice", again.get("code") == "bad_request" and redo in again.get("message", ""), again)
    out = await rcon.command("dave undo " + redo)
    st = await read(a, STATE, P)
    check("/dave undo of the undo record redoes the build", "Undid" in out and st["chest"] == "minecraft:chest"
          and st["slot0"] and st["slot0"][1] == 5 and st["glass"] == "minecraft:air", "%s | %s" % (out.strip(), st))

    # ---- inventory edit through dave.te, then breaking the chest (it drops its items)
    inv = """def te = dave.te(0, params.x as int, params.y as int, (params.z as int) + 4)
te.setInventorySlotContents(0, new ItemStack(Items.gold_ingot, 64)); 'edited'"""
    w_inv = await confirmed_write(a, rcon, inv, "Fill the chest with gold", P)
    check("an inventory edit through dave.te is journaled", w_inv.get("snapshotBlocks") == 1, w_inv)
    brk = "world.setBlockToAir(params.x as int, params.y as int, (params.z as int) + 4); 'broken'"
    w_brk = await confirmed_write(a, rcon, brk, "Break the chest", P)
    st = await read(a, STATE, P)
    check("breaking the chest spilled its items, and the journal saw the drops", st["items"] > 0
          and w_brk.get("snapshotEntities", 0) > 0, "%s | %s" % (st, w_brk))
    out = await rcon.command("dave undo " + w_brk["snapshot"])
    st = await read(a, STATE, P)
    check("undo puts the chest back with its gold and removes the dropped items", st["chest"] == "minecraft:chest"
          and st["slot0"] == ["item.ingotGold", 64] and st["items"] == 0, "%s | %s" % (out.strip(), st))
    out = await rcon.command("dave undo " + w_inv["snapshot"])
    st = await read(a, STATE, P)
    check("undoing the inventory edit restores the diamonds", st["slot0"] == ["item.diamond", 5], "%s | %s" % (out.strip(), st))

    # ---- conflict: a position changed since the snapshot is skipped unless forced
    w_c = await confirmed_write(a, rcon, "world.setBlock(params.x as int, (params.y as int) + 1, params.z as int, Blocks.stone); 'ok'",
                                "One stone above the glass", P)
    await confirmed_write(a, rcon, "world.setBlock(params.x as int, (params.y as int) + 1, params.z as int, Blocks.brick_block); 'ok'",
                          "Swap it for bricks", P)
    out = await rcon.command("dave undo " + w_c["snapshot"])
    check("a position changed since is skipped as a conflict", "1 skipped" in out, out.strip())
    out = await rcon.command("dave undo " + w_c["snapshot"] + " force")
    blk = await read(a, "Block.blockRegistry.getNameForObject(world.getBlock(params.x as int, (params.y as int) + 1, params.z as int)).toString()", P)
    check("force restores it anyway", blk == "minecraft:air", "%s | %s" % (out.strip(), blk))

    # ---- the gold block from the poll test: undo it through the agent, leave the poll hook for --phase after
    task = asyncio.create_task(a.request({"type": "undo", "snapshot": "last", "player": ASKER, "of": "*"}, timeout=60))
    pend = await a.wait_event(lambda m: m.get("type") == "undo_pending")
    await rcon.command("dave cancel " + pend["confirmId"])
    res = await task
    check("a cancelled undo changes nothing", res.get("errorType") == "cancelled", res)
    out = await rcon.command("dave undo " + gold_snapshot)
    check("/dave undo removes the gold block", "1 block restored" in out, out.strip())
    out = await rcon.command("dave snapshots " + ASKER)
    check("/dave snapshots lists the snapshots", snap in out and "undone" in out, out.strip()[:400])
    # A snapshot to undo after the restart, from disk:
    w_keep = await confirmed_write(a, rcon, "world.setBlock(params.x as int, (params.y as int) + 2, params.z as int, Blocks.lapis_block); 'ok'",
                                   "Lapis above the glass", P)
    Path(args.state).write_text(json.dumps({"poll": poll_id, "keep": w_keep.get("snapshot"), "P": P}))
    print("left for --phase after: poll hook %s, snapshot %s" % (poll_id, w_keep.get("snapshot")))


async def after(a, rcon, args, saved, url, token):
    P = saved["P"]
    lst = await a.request({"type": "hook_list"})
    ids = [h["id"] for h in lst.get("hooks", [])]
    check("the poll hook survived the restart", saved["poll"] in ids, ids)
    await a.ws.close()
    a.reader.cancel()
    out = await rcon.command("dave hooks fire " + saved["poll"])
    check("/dave hooks fire with no agent connected", "test notification" in out, out.strip())
    ws, hello, a = await connect(url, token)
    a.hello = hello
    ev = await a.wait_event(lambda m: m.get("type") == "hook_fired" and m.get("hookId") == saved["poll"], 10)
    check("a notification queued while no agent was connected arrives on connect", ev.get("queued")
          and ev.get("detail", {}).get("test"), ev)
    await confirmed_write(a, rcon, "world.setBlock(params.x as int, params.y as int, params.z as int, Blocks.gold_block)",
                          "Gold again", {"x": P["x"] + 2, "y": P["y"], "z": P["z"]})
    ev = await a.wait_event(lambda m: m.get("type") == "hook_fired" and m.get("hookId") == saved["poll"]
                            and not m.get("detail", {}).get("test"), 20)
    check("the restored poll hook still fires (recompiled on load)", ev.get("event") == "poll", ev)
    r = await a.request({"type": "hook_remove", "owner": ASKER})
    check("hook_remove by owner", saved["poll"] in (r.get("removed") or []), r)
    out = await rcon.command("dave undo last " + ASKER)
    check("/dave undo last undoes the newest change (the gold)", "1 block restored" in out, out.strip())
    out = await rcon.command("dave undo " + saved["keep"])
    blk = await read(a, "Block.blockRegistry.getNameForObject(world.getBlock(params.x as int, (params.y as int) + 2, params.z as int)).toString()", P)
    check("a snapshot from before the restart is undone from disk", "1 block restored" in out and blk == "minecraft:air",
          "%s | %s" % (out.strip(), blk))
    # clean up the glass
    await confirmed_write(a, rcon, "world.setBlockToAir(params.x as int, params.y as int, params.z as int); 'ok'",
                          "Remove the test glass", P)


async def main(args):
    global ASKER
    token = read_token(args.config)
    url = args.url
    ws, hello, a = await connect(url, token)
    a.hello = hello
    bot = None
    if args.op_bot:
        # The bot types the console commands and is the asking player, so it confirms its own changes in game.
        ASKER = args.op_bot
        bot = Bot("127.0.0.1", args.mc_port, args.op_bot)
        await bot.connect()
        await asyncio.sleep(1)
        console = BotConsole(bot)
    else:
        host, port = args.rcon.rsplit(":", 1)
        console = RconConsole(Rcon(host, int(port), Path(args.rcon_password_file).read_text().strip()))
    r = await a.eval("def s = world.getSpawnPoint(); [s.posX, world.getTopSolidOrLiquidBlock(s.posX, s.posZ), s.posZ]")
    sx, top, sz = r["result"]
    P = {"x": sx + 3, "y": min(240, top + 40), "z": sz + 3}
    if args.phase == "before":
        await before(a, console, args, P)
    else:
        await after(a, console, args, json.loads(Path(args.state).read_text()), url, token)
    print("\nChecks: %d passed, %d failed" % (RESULTS.count(True), RESULTS.count(False)))
    if args.stop:
        print("stopping the server: %s" % (await console.command("stop")).strip())
    if bot:
        await bot.close()
    return 0 if all(RESULTS) else 1


if __name__ == "__main__":
    p = argparse.ArgumentParser()
    p.add_argument("--url", default="ws://127.0.0.1:25580")
    p.add_argument("--config", required=True)
    p.add_argument("--rcon", help="host:port; or use --op-bot")
    p.add_argument("--rcon-password-file")
    p.add_argument("--op-bot", help="a scripted player (an op in ops.json) types the console commands instead of RCON")
    p.add_argument("--stop", action="store_true", help="/stop the server at the end (graceful, saves everything)")
    p.add_argument("--mc-port", type=int, default=25565)
    p.add_argument("--phase", choices=["before", "after"], required=True)
    p.add_argument("--state", default="hooks_undo_check.state.json", help="where --phase before leaves its ids")
    sys.exit(asyncio.run(main(p.parse_args())))
