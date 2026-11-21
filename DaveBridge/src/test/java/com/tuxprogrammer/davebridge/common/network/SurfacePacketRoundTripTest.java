package com.tuxprogrammer.davebridge.common.network;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.charset.StandardCharsets;
import java.util.List;

import org.junit.jupiter.api.Test;

import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import com.google.gson.JsonPrimitive;
import com.tuxprogrammer.davebridge.common.protocol.Ask;
import com.tuxprogrammer.davebridge.common.protocol.AskCancel;
import com.tuxprogrammer.davebridge.common.protocol.DaveMessage;
import com.tuxprogrammer.davebridge.common.protocol.DavePackets;
import com.tuxprogrammer.davebridge.common.protocol.Surfaces;

/** Surface packets survive the real encode, chunk, reassemble and decode path; limits and validators hold. */
class SurfacePacketRoundTripTest {

    /** A plan-like attachment of about {@code bytes} UTF-8 bytes. */
    static JsonObject attachment(int bytes) {
        JsonObject plan = new JsonObject();
        plan.addProperty("id", "plan-1");
        JsonArray cards = new JsonArray();
        int i = 0;
        while (plan.toString()
            .length() < bytes) {
            JsonObject card = new JsonObject();
            card.addProperty("recipe", "gt.recipe." + i);
            card.addProperty("machine", "Large Chemical Reactor");
            card.addProperty("note", "Ammonia from nitrogen and hydrogen, card number " + i++);
            cards.add(card);
            plan.add("cards", cards);
        }
        return plan;
    }

    private static byte[] reassemble(byte[] data, int maxBytes) {
        List<byte[]> chunks = PayloadChunker.split(data, PayloadChunker.DEFAULT_CHUNK_BYTES);
        PayloadChunker.Assembler assembler = new PayloadChunker.Assembler(maxBytes, 4, Long.MAX_VALUE);
        byte[] whole = null;
        for (int i = 0; i < chunks.size(); i++) {
            byte[] r = assembler.accept(1, i, chunks.size(), chunks.get(i));
            if (r != null) whole = r;
        }
        return whole;
    }

    @Test
    void askWithLargestAttachmentFitsTheClientPacketCap() {
        Ask ask = new Ask();
        ask.text = new String(new char[2000]).replace('\0', 'q');
        ask.surface = "gtnhplanner";
        ask.requestId = "r0123456789abcdef";
        JsonObject context = new JsonObject();
        context.addProperty("planName", "Titanium");
        ask.context = context;
        ask.attachment = attachment(Surfaces.MAX_ATTACHMENT_BYTES - 200);
        assertNull(Surfaces.checkAttachment(ask.attachment));
        byte[] data = DaveNetwork.encode(ask);
        assertTrue(data.length > 8 * PayloadChunker.DEFAULT_CHUNK_BYTES, "expected many chunks");
        byte[] whole = reassemble(data, DaveNetwork.MAX_TO_SERVER_BYTES);
        assertNotNull(whole, "a maximal surface ask must fit MAX_TO_SERVER_BYTES");
        Ask got = (Ask) DaveNetwork.decode(Ask.KIND, whole, DavePackets.Direction.TO_SERVER);
        assertEquals("gtnhplanner", got.surface);
        assertEquals("r0123456789abcdef", got.requestId);
        assertEquals(ask.attachment, got.attachment);
        assertEquals(ask.text, got.text);
    }

    @Test
    void oversizedAttachmentIsRefused() {
        String why = Surfaces.checkAttachment(attachment(Surfaces.MAX_ATTACHMENT_BYTES + 100));
        assertNotNull(why);
        assertTrue(why.contains("too large"), why);
        assertNotNull(Surfaces.checkAttachment(new JsonPrimitive("a string")));
        assertNull(Surfaces.checkAttachment(null));
        assertNull(Surfaces.checkAttachment(new JsonArray()));
    }

    @Test
    void daveMessageCarriesFinalUnderItsWireName() {
        DaveMessage m = new DaveMessage();
        m.surface = "gtnhplanner";
        m.requestId = "r1";
        m.finalReply = true;
        m.markup = "Use the {m:Haber-Bosch} line";
        m.components = new String[] { "{\"text\":\"Use the Haber-Bosch line\"}" };
        JsonObject data = new JsonObject();
        data.addProperty("proposalId", "p7");
        m.data = data;
        String json = new String(DaveNetwork.encode(m), StandardCharsets.UTF_8);
        JsonObject wire = new JsonParser().parse(json)
            .getAsJsonObject();
        assertTrue(wire.get("final")
            .getAsBoolean(), json);
        assertFalse(wire.has("finalReply"), json);
        assertFalse(wire.has("error"), "null fields are left out: " + json);
        DaveMessage got = (DaveMessage) DaveNetwork.decode(
            DaveMessage.KIND,
            json.getBytes(StandardCharsets.UTF_8),
            DavePackets.Direction.TO_CLIENT);
        assertTrue(got.finalReply);
        assertEquals("p7", got.data.getAsJsonObject()
            .get("proposalId")
            .getAsString());
        assertEquals(1, got.components.length);
    }

    @Test
    void askCancelTravelsOnlyToTheServer() {
        AskCancel c = new AskCancel();
        c.requestId = "r9";
        byte[] data = DaveNetwork.encode(c);
        assertEquals("r9", ((AskCancel) DaveNetwork.decode(AskCancel.KIND, data, DavePackets.Direction.TO_SERVER)).requestId);
        assertThrows(
            IllegalArgumentException.class,
            () -> DaveNetwork.decode(AskCancel.KIND, data, DavePackets.Direction.TO_CLIENT));
    }

    @Test
    void validators() {
        assertTrue(Surfaces.validSurface("gtnhplanner"));
        assertTrue(Surfaces.validSurface("my-mod.panel_2"));
        assertFalse(Surfaces.validSurface("GTNH"));
        assertFalse(Surfaces.validSurface(""));
        assertFalse(Surfaces.validSurface(null));
        assertFalse(Surfaces.validSurface("-x"));
        assertFalse(Surfaces.validSurface(new String(new char[33]).replace('\0', 'a')));
        assertTrue(Surfaces.validRequestId("r0123abc"));
        assertTrue(Surfaces.validRequestId(new String(new char[64]).replace('\0', 'a')));
        assertFalse(Surfaces.validRequestId(new String(new char[65]).replace('\0', 'a')));
        assertFalse(Surfaces.validRequestId("r 1"));
        assertFalse(Surfaces.validRequestId("_r"));
        assertNull(Surfaces.checkData(new JsonObject()));
        assertNotNull(Surfaces.checkData(new JsonArray()));
        assertNotNull(Surfaces.checkData(attachment(Surfaces.MAX_DATA_BYTES + 10)));
    }
}
