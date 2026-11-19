"""The turn envelope sent to Dave and the parsing of his reply. Spec: dave/ENVELOPE.md. Standard library only."""

import json
import re
from datetime import datetime, timezone

DIRECTIVES = {"@reply": "reply", "@whisper": "whisper", "@none": "none"}


def esc(value):
    """XML-escape player- or client-supplied text so it can't close a tag or forge an element."""
    return (str(value).replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;").replace('"', "&quot;"))


def _attrs(pairs):
    return "".join(' %s="%s"' % (k, esc(v)) for k, v in pairs if v is not None and v != "")


def _bool(v):
    return "true" if v else "false"


def _item_id(d):
    if not isinstance(d, dict) or not d.get("id"):
        return None
    meta = d.get("meta")
    return "%s:%s" % (d["id"], meta) if meta not in (None, 0, "0") else str(d["id"])


def context_attrs(ctx):
    """<context> attributes from a client-half ask context (PROTOCOL.md 2.4). Client-reported, untrusted."""
    if not isinstance(ctx, dict):
        return []
    look = ctx.get("lookingAt") if isinstance(ctx.get("lookingAt"), dict) else {}
    if look.get("kind") == "block":
        looking = _item_id(look)
        looking_name = look.get("name")
        looking_pos = ",".join(str(look[k]) for k in ("x", "y", "z") if k in look) or None
    elif look.get("kind") == "entity":
        looking, looking_name, looking_pos = "entity:" + str(look.get("type") or look.get("class") or "?"), look.get("name"), None
    else:
        looking = looking_name = looking_pos = None
    held = ctx.get("heldItem") if isinstance(ctx.get("heldItem"), dict) else {}
    return [("looking_at", looking), ("looking_at_name", looking_name), ("looking_at_pos", looking_pos),
            ("holding", _item_id(held)), ("holding_name", held.get("name")), ("biome", ctx.get("biome"))]


def build(turn_id, chat, profile, pack, client_eval=None, warnings=None, recent=None, kind="question", now=None):
    """The <turn> envelope for one chat event (DaveBridge `chat`, PROTOCOL.md 1.3)."""
    ts = (now or datetime.now(timezone.utc)).strftime("%Y-%m-%dT%H:%M:%SZ")
    pos = None
    if all(isinstance(chat.get(k), (int, float)) for k in ("x", "y", "z")):
        pos = "%d,%d,%d" % (int(chat["x"] // 1), int(chat["y"] // 1), int(chat["z"] // 1))
    player = [("name", chat.get("player")), ("uuid", chat.get("playerUuid")), ("dim", chat.get("dim")),
              ("pos", pos), ("hasClientMod", _bool(chat.get("hasClientMod"))),
              ("clientEval", "true" if client_eval else None), ("source", chat.get("source") or "chat")]
    lines = ['<turn%s>' % _attrs([("id", turn_id), ("ts", ts), ("pack", pack), ("kind", kind)]),
             "<player%s/>" % _attrs(player),
             "<profile>\n%s\n</profile>" % esc(profile or "no profile yet")]
    if chat.get("source") == "client" and chat.get("context"):
        lines.append("<context%s/>" % _attrs(context_attrs(chat["context"])))
    for reply_to, text in warnings or []:
        lines.append('<warnings reply="%s">%s</warnings>' % (esc(reply_to), esc(text)))
    if recent:
        lines.append("<recent>\n%s\n</recent>" % "\n".join(esc(r) for r in recent))
    message = chat.get("prompt") if chat.get("triggered") and chat.get("prompt") is not None else chat.get("message")
    lines.append("<message>%s</message>" % esc(message or ""))
    lines.append("</turn>")
    return "\n".join(lines)


def event(turn_id, ev, profile, pack, coalesced=0, dropped=0, now=None):
    """The <turn kind="event"> envelope for a hook notification (DaveBridge `hook_fired` / `hook_disabled`,
    PROTOCOL.md 1.9). The hook's owner stands in for the asker: <player> is the owner, so replies, whispers and
    write confirms go to them. Everything from the hook (name, note, detail) is agent- or game-supplied text and is
    escaped like player text."""
    ts = (now or datetime.now(timezone.utc)).strftime("%Y-%m-%dT%H:%M:%SZ")
    kind = "disabled" if ev.get("type") == "hook_disabled" else "fired"
    attrs = [("hook", ev.get("hookId")), ("name", ev.get("name")), ("type", ev.get("event")), ("status", kind),
             ("once", _bool(ev.get("once"))), ("removed", _bool(ev.get("removed")) if kind == "fired" else None),
             ("fired", ev.get("fireCount")), ("notify", ev.get("notify")),
             ("ownerOnline", _bool(ev.get("ownerOnline"))), ("queued", "true" if ev.get("queued") else None),
             ("suppressed", ev.get("suppressed") or None), ("coalesced", coalesced or None),
             ("dropped", dropped or None)]
    lines = ['<turn%s>' % _attrs([("id", turn_id), ("ts", ts), ("pack", pack), ("kind", "event")]),
             "<player%s/>" % _attrs([("name", ev.get("owner")), ("uuid", ev.get("ownerUuid")), ("source", "hook")]),
             "<profile>\n%s\n</profile>" % esc(profile or "no profile yet"),
             "<event%s/>" % _attrs(attrs)]
    if ev.get("note"):
        lines.append("<note>%s</note>" % esc(ev["note"]))
    if kind == "disabled":
        lines.append("<error>%s</error>" % esc(ev.get("error") or ""))
    else:
        detail = json.dumps(ev.get("detail") or {}, ensure_ascii=False, separators=(",", ":"))
        lines.append("<detail>%s</detail>" % esc(detail[:4000]))
    lines.append("</turn>")
    return "\n".join(lines)


def retry(turn_id, chat, pack, now=None):
    """The envelope sent after a crash when the resumed session already holds turn `turn_id`'s question.

    It carries no <message>: the question is already in the conversation, so it is not sent twice.
    """
    ts = (now or datetime.now(timezone.utc)).strftime("%Y-%m-%dT%H:%M:%SZ")
    return "\n".join([
        '<turn%s>' % _attrs([("id", turn_id), ("ts", ts), ("pack", pack), ("kind", "retry")]),
        "<player%s/>" % _attrs([("name", chat.get("player")), ("uuid", chat.get("playerUuid")),
                                ("source", chat.get("source") or "chat")]),
        "<note>Your answer to turn %s was cut off by a restart. The question is above in this conversation; "
        "answer it now, starting with the directive line as usual.</note>" % esc(turn_id),
        "</turn>"])


def user_message(envelope):
    """One stream-json input line for the claude process."""
    return {"type": "user", "message": {"role": "user", "content": envelope}}


def parse_reply(text, default="reply"):
    """Splits Dave's final text into (directive, markup, had_directive).

    Line 1 must be @reply, @whisper or @none; otherwise the whole text is the message and `default` applies.
    """
    text = (text or "").strip("\n")
    first, _, rest = text.partition("\n")
    d = DIRECTIVES.get(first.strip().lower())
    if d:
        return d, rest.strip("\n").strip(), True
    m = re.match(r"\s*(@reply|@whisper|@none)\b[ \t]*(.*)$", first, re.I)
    if m:                                   # "@reply Hello" on one line: tolerate it
        return DIRECTIVES[m.group(1).lower()], (m.group(2) + ("\n" + rest if rest else "")).strip(), True
    return default, text.strip(), False
