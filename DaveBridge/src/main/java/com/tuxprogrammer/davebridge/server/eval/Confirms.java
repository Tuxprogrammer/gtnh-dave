package com.tuxprogrammer.davebridge.server.eval;

import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.TimeUnit;

import net.minecraft.command.ICommandSender;
import net.minecraft.entity.player.EntityPlayerMP;
import net.minecraft.util.ChatComponentText;
import net.minecraft.util.ChatStyle;
import net.minecraft.util.EnumChatFormatting;
import net.minecraft.util.IChatComponent;

import com.tuxprogrammer.davebridge.DaveBridge;
import com.tuxprogrammer.davebridge.common.Config;
import com.tuxprogrammer.davebridge.server.DaveServer;
import com.tuxprogrammer.davebridge.server.chat.DaveChat;
import com.tuxprogrammer.davebridge.server.chat.markup.ChatStyles;

/**
 * The in-game Confirm / Cancel step every world change from the agent goes through: confirmed write evals
 * ({@link EvalService}) and undos ({@code server.undo.UndoService}). An accident guard, not security: a person sees
 * what Dave is about to do before it happens. The rules live in {@link PendingConfirms}; this class owns the shared
 * instance, the chat lines, the {@code /dave confirm|cancel} commands and expiry.
 */
public final class Confirms {

    public static final int MAX_PER_PLAYER = 5;

    /** Something waiting for a click. Every method runs on the server thread and must not throw. */
    public interface Job {

        /** One line: what will change. */
        String summary();

        /** The words before the summary, e.g. {@code "wants to change the world: "}. */
        String headline();

        /** Hover text on the summary (the script, or what an undo restores). */
        String hover();

        /** The asker (or the console) clicked Confirm: do it now. */
        void confirmed(Confirmed by);

        /** Cancel was clicked; {@code who} is the clicker's name. */
        void cancelled(String who);

        /** Nobody answered in time. */
        void expired(String asker);

        /** The server is stopping before anyone answered. */
        void stopping();
    }

    /** Who confirmed what. */
    public static final class Confirmed {

        public final String token;
        /** Name of whoever clicked (a player or the console). */
        public final String who;
        /** The player the confirm was shown to. */
        public final String asker;
        public final ICommandSender sender;
        /** The clicking player, or null for the console. */
        public final EntityPlayerMP clicker;

        Confirmed(String token, String who, String asker, ICommandSender sender, EntityPlayerMP clicker) {
            this.token = token;
            this.who = who;
            this.asker = asker;
            this.sender = sender;
            this.clicker = clicker;
        }
    }

    private static final PendingConfirms<Job> PENDING = new PendingConfirms<>(MAX_PER_PLAYER);

    private Confirms() {}

    /**
     * Parks {@code job} for {@code playerName}'s click and shows them the confirm lines (when online). Server thread.
     *
     * @return the pending entry (its {@code token} is the confirm id), or null when the player already has
     *         {@link #MAX_PER_PLAYER} waiting
     */
    public static PendingConfirms.Pending<Job> create(EntityPlayerMP player, String playerName, UUID uuid, long seconds,
        Job job) {
        PendingConfirms.Pending<Job> pending = PENDING.create(uuid, playerName, now(), seconds * 1000L, job);
        if (pending == null) {
            return null;
        }
        if (player != null) {
            player.addChatMessage(line1(job));
            player.addChatMessage(line2(pending.token, seconds));
        }
        EvalService.schedule(
            () -> DaveServer.QUEUE.submit("confirm_expiry", Confirms::expire),
            seconds * 1000L + 50,
            TimeUnit.MILLISECONDS);
        return pending;
    }

    private static IChatComponent line1(Job job) {
        IChatComponent line = new ChatComponentText("[" + Config.botName + "] ");
        line.setChatStyle(new ChatStyle().setColor(EnumChatFormatting.AQUA));
        IChatComponent text = new ChatComponentText(job.headline());
        text.setChatStyle(new ChatStyle().setColor(EnumChatFormatting.GOLD));
        IChatComponent what = new ChatComponentText(job.summary());
        what.setChatStyle(
            new ChatStyle().setColor(EnumChatFormatting.WHITE)
                .setChatHoverEvent(ChatStyles.hoverText(job.hover())));
        line.appendSibling(text);
        line.appendSibling(what);
        return line;
    }

