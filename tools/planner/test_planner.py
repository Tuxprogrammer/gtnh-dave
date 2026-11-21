"""Offline tests for tools/planner: the planner MCP server against a local fake of the site's agent API.

    python3 -I tools/planner/test_planner.py

testdata/*.json are the site's own request/response examples (docs/agent-api/examples/ in gtnh-factory-flow), so
the built endpoints are checked against real answers. Nothing here contacts a real server.
"""

import json
import os
import socket
import socketserver
import stat
import subprocess
import sys
import tempfile
import threading
import time
import unittest
from http.server import BaseHTTPRequestHandler, ThreadingHTTPServer
from pathlib import Path

HERE = Path(__file__).resolve().parent
sys.path.insert(0, str(HERE))
import server  # noqa: E402

TOKEN = "test-token-0123456789"
EXAMPLES = {n: json.loads((HERE / "testdata" / (n + ".json")).read_text(encoding="utf-8"))
            for n in ("resources-search", "recipes-search", "recipes-get")}


class FakeSite:
    """The agent API on 127.0.0.1: checks the bearer token, records each body, answers from `routes`.

    routes: endpoint -> (status, body bytes or JSON-able, content type) or a callable(body) returning that.
    An endpoint not in routes answers a bare-text 404, like an unbuilt path.
    """

    def __init__(self):
        self.routes, self.requests = {}, []
        site = self

        class Handler(BaseHTTPRequestHandler):
            def log_message(self, *a):
                pass

            def do_POST(self):
                body = self.rfile.read(int(self.headers.get("Content-Length") or 0))
                endpoint = self.path[len(server.API_PATH):] if self.path.startswith(server.API_PATH) else self.path
                site.requests.append({"endpoint": endpoint, "auth": self.headers.get("Authorization"),
                                      "type": self.headers.get("Content-Type"), "body": json.loads(body or b"null")})
                if self.headers.get("Authorization") != "Bearer " + TOKEN:
                    return self.send(401, {"error": {"code": "unauthorized", "message": "Missing or wrong token."}})
                route = site.routes.get(endpoint)
                if route is None:
                    return self.send(404, b"Not Found", "text/plain")
                if callable(route):
                    route = route(site.requests[-1]["body"])
                self.send(*route)

            def send(self, status, body, ctype="application/json", headers=()):
                if not isinstance(body, bytes):
                    body = json.dumps(body).encode()
                self.send_response(status)
                self.send_header("Content-Type", ctype)
                self.send_header("Content-Length", str(len(body)))
                for k, v in headers:
                    self.send_header(k, v)
                self.end_headers()
                try:
                    self.wfile.write(body)
                except (BrokenPipeError, ConnectionResetError):
                    pass

        self.httpd = ThreadingHTTPServer(("127.0.0.1", 0), Handler)
        self.httpd.daemon_threads = True
        self.url = "http://127.0.0.1:%d" % self.httpd.server_address[1]
        threading.Thread(target=self.httpd.serve_forever, args=(0.05,), daemon=True).start()

    def close(self):
        self.httpd.shutdown()
        self.httpd.server_close()


class FakeDaemon:
    """The daemon's RPC socket, answering only surface_attachment with `reply`."""

    def __init__(self, path, reply):
        self.reply, self.calls = reply, []
        fake = self

        class Handler(socketserver.StreamRequestHandler):
            def handle(self):
                req = json.loads(self.rfile.readline())
                fake.calls.append(req)
                out = dict(fake.reply, id=req["id"]) if req.get("op") == "surface_attachment" else \
                    {"id": req["id"], "ok": False, "error": "unknown op"}
                self.wfile.write((json.dumps(out) + "\n").encode())

        self.srv = socketserver.ThreadingUnixStreamServer(str(path), Handler)
        self.srv.daemon_threads = True
        threading.Thread(target=self.srv.serve_forever, args=(0.05,), daemon=True).start()

    def close(self):
        self.srv.shutdown()
        self.srv.server_close()


