package com.tuxprogrammer.davebridge.server.hooks;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import net.minecraft.entity.player.EntityPlayerMP;
import net.minecraftforge.common.MinecraftForge;
import net.minecraftforge.common.UsernameCache;

import com.tuxprogrammer.davebridge.DaveBridge;
import com.tuxprogrammer.davebridge.server.chat.DaveChat;
import com.tuxprogrammer.davebridge.server.compat.BqQuests;

import betterquesting.api.events.QuestEvent;
import cpw.mods.fml.common.eventhandler.EventPriority;
import cpw.mods.fml.common.eventhandler.SubscribeEvent;

/**
 * The {@code quest_complete} hook event: BetterQuesting posts {@code QuestEvent(COMPLETED, player, quests)} on the
 * Forge bus when a player completes quests. The only class that references BQ's event, registered only when BQ is
 * loaded, so it is never loaded without it.
 */
// Source:
// https://github.com/GTNewHorizons/BetterQuesting/blob/3.8.92-GTNH/src/main/java/betterquesting/api/events/QuestEvent.java
// and .../handlers/EventHandler.java (posts Type.COMPLETED with the player's UUID and the completed quest ids).
final class BqHookEvents {

    private BqHookEvents() {}

    static void register() {
        MinecraftForge.EVENT_BUS.register(new Handler());
    }

    public static final class Handler {

        @SubscribeEvent(priority = EventPriority.LOWEST)
        public void onQuest(QuestEvent event) {
            try {
                if (event.getType() != QuestEvent.Type.COMPLETED || !HookService.REG.watches("quest_complete")) {
                    return;
                }
                UUID uuid = event.getPlayerID();
                Map<String, Object> f = new LinkedHashMap<>();
                EntityPlayerMP online = null;
                for (EntityPlayerMP p : DaveChat.onlinePlayers()) {
                    if (p.getUniqueID()
                        .equals(uuid)) {
                        online = p;
                    }
                }
                if (online != null) {
                    f.putAll(HookEvents.player(online));
                } else {
                    String name = UsernameCache.getLastKnownUsername(uuid);
                    f.put("player", name != null ? name : String.valueOf(uuid));
                }
                List<String> ids = new ArrayList<>();
                List<String> names = new ArrayList<>();
                for (UUID q : event.getQuestIDs()) {
                    ids.add(q.toString());
                    String n = BqQuests.nameOf(q);
                    names.add(n == null ? "" : n.replaceAll("§.", ""));
                }
                f.put("questIds", ids);
                f.put("questNames", names);
                HookService.onEvent("quest_complete", f, event);
            } catch (Throwable t) {
                DaveBridge.LOG.warn("Hooks: quest_complete failed", t);
            }
        }
    }
}