    private static IChatComponent line2(String token, long seconds) {
        IChatComponent line = new ChatComponentText("");
        line.appendSibling(
            ChatStyles.button(
                "Confirm",
                EnumChatFormatting.GREEN,
                null,
                ChatStyles.hoverText("Run it now"),
                ChatStyles.run("/dave confirm " + token)));
        line.appendSibling(new ChatComponentText(" "));
        line.appendSibling(
            ChatStyles.button(
                "Cancel",
                EnumChatFormatting.RED,
                null,
                ChatStyles.hoverText("Do nothing"),
                ChatStyles.run("/dave cancel " + token)));
        IChatComponent note = new ChatComponentText(" (expires in " + formatSeconds(seconds) + ")");
        note.setChatStyle(new ChatStyle().setColor(EnumChatFormatting.GRAY));
        line.appendSibling(note);
        return line;
    }

    static String formatSeconds(long s) {
        return s >= 120 && s % 60 == 0 ? (s / 60) + " min" : s + " s";
    }

    /**
     * {@code /dave confirm|cancel <token>}: the Confirm and Cancel buttons, or the console. A player may answer only
     * their own confirm; the console (any non-player sender) may answer any. Server thread (commands run there).
     *
     * @return the message for whoever ran it
     */
    public static IChatComponent onCommand(ICommandSender sender, String token, boolean confirm) {
        EntityPlayerMP clicker = sender instanceof EntityPlayerMP ? (EntityPlayerMP) sender : null;
        String who = sender.getCommandSenderName();
        PendingConfirms.Resolution<Job> res = PENDING
            .resolve(token, clicker == null ? null : clicker.getUniqueID(), confirm, now());
        switch (res.outcome) {
            case WRONG_PLAYER:
                return DaveChat.notice("Only " + res.pending.playerName + " can confirm or cancel that.");
            case UNKNOWN:
                return DaveChat.notice("That change was already handled or has expired.");
            case CANCELLED:
                try {
                    res.pending.payload.cancelled(who);
                } catch (Throwable t) {
                    DaveBridge.LOG.warn("Cancelling {} failed", res.pending.token, t);
                }
                DaveBridge.LOG.info("{} cancelled {}: {}", who, res.pending.token, res.pending.payload.summary());
                return DaveChat.notice("Cancelled. Nothing was changed.");
            case CONFIRMED:
            default:
                DaveBridge.LOG.info(
                    "{} confirmed {} for {}: {}",
                    who,
                    res.pending.token,
                    res.pending.playerName,
                    res.pending.payload.summary());
                Confirmed by = new Confirmed(res.pending.token, who, res.pending.playerName, sender, clicker);
                Job job = res.pending.payload;
                DaveServer.QUEUE.submit("confirmed", () -> {
                    try {
                        job.confirmed(by);
                    } catch (Throwable t) {
                        DaveBridge.LOG.warn("Confirmed job {} failed", by.token, t);
                    }
                });
                return DaveChat.notice("Confirmed. Running it now...");
        }
    }

    /** {@code /dave pending}: every change waiting for a confirm, one line each. */
    public static List<String> describe() {
        List<String> out = new ArrayList<>();
        long now = now();
        for (PendingConfirms.Pending<Job> p : PENDING.list()) {
            out.add(
                p.token + "  "
                    + p.playerName
                    + "  "
                    + Math.max(0, (p.expiresAt - now) / 1000)
                    + " s left  "
                    + p.payload.summary());
        }
        return out;
    }

    /** Server thread: answers and tells the player about every confirm past its time. */
    static void expire() {
        for (PendingConfirms.Pending<Job> p : PENDING.expire(now())) {
            try {
                p.payload.expired(p.playerName);
            } catch (Throwable t) {
                DaveBridge.LOG.warn("Expiring {} failed", p.token, t);
            }
            EntityPlayerMP player = EvalService.findPlayer(p.playerName);
            if (player != null) {
                player.addChatMessage(DaveChat.notice("Dave's change expired without a confirm; nothing was changed."));
            }
        }
    }

    /** Server stopping: answers every waiting confirm. */
    public static void serverStopping() {
        for (PendingConfirms.Pending<Job> p : PENDING.clear()) {
            try {
                p.payload.stopping();
            } catch (Throwable t) {
                DaveBridge.LOG.warn("Stopping {} failed", p.token, t);
            }
        }
    }

    public static int size() {
        return PENDING.size();
    }

    static long now() {
        return System.currentTimeMillis();
    }
}