class Base(unittest.TestCase):
    def setUp(self):
        self.tmp = tempfile.TemporaryDirectory()
        self.dir = Path(self.tmp.name)
        self.token_file = self.dir / "token"
        self.token_file.write_text(TOKEN + "\n")
        self.site = FakeSite()
        self.env = {"PLANNER_API_URL": self.site.url, "PLANNER_API_TOKEN_FILE": str(self.token_file)}
        self.service = server.Service(self.env)
        self.logs = []
        self._log, server.log = server.log, self.logs.append

    def tearDown(self):
        server.log = self._log
        self.site.close()
        self.tmp.cleanup()

    def call(self, tool, **args):
        """(text, isError) through the JSON-RPC handler, as Claude calls it."""
        r = server.handle(self.service, {"jsonrpc": "2.0", "id": 1, "method": "tools/call",
                                         "params": {"name": tool, "arguments": args}})
        res = r["result"]
        text = res["content"][0]["text"]
        self.assertNotIn(TOKEN, text)
        return text, res["isError"]


class Endpoints(Base):
    def test_built_endpoints_against_the_site_examples(self):
        for name, tool in (("resources-search", "resources_search"), ("recipes-search", "recipes_search"),
                           ("recipes-get", "recipes_get")):
            ex = EXAMPLES[name]
            self.site.routes[ex["endpoint"]] = (ex["status"], ex["response"])
            text, err = self.call(tool, **ex["request"])
            self.assertFalse(err, text)
            self.assertEqual(json.loads(text), ex["response"])
            self.assertEqual(text, server.compact(ex["response"]))   # compact, unindented
            last = self.site.requests[-1]
            self.assertEqual(last["endpoint"], ex["endpoint"])
            self.assertEqual(last["body"], ex["request"])      # the example's request, field for field
            self.assertEqual(last["auth"], "Bearer " + TOKEN)
            self.assertEqual(last["type"], "application/json")
        self.assertTrue(all(TOKEN not in line for line in self.logs))

    def test_every_tool_maps_to_its_endpoint(self):
        args = {"resources_search": {"text": "x"}, "recipes_search": {"text": "x"}, "recipes_get": {"recipeId": "r"},
                "plan_create": {"name": "n"}, "plan_import": {"snapshot": {}},
                "plan_add_cards": {"planId": "p", "cards": [{"recipeId": "r"}]},
                "plan_remove_cards": {"planId": "p", "cardIds": ["c"]},
                "plan_connect": {"planId": "p", "from": {"cardId": "a", "resource": "water"},
                                 "to": {"cardId": "b", "resource": {"id": "water", "kind": "fluid"}}},
                "plan_disconnect": {"planId": "p", "wireId": "w"},
                "plan_set_targets": {"planId": "p", "targets": [{"resource": "x", "rate": 1.5}]},
                "plan_get": {"planId": "p"}, "plan_solve": {"planId": "p", "fields": ["summary"]},
                "plan_expand_inputs": {"planId": "p", "max": 2}, "plan_diff": {"planId": "p"},
                "plan_deliver": {"planId": "p", "player": {"name": "Steve", "uuid": "u"}}}
        self.assertEqual(set(args), set(server.ENDPOINTS))
        for tool, a in args.items():
            endpoint = server.ENDPOINTS[tool][0]
            self.site.routes[endpoint] = (200, {"ok": tool})
            text, err = self.call(tool, **a)
            self.assertFalse(err, text)
            self.assertEqual(json.loads(text), {"ok": tool})
            self.assertEqual(self.site.requests[-1]["endpoint"], endpoint)
            self.assertEqual(self.site.requests[-1]["body"], a)

    def test_player_is_passed_through_and_nulls_dropped(self):
        self.site.routes["plans/create"] = (200, {"planId": "p1"})
        text, err = self.call("plan_create", name="Titanium", constraints=None,
                              player={"name": "Steve", "uuid": "00000000-0000-0000-0000-000000000001"})
        self.assertEqual(json.loads(text), {"planId": "p1"})
        self.assertEqual(self.site.requests[-1]["body"], {
            "name": "Titanium", "player": {"name": "Steve", "uuid": "00000000-0000-0000-0000-000000000001"}})

    def test_missing_required_argument_is_caught_locally(self):
        text, err = self.call("plan_connect", planId="p")
        self.assertTrue(err)
        self.assertIn("from", text)
        self.assertEqual(self.site.requests, [])

    def test_schemas(self):
        names = [t["name"] for t in server.TOOLS]
        self.assertEqual(len(names), len(set(names)))
        self.assertEqual(len(names), 16)
        for t in server.TOOLS:
            s = t["inputSchema"]
            self.assertIs(s["additionalProperties"], False, t["name"])
            self.assertIn("player", s["properties"], t["name"])
            self.assertTrue(set(s["required"]) <= set(s["properties"]), t["name"])
        deliver = next(t for t in server.TOOLS if t["name"] == "plan_deliver")["inputSchema"]
        self.assertEqual(deliver["properties"]["player"]["required"], ["name", "uuid"])
        self.assertIn("player", deliver["required"])
        self.assertIn("/gtnhplanner open", next(t for t in server.TOOLS if t["name"] == "plan_deliver")["description"])


