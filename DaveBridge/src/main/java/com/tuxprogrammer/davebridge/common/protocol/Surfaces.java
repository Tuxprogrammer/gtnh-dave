package com.tuxprogrammer.davebridge.common.protocol;

import java.util.regex.Pattern;

import com.google.gson.JsonElement;

/**
 * Limits and validation shared by both halves for surface asks: questions from another mod's panel (through
 * {@code api.DaveBridgeClientApi}) whose answers go back only to that panel. Pure functions, no game classes.
 */
public final class Surfaces {

    /** {@code server_hello.features} entry and {@code client_hello.capabilities} entry for surface support. */
    public static final String FEATURE = "surface";

    /** Largest attachment (UTF-8 bytes of its JSON). */
    public static final int MAX_ATTACHMENT_BYTES = 256 * 1024;
    /** Largest {@code data} object on a reply (UTF-8 bytes of its JSON). */
    public static final int MAX_DATA_BYTES = 64 * 1024;

    private static final Pattern SURFACE = Pattern.compile("[a-z0-9][a-z0-9_.-]{0,31}");
    private static final Pattern REQUEST_ID = Pattern.compile("[A-Za-z0-9][A-Za-z0-9_-]{0,63}");

    private Surfaces() {}

    /** A surface name: lowercase letters, digits, {@code _ . -}, 1..32 characters, starting with a letter or digit. */
    public static boolean validSurface(String s) {
        return s != null && SURFACE.matcher(s)
            .matches();
    }

    /** A request id: letters, digits, {@code _ -}, 1..64 characters, starting with a letter or digit. */
    public static boolean validRequestId(String s) {
        return s != null && REQUEST_ID.matcher(s)
            .matches();
    }

    /** UTF-8 size of {@code el}'s JSON; 0 for null. */
    public static int jsonBytes(JsonElement el) {
        if (el == null || el.isJsonNull()) {
            return 0;
        }
        return el.toString()
            .getBytes(java.nio.charset.StandardCharsets.UTF_8).length;
    }

    /** Null if {@code attachment} is acceptable (absent, or an object or array within the size cap), else why not. */
    public static String checkAttachment(JsonElement attachment) {
        if (attachment == null || attachment.isJsonNull()) {
            return null;
        }
        if (!attachment.isJsonObject() && !attachment.isJsonArray()) {
            return "the attachment must be a JSON object or array";
        }
        int bytes = jsonBytes(attachment);
        if (bytes > MAX_ATTACHMENT_BYTES) {
            return "the attachment is too large (" + bytes + " bytes, at most " + MAX_ATTACHMENT_BYTES + ")";
        }
        return null;
    }

    /** Null if {@code data} is acceptable (absent, or an object within the size cap), else why not. */
    public static String checkData(JsonElement data) {
        if (data == null || data.isJsonNull()) {
            return null;
        }
        if (!data.isJsonObject()) {
            return "data must be a JSON object";
        }
        int bytes = jsonBytes(data);
        if (bytes > MAX_DATA_BYTES) {
            return "data is too large (" + bytes + " bytes, at most " + MAX_DATA_BYTES + ")";
        }
        return null;
    }
}
