"""The daemon's local RPC socket: how the `bridge` MCP server (dave/bridge/server.py) reaches DaveBridge.

Claude starts the MCP server as its own stdio child, so it cannot share the daemon's WebSocket. Instead the daemon
listens on a unix socket in its run directory (`<run>/bridge.sock`, mode 0600, so only the daemon's user can
connect), and the MCP server sends each tool call there as one JSON line:

    -> {"id": 1, "op": "eval", "args": {"script": "...", "mode": "read"}}
    <- {"id": 1, "ok": true, "result": {...the bridge's reply...}}
    <- {"id": 1, "ok": false, "error": "bridge not connected"}

The daemon fills in what Claude must not choose: the asking player of the turn in flight (for confirms and the
`player` binding). Standard library only.
"""

import asyncio
import json
import logging
import os
import re
from pathlib import Path

from dave.bridge.queries import QueryError, Queries

log = logging.getLogger("dave")

MAX_LINE = 1024 * 1024
MAX_DATA_BYTES = 64 * 1024
TURN_ID_RE = re.compile(r"^t-[0-9a-f]{10}$")
NEEDS_030 = {"hook_add", "hook_list", "hook_remove", "snapshot_list", "snapshot_get", "undo"}


class RpcError(Exception):
    pass


def socket_path(cfg):
    from dave import config
    return config.bridge_socket(cfg)