class Errors(Base):
    def test_error_envelope(self):
        self.site.routes["plans/get"] = (404, {"error": {"code": "plan_not_found", "message": "No such draft."}})
        text, err = self.call("plan_get", planId="gone")
        self.assertTrue(err)
        self.assertEqual(text, "planner error plan_not_found: No such draft.")

    def test_wrong_token_is_unauthorized(self):
        self.token_file.write_text("wrong")
        self.site.routes["plans/get"] = (200, {})
        text, err = self.call("plan_get", planId="p")
        self.assertTrue(err)
        self.assertEqual(text, "planner error unauthorized: Missing or wrong token.")

    def test_bare_404_means_not_built(self):
        text, err = self.call("plan_solve", planId="p")
        self.assertTrue(err)
        self.assertEqual(text, "plans/solve is not available on the planner site yet (or the agent API is off).")

    def test_other_status_without_envelope(self):
        self.site.routes["plans/get"] = (502, b"Bad Gateway", "text/plain")
        text, err = self.call("plan_get", planId="p")
        self.assertTrue(err)
        self.assertEqual(text, "the planner site answered HTTP 502: Bad Gateway")

    def test_not_json(self):
        self.site.routes["plans/get"] = (200, b"<html>", "text/html")
        text, err = self.call("plan_get", planId="p")
        self.assertTrue(err)
        self.assertIn("not JSON", text)

    def test_redirect_is_not_followed(self):
        self.site.routes["plans/get"] = (302, b"", "text/plain", [("Location", "/api/agent/v1/plans/create")])
        self.site.routes["plans/create"] = (200, {"planId": "nope"})
        text, err = self.call("plan_get", planId="p")
        self.assertTrue(err)
        self.assertIn("HTTP 302", text)
        self.assertEqual([r["endpoint"] for r in self.site.requests], ["plans/get"])

    def test_unreachable(self):
        s = socket.socket()
        s.bind(("127.0.0.1", 0))
        port = s.getsockname()[1]
        s.close()                                              # nothing listens there now
        self.env["PLANNER_API_URL"] = "http://127.0.0.1:%d" % port
        text, err = self.call("plan_get", planId="p")
        self.assertTrue(err)
        self.assertTrue(text.startswith("planner site unreachable: "), text)

    def test_timeout_and_solver_timeouts(self):
        self.assertEqual(server.SLOW, {"plans/solve": 10, "plans/expand-inputs": 10})
        self.assertEqual(server.TIMEOUT, 6)

        def slow(body):
            time.sleep(1.5)
            return 200, {}
        self.site.routes["plans/get"] = slow
        old = server.TIMEOUT
        server.TIMEOUT = 0.3
        try:
            t0 = time.monotonic()
            text, err = self.call("plan_get", planId="p")
        finally:
            server.TIMEOUT = old
        self.assertTrue(err)
        self.assertTrue(text.startswith("planner site unreachable: no answer within"), text)
        self.assertLess(time.monotonic() - t0, 1.4)

    def test_truncation(self):
        big = {"results": [{"id": "x%d" % i, "name": "y" * 40} for i in range(600)]}
        self.site.routes["recipes/search"] = (200, big)
        text, err = self.call("recipes_search", text="dust")
        self.assertFalse(err)
        note, body = text.split("\n", 1)
        self.assertTrue(note.startswith("[cut: the answer is %d characters" % len(server.compact(big))), note)
        self.assertIn("smaller limit", note)
        self.assertEqual(len(body), server.MAX_TEXT)
        small = {"results": []}
        self.site.routes["recipes/search"] = (200, small)
        self.assertEqual(self.call("recipes_search", text="dust")[0], '{"results":[]}')

    def test_request_over_64k_is_refused_locally(self):
        text, err = self.call("plan_import", snapshot={"blob": "x" * (70 * 1024)})
        self.assertTrue(err)
        self.assertIn("at most 65536", text)
        self.assertEqual(self.site.requests, [])


class NotConfigured(Base):
    def assert_all_not_configured(self, why):
        for t in server.TOOLS:
            text, err = self.call(t["name"], planId="p")
            self.assertTrue(err, t["name"])
            self.assertTrue(text.startswith("the planner is not configured"), (t["name"], text))
            self.assertIn(why, text)
        self.assertEqual(self.site.requests, [])

    def test_no_url(self):
        del self.env["PLANNER_API_URL"]
        self.assert_all_not_configured("PLANNER_API_URL")

    def test_no_token_file_setting(self):
        self.env["PLANNER_API_TOKEN_FILE"] = ""
        self.assert_all_not_configured("PLANNER_API_TOKEN_FILE")

    def test_missing_or_empty_token_file(self):
        self.env["PLANNER_API_TOKEN_FILE"] = str(self.dir / "nope")
        self.assert_all_not_configured("token file")
        self.token_file.write_text("  \n")
        self.env["PLANNER_API_TOKEN_FILE"] = str(self.token_file)
        self.assert_all_not_configured("token file")

    def test_token_is_read_at_call_time_and_base_url_forms(self):
        self.site.routes["plans/get"] = (200, {"cards": []})
        self.env["PLANNER_API_URL"] = self.site.url + "/api/agent/v1/"   # the API path given too: not doubled
        self.assertFalse(self.call("plan_get", planId="p")[1])
        self.token_file.write_text("rotated")
        self.assertIn("unauthorized", self.call("plan_get", planId="p")[0])
        self.assertEqual(self.site.requests[-1]["auth"], "Bearer rotated")
        self.env["PLANNER_API_URL"] = "ftp://planner.example.invalid"
        self.assertIn("http://", self.call("plan_get", planId="p")[0])


class Cards(Base):
    def test_dials_are_sent_nested(self):
        self.site.routes["plans/add-cards"] = (200, {"cardIds": ["c1", "c2"]})
        cards = [{"recipeId": "r1", "machine": "electric-blast-furnace", "tier": "HV",
                  "dials": {"heatingCoil": "kanthal", "batchSize": 4}},
                 {"recipeId": "r2", "machine": None, "dials": {}}]
        text, err = self.call("plan_add_cards", planId="p", cards=cards)
        self.assertFalse(err, text)
        self.assertEqual(json.loads(text), {"cardIds": ["c1", "c2"]})
        self.assertEqual(self.site.requests[-1]["body"]["cards"], [
            {"recipeId": "r1", "machine": "electric-blast-furnace", "tier": "HV",
             "dials": {"heatingCoil": "kanthal", "batchSize": 4}},
            {"recipeId": "r2"}])

    def test_api_card(self):
        self.assertEqual(server.api_card({"recipeId": "r", "dials": {"a": 1.5}}), {"recipeId": "r", "dials": {"a": 1.5}})
        for bad in ({"machine": "x"}, {"recipeId": "r", "heatingCoil": "kanthal"}, {"recipeId": "r", "dials": [1]},
                    {"recipeId": "r", "dials": {"a": True}}, {"recipeId": "r", "dials": {"a": {"b": 1}}}, "r"):
            with self.assertRaises(server.PlannerError, msg=bad):
                server.api_card(bad)
        text, err = self.call("plan_add_cards", planId="p", cards=[{"recipeId": "r", "heatingCoil": "kanthal"}])
        self.assertTrue(err)
        self.assertIn("dials", text)
        self.assertEqual(self.site.requests, [])