class BridgeRPC:
    def __init__(self, daemon, path, queries):
        self.daemon = daemon
        self.path = Path(path)
        self.queries = queries if isinstance(queries, Queries) else Queries(queries)
        self.server = None
        self._tasks = set()

    async def start(self):
        self.path.parent.mkdir(parents=True, exist_ok=True)
        try:
            self.path.unlink()                    # a stale socket from a previous run
        except FileNotFoundError:
            pass
        old = os.umask(0o177)                     # created 0600 from the start, not chmod-ed afterwards
        try:
            self.server = await asyncio.start_unix_server(self._client, path=str(self.path), limit=MAX_LINE)
        finally:
            os.umask(old)
        os.chmod(self.path, 0o600)
        log.info("bridge RPC listening on %s", self.path)

    async def stop(self):
        if self.server:
            self.server.close()
            await self.server.wait_closed()
            self.server = None
        try:
            self.path.unlink()
        except FileNotFoundError:
            pass

    async def _client(self, reader, writer):
        lock = asyncio.Lock()
        mine = set()
        try:
            while True:
                try:
                    line = await reader.readline()
                except ValueError:                # over MAX_LINE
                    break
                if not line:
                    break
                task = asyncio.create_task(self._serve(line, writer, lock))
                for s in (mine, self._tasks):
                    s.add(task)
                    task.add_done_callback(s.discard)
            if mine:                              # the caller half-closed: finish its calls, then hang up
                await asyncio.gather(*mine, return_exceptions=True)
        finally:
            writer.close()

    async def _serve(self, line, writer, lock):
        rid = None
        try:
            req = json.loads(line)
            rid = req.get("id")
            result = await self.handle(req.get("op"), req.get("args") or {})
            reply = {"id": rid, "ok": True, "result": result}
        except (RpcError, QueryError) as e:
            reply = {"id": rid, "ok": False, "error": str(e)}
        except asyncio.TimeoutError:
            reply = {"id": rid, "ok": False, "error": "DaveBridge did not answer in time"}
        except Exception as e:                    # noqa: BLE001 - every failure goes back to the caller
            log.exception("bridge RPC call failed")
            reply = {"id": rid, "ok": False, "error": "%s: %s" % (type(e).__name__, e)}
        async with lock:
            try:
                writer.write((json.dumps(reply) + "\n").encode("utf-8"))
                await writer.drain()
            except (ConnectionError, OSError):
                pass

    # ---- ops

    def asker(self):
        turn = self.daemon.in_flight
        return turn.player if turn else None

    async def request(self, msg, timeout):
        from dave.daemon.main import BridgeDown   # late import: main imports this module
        bridge = self.daemon.bridge
        if not bridge.ready.is_set():
            raise RpcError("DaveBridge is not connected right now")
        accepts = (bridge.hello or {}).get("accepts") or []
        if msg["type"] not in accepts:
            need = "0.3.0" if msg["type"] in NEEDS_030 else "0.2.0"
            raise RpcError("this server's DaveBridge does not support %s (it needs DaveBridge %s or newer)"
                           % (msg["type"], need))
        try:
            reply = await bridge.request(msg, timeout=timeout)
        except BridgeDown as e:
            raise RpcError("DaveBridge connection lost: %s" % e)
        if reply.get("type") == "error":
            raise RpcError("%s: %s" % (reply.get("code"), reply.get("message")))
        reply.pop("reqId", None)
        reply.pop("ts", None)
        return reply

    async def handle(self, op, a):
        if op == "eval":
            return await self.eval(a)
        if op == "describe":
            msg = {"type": "describe", "target": a.get("target") or "looking"}
            for k_in, k_out in (("x", "x"), ("y", "y"), ("z", "z"), ("dim", "dim"), ("entity_id", "entityId"),
                                ("reach", "reach")):
                if a.get(k_in) is not None:
                    msg[k_out] = a[k_in]
            player = a.get("player") or self.asker()
            if player:
                msg["player"] = player
            return await self.request(msg, timeout=30)
        if op == "say":
            return await self.say(a)
        if op == "surface_attachment":
            return self.surface_attachment(a)
        if op == "list_players":
            return await self.request({"type": "list_players"}, timeout=30)
        if op == "lesson":
            player = a.get("player") or self.asker()
            if not player or not isinstance(a.get("lesson"), dict):
                raise RpcError("lesson needs a player (default: the asker) and a lesson object with steps")
            return await self.request({"type": "lesson", "player": player, "lesson": a["lesson"]}, timeout=15)
        if op == "lesson_stop":
            player = a.get("player") or self.asker()
            if not player:
                raise RpcError("lesson_stop needs a player (default: the asker)")
            msg = {"type": "lesson_stop", "player": player}
            if a.get("id"):
                msg["id"] = str(a["id"])
            return await self.request(msg, timeout=10)
        if op == "hook_add":
            return await self.hook_add(a)
        if op == "hook_list":
            msg = {"type": "hook_list"}
            if a.get("owner"):
                msg["owner"] = a["owner"]
            return await self.request(msg, timeout=30)
        if op == "hook_remove":
            if not a.get("id") and not a.get("owner"):
                raise RpcError("hook_remove needs id (one hook) or owner (all of that player's hooks)")
            msg = {"type": "hook_remove"}
            msg.update({"id": str(a["id"])} if a.get("id") else {"owner": a["owner"]})
            return await self.request(msg, timeout=30)
        if op == "snapshot_list":
            msg = {"type": "snapshot_list", "limit": int(a.get("limit") or 20)}
            if a.get("player"):
                msg["player"] = a["player"]
            return await self.request(msg, timeout=30)
        if op == "snapshot_get":
            msg = {"type": "snapshot_get", "snapshot": str(a.get("snapshot") or "last")}
            msg["of"] = a.get("of") or self.asker() or "*"
            return await self.request(msg, timeout=30)
        if op == "undo":
            player = a.get("player") or self.asker()
            if not player:
                raise RpcError("undo needs the asking player, who confirms it in chat")
            confirm = int(a.get("confirm_timeout_seconds") or 120)
            msg = {"type": "undo", "snapshot": str(a.get("snapshot") or "last"), "player": player,
                   "force": bool(a.get("force")), "confirmTimeoutSeconds": confirm}
            if a.get("of"):
                msg["of"] = a["of"]
            # the player's click, then the restore over a few ticks
            return await self.request(msg, timeout=confirm + 180)
        if op == "query_list":
            return {"queries": [{k: q.get(k) for k in ("name", "description", "mode", "side", "params", "origin")}
                                for q in self.queries.list()]}
        if op == "query_get":
            q = self.queries.get(a.get("name"))
            return {k: q.get(k) for k in ("name", "description", "mode", "side", "params", "origin", "script")}
        if op == "query_save":
            return await asyncio.to_thread(
                self.queries.save, a.get("name"), a.get("description"), a.get("script"), a.get("mode") or "read",
                a.get("side") or "server", a.get("params_doc"), a.get("timeout_ms"), bool(a.get("replace")))
        if op == "query_run":
            q = await asyncio.to_thread(self.queries.get, a.get("name"))
            args = {"script": q["script"], "mode": q["mode"], "side": q["side"], "params": a.get("params") or {},
                    "timeout_ms": a.get("timeout_ms") or q.get("timeout_ms"), "id": "q:" + q["name"],
                    "summary": a.get("summary") or q["description"], "player": a.get("player")}
            reply = await self.eval(args)
            reply["query"] = q["name"]
            return reply
        raise RpcError("unknown op %r" % (op,))

    # Tool argument names (snake_case) -> the hook object's fields (DaveBridge PROTOCOL.md 1.9).
    HOOK_FIELDS = {"event": "event", "name": "name", "note": "note", "notify": "notify", "script": "script",
                   "params": "params", "filter": "filter", "every_ticks": "everyTicks", "once": "once",
                   "cooldown_seconds": "cooldownSeconds", "edge": "edge",
                   "fire_if_true_at_start": "fireIfTrueAtStart", "expires_in_seconds": "expiresInSeconds"}

    async def say(self, a):
        """A mid-turn chat line. During a panel's ask (a surface turn) a say to the asker (or with no target, or @a)
        goes privately to that panel as an interim reply (final false), with optional `data`; it is never public."""
        data = a.get("data")
        if data is not None and not isinstance(data, dict):
            raise RpcError("data must be a JSON object")
        if data is not None and len(json.dumps(data, separators=(",", ":")).encode("utf-8")) > MAX_DATA_BYTES:
            raise RpcError("data is over 64 KiB")
        markup = a.get("markup")
        turn = self.daemon.in_flight
        target = a.get("target") or "@a"
        if turn is not None and turn.surface and target in ("@a", "", turn.player):
            if turn.cancelled:
                return {"ok": True, "recipients": 0, "note": "the player cancelled this ask; nothing was sent"}
            if not markup and data is None:
                raise RpcError("markup or data is required")
            msg = {"type": "say", "target": turn.player, "surface": turn.surface, "requestId": turn.request_id,
                   "final": False}
            if markup:
                msg["markup"] = markup
            if data is not None:
                msg["data"] = data
            reply = await self.request(msg, timeout=30)
            reply["note"] = "sent privately to %s's %s panel (interim)" % (turn.player, turn.surface)
            return reply
        if not markup:
            raise RpcError("markup is required")
        reply = await self.request({"type": "say", "target": target, "markup": markup}, timeout=30)
        if data is not None:
            reply["note"] = "data was dropped: it only reaches a panel, while answering that panel's ask"
        return reply

    def surface_attachment(self, a):
        """Where a panel's attachment (a plan snapshot) is stashed. Default: the turn in flight's, else the asker's
        newest; `turn` picks a turn id. Returns path (a 0600 JSON file), turn, surface, requestId and bytes."""
        from dave.daemon.main import surface_dir   # late import: main imports this module
        tid = a.get("turn")
        if tid is not None:
            if not isinstance(tid, str) or not TURN_ID_RE.match(tid):
                raise RpcError("turn must be a turn id like t-0123456789")
            path = surface_dir(self.daemon.cfg) / (tid + ".json")
            found = next((v for v in self.daemon.attachments.values() if v[0] == tid), None)
            meta = {"turn": tid, "surface": found[1] if found else None, "requestId": found[2] if found else None}
        else:
            turn = self.daemon.in_flight
            if turn is None:
                raise RpcError("no turn is running; pass turn")
            if turn.attachment:
                path, meta = turn.attachment, {"turn": turn.id, "surface": turn.surface, "requestId": turn.request_id}
            elif turn.key in self.daemon.attachments:
                tid, surface, rid, path = self.daemon.attachments[turn.key]
                meta = {"turn": tid, "surface": surface, "requestId": rid, "earlier": True}
            else:
                raise RpcError("%s has not attached anything" % turn.player)
        try:
            size = os.stat(path).st_size
        except OSError:
            raise RpcError("that attachment is gone (only the newest 50 are kept)")
        return dict(meta, path=str(path), bytes=size)

    async def hook_add(self, a):
        """The owner defaults to the asker: the player the hook reports back to."""
        if not a.get("event"):
            raise RpcError("hook_add needs event (player_join, player_leave, player_death, player_dimension, "
                           "entity_death, block_break, block_place, chat, quest_complete or poll)")
        owner = a.get("owner") or self.asker()
        if not owner:
            raise RpcError("hook_add needs an owner: the player the hook is for (default: the asker)")
        hook = {"owner": owner}
        for k_in, k_out in self.HOOK_FIELDS.items():
            if a.get(k_in) is not None:
                hook[k_out] = a[k_in]
        return await self.request({"type": "hook_add", "hook": hook}, timeout=60)

    async def eval(self, a):
        script = a.get("script")
        if not isinstance(script, str) or not script.strip():
            raise RpcError("script is required")
        side = a.get("side") or "server"
        mode = a.get("mode") or "read"
        player = a.get("player") or self.asker()
        timeout_ms = a.get("timeout_ms")
        msg = {"script": script, "params": a.get("params") or {}}
        if a.get("id"):
            msg["id"] = str(a["id"])
        if timeout_ms:
            msg["timeoutMs"] = int(timeout_ms)
        if side == "client":
            if not player:
                raise RpcError("client eval needs a player (the asker of this turn)")
            msg.update(type="client_eval", player=player, mode="groovy")
            wait = 60 + (int(timeout_ms or 0) // 1000)
        elif side == "server":
            msg.update(type="eval", mode=mode)
            if player:
                msg["player"] = player
            if mode == "write":
                if not player:
                    raise RpcError("a write needs the asking player, who confirms it in chat")
                if not (a.get("summary") or "").strip():
                    raise RpcError("a write needs summary: one line telling the player what will change")
                msg["summary"] = a["summary"]
                if a.get("snapshot"):
                    msg["snapshot"] = a["snapshot"]   # regions to save for undo before it runs
                confirm = int(a.get("confirm_timeout_seconds") or 120)
                msg["confirmTimeoutSeconds"] = confirm
                wait = confirm + 60
            else:
                wait = 60 + (int(timeout_ms or 0) // 1000)
        else:
            raise RpcError("side must be server or client")
        return await self.request(msg, timeout=wait)