class ImportCurrent(Base):
    def setUp(self):
        super().setUp()
        self.sock = Path(tempfile.mkdtemp(prefix="pl-", dir="/tmp")) / "b.sock"   # short: unix path limit
        self.env["DAVE_BRIDGE_SOCKET"] = str(self.sock)
        self.snapshot = {"version": 1, "name": "Titanium", "cards": [{"recipeId": "r1"}]}
        self.att = self.dir / "t-0123456789.json"
        self.att.write_text(json.dumps(self.snapshot))
        os.chmod(self.att, 0o600)
        self.daemon = FakeDaemon(self.sock, {"ok": True, "result": {
            "turn": "t-0123456789", "surface": "gtnhplanner", "requestId": "r1", "path": str(self.att),
            "bytes": self.att.stat().st_size}})
        self.site.routes["plans/import"] = (200, {"planId": "draft-1"})

    def tearDown(self):
        self.daemon.close()
        self.sock.unlink(missing_ok=True)
        self.sock.parent.rmdir()
        super().tearDown()

    def test_imports_this_turns_attachment(self):
        text, err = self.call("plan_import_current", player={"name": "Steve"})
        self.assertFalse(err, text)
        out = json.loads(text)
        self.assertEqual(out["planId"], "draft-1")
        self.assertEqual(out["attachment"], {"turn": "t-0123456789", "surface": "gtnhplanner",
                                             "bytes": self.att.stat().st_size})
        self.assertEqual(self.site.requests[-1]["endpoint"], "plans/import")
        self.assertEqual(self.site.requests[-1]["body"], {"snapshot": self.snapshot, "player": {"name": "Steve"}})
        self.assertEqual(self.daemon.calls[-1]["op"], "surface_attachment")
        self.assertEqual(self.daemon.calls[-1]["args"], {})

    def test_turn_and_earlier(self):
        self.daemon.reply["result"]["earlier"] = True
        out = json.loads(self.call("plan_import_current", turn="t-0123456789")[0])
        self.assertTrue(out["attachment"]["earlier"])
        self.assertEqual(self.daemon.calls[-1]["args"], {"turn": "t-0123456789"})
        self.assertEqual(self.site.requests[-1]["body"], {"snapshot": self.snapshot})

    def test_no_attachment(self):
        self.daemon.reply = {"ok": False, "error": "Steve has not attached anything"}
        text, err = self.call("plan_import_current")
        self.assertTrue(err)
        self.assertIn("no plan is attached to this turn", text)
        self.assertIn("Steve has not attached anything", text)
        self.assertIn("GTNH Planner panel", text)
        self.assertEqual(self.site.requests, [])

    def test_attachment_gone_or_daemon_down(self):
        self.att.unlink()
        text, err = self.call("plan_import_current")
        self.assertTrue(err)
        self.assertIn("could not be read", text)
        self.env["DAVE_BRIDGE_SOCKET"] = str(self.dir / "missing.sock")
        text, err = self.call("plan_import_current")
        self.assertTrue(err)
        self.assertIn("daemon is not reachable", text)
        del self.env["PLANNER_API_URL"]
        self.assertTrue(self.call("plan_import_current")[0].startswith("the planner is not configured"))
        self.assertEqual(self.site.requests, [])

    def test_import_error_maps_like_the_others(self):
        self.site.routes["plans/import"] = (400, {"error": {"code": "bad_request", "message": "snapshot: bad"}})
        text, err = self.call("plan_import_current")
        self.assertTrue(err)
        self.assertEqual(text, "planner error bad_request: snapshot: bad")


class Stdio(Base):
    def test_end_to_end_over_stdio(self):
        ex = EXAMPLES["resources-search"]
        self.site.routes[ex["endpoint"]] = (200, ex["response"])
        lines = [{"jsonrpc": "2.0", "id": 1, "method": "initialize", "params": {"protocolVersion": "2025-06-18"}},
                 {"jsonrpc": "2.0", "method": "notifications/initialized"},
                 {"jsonrpc": "2.0", "id": 2, "method": "tools/list"},
                 {"jsonrpc": "2.0", "id": 3, "method": "tools/call",
                  "params": {"name": "resources_search", "arguments": ex["request"]}}]
        env = dict(self.env, PATH=os.environ.get("PATH", ""))
        proc = subprocess.run([sys.executable, "-I", str(HERE / "server.py")], env=env, capture_output=True,
                              text=True, timeout=30, input="".join(json.dumps(m) + "\n" for m in lines))
        replies = {r["id"]: r for r in map(json.loads, proc.stdout.splitlines())}
        self.assertEqual(sorted(replies), [1, 2, 3])
        self.assertEqual(replies[1]["result"]["serverInfo"]["name"], "planner")
        self.assertEqual(len(replies[2]["result"]["tools"]), 16)
        res = replies[3]["result"]
        self.assertFalse(res["isError"], res)
        self.assertEqual(json.loads(res["content"][0]["text"]), ex["response"])
        self.assertNotIn(TOKEN, proc.stderr + proc.stdout)


class Registration(unittest.TestCase):
    """dave_cmd.py registers the planner server only when it is configured, and grants its tools."""

    VARS = ("DAVE_CONFIG", "DAVE_ROOT", "DAVE_RUN_DIR", "DAVE_CLAUDE_HOME", "DAVE_WORKDIR", "DAVE_CONTAINER",
            "DAVE_PLANNER_URL", "PLANNER_API_TOKEN", "PLANNER_API_TOKEN_FILE")

    def setUp(self):
        sys.path.insert(0, str(HERE.parents[1]))
        from dave.launch import dave_cmd
        self.dave_cmd = dave_cmd
        self.tmp = tempfile.TemporaryDirectory()
        d = Path(self.tmp.name)
        self.saved = {k: os.environ.get(k) for k in self.VARS}
        for k in self.VARS:
            os.environ.pop(k, None)
        os.environ.update(DAVE_RUN_DIR=str(d / "run"), DAVE_CLAUDE_HOME=str(d / "home"),
                          DAVE_WORKDIR=str(d / "work"), DAVE_CONTAINER="1")   # no dev .env fallback

    def tearDown(self):
        for k, v in self.saved.items():
            if v is None:
                os.environ.pop(k, None)
            else:
                os.environ[k] = v
        self.tmp.cleanup()

    def build(self):
        from dave import config
        b = self.dave_cmd.build(config.load())
        mcp = json.loads(b["files"]["mcp"].read_text())
        return b, mcp

    def test_left_out_until_configured(self):
        b, mcp = self.build()
        self.assertNotIn("planner", b["mcp_servers"])
        self.assertFalse([t for t in b["agents"]["recipe-planner"]["tools"] if t.startswith("mcp__planner__")])
        os.environ["DAVE_PLANNER_URL"] = "http://planner.example.invalid:3000"
        b, mcp = self.build()                                   # a URL but no token: still left out
        self.assertNotIn("planner", b["mcp_servers"])
        self.assertTrue(any("planner left out: not configured" in n for n in b["notes"]))

    def test_configured_with_a_token_value(self):
        os.environ.update(DAVE_PLANNER_URL="http://planner.example.invalid:3000", PLANNER_API_TOKEN=TOKEN)
        b, mcp = self.build()
        self.assertIn("planner", b["mcp_servers"])
        srv = mcp["mcpServers"]["planner"]
        self.assertNotIn("_requires", srv)
        self.assertEqual(srv["env"]["PLANNER_API_URL"], "http://planner.example.invalid:3000")
        token_file = Path(srv["env"]["PLANNER_API_TOKEN_FILE"])
        self.assertEqual(token_file.read_text().strip(), TOKEN)
        self.assertEqual(stat.S_IMODE(token_file.stat().st_mode), 0o600)
        self.assertTrue(srv["env"]["DAVE_BRIDGE_SOCKET"])
        self.assertTrue(srv["args"][-1].endswith("tools/planner/server.py"))
        self.assertNotIn(TOKEN, b["files"]["mcp"].read_text())
        self.assertNotIn("PLANNER_API_TOKEN", b["env"])          # never in Claude's own environment
        granted = {t.split("__")[2] for t in b["agents"]["recipe-planner"]["tools"] if t.startswith("mcp__planner__")}
        self.assertEqual(granted, {t["name"] for t in server.TOOLS})
        self.assertIn("mcp__planner", b["settings"]["permissions"]["allow"])

    def test_configured_with_a_token_file(self):
        f = Path(self.tmp.name) / "planner-token"
        f.write_text(TOKEN)
        os.environ.update(DAVE_PLANNER_URL="http://planner.example.invalid:3000", PLANNER_API_TOKEN_FILE=str(f))
        b, mcp = self.build()
        self.assertEqual(mcp["mcpServers"]["planner"]["env"]["PLANNER_API_TOKEN_FILE"], str(f))


if __name__ == "__main__":
    unittest.main()
