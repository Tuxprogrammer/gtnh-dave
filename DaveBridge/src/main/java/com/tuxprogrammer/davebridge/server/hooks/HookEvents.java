package com.tuxprogrammer.davebridge.server.hooks;

import java.util.LinkedHashMap;
import java.util.Map;

import net.minecraft.entity.Entity;
import net.minecraft.entity.EntityList;
import net.minecraft.entity.EntityLiving;
import net.minecraft.entity.EntityLivingBase;
import net.minecraft.entity.player.EntityPlayer;
import net.minecraft.entity.player.EntityPlayerMP;
import net.minecraftforge.common.MinecraftForge;
import net.minecraftforge.event.ServerChatEvent;
import net.minecraftforge.event.entity.living.LivingDeathEvent;
import net.minecraftforge.event.world.BlockEvent;

import com.tuxprogrammer.davebridge.DaveBridge;
import com.tuxprogrammer.davebridge.server.chat.TriggerMatcher;
import com.tuxprogrammer.davebridge.server.compat.BqQuests;

import cpw.mods.fml.common.FMLCommonHandler;
import cpw.mods.fml.common.eventhandler.EventPriority;
import cpw.mods.fml.common.eventhandler.SubscribeEvent;
import cpw.mods.fml.common.gameevent.PlayerEvent;

/**
 * Turns Forge and FML events into hook facts ({@link HookService#onEvent}). Each handler returns at once when no hook
 * watches its event, reads only plain fields of the event, and catches everything: a hook must never break the game.
 * Handlers run at LOWEST priority without {@code receiveCanceled}, so events other mods cancelled do not fire hooks.
 * <p>
 * Fact keys (also the {@code detail} of {@code hook_fired}): {@code player}, {@code dim}, {@code x}, {@code y},
 * {@code z}, and per event {@code block}, {@code meta}, {@code message}, {@code triggered}, {@code entity},
 * {@code entityClass}, {@code entityName}, {@code killer}, {@code killerEntity}, {@code damage}, {@code fromDim},
 * {@code toDim}, {@code questIds}, {@code questNames}.
 */
public final class HookEvents {

    private HookEvents() {}

    static void register() {
        Forge forge = new Forge();
        MinecraftForge.EVENT_BUS.register(forge);
        FMLCommonHandler.instance()
            .bus()
            .register(forge);
        if (BqQuests.available()) {
            try {
                BqHookEvents.register();
            } catch (Throwable t) {
                DaveBridge.LOG.warn("Hooks: quest_complete is unavailable (BetterQuesting events)", t);
            }
        }
    }

    static Map<String, Object> player(EntityPlayer p) {
        Map<String, Object> f = new LinkedHashMap<>();
        f.put("player", p.getCommandSenderName());
        f.put("dim", p.dimension);
        f.put("x", Math.floor(p.posX));
        f.put("y", Math.floor(p.posY));
        f.put("z", Math.floor(p.posZ));
        return f;
    }

    static void entity(Map<String, Object> f, String prefix, Entity e) {
        String kind = e instanceof EntityPlayer ? "Player" : EntityList.getEntityString(e);
        f.put(
            prefix,
            kind != null ? kind
                : e.getClass()
                    .getSimpleName());
        if (prefix.equals("entity")) {
            f.put(
                "entityClass",
                e.getClass()
                    .getName());
            if (e instanceof EntityLiving && ((EntityLiving) e).hasCustomNameTag()) {
                f.put("entityName", ((EntityLiving) e).getCustomNameTag());
            } else if (e instanceof EntityPlayer) {
                f.put("entityName", e.getCommandSenderName());
            }
        }
    }

    /** The subscribers (public with public methods, as the event buses require). */
    public static final class Forge {

        @SubscribeEvent(priority = EventPriority.LOWEST)
        public void onLogin(PlayerEvent.PlayerLoggedInEvent event) {
            try {
                if (event.player instanceof EntityPlayerMP && HookService.REG.watches("player_join")) {
                    HookService.onEvent("player_join", player(event.player), event);
                }
            } catch (Throwable t) {
                DaveBridge.LOG.warn("Hooks: player_join failed", t);
            }
        }

        @SubscribeEvent(priority = EventPriority.LOWEST)
        public void onLogout(PlayerEvent.PlayerLoggedOutEvent event) {
            try {
                if (event.player instanceof EntityPlayerMP && HookService.REG.watches("player_leave")) {
                    HookService.onEvent("player_leave", player(event.player), event);
                }
            } catch (Throwable t) {
                DaveBridge.LOG.warn("Hooks: player_leave failed", t);
            }
        }

        @SubscribeEvent(priority = EventPriority.LOWEST)
        public void onDimension(PlayerEvent.PlayerChangedDimensionEvent event) {
            try {
                if (event.player instanceof EntityPlayerMP && HookService.REG.watches("player_dimension")) {
                    Map<String, Object> f = player(event.player);
                    f.put("fromDim", event.fromDim);
                    f.put("toDim", event.toDim);
                    HookService.onEvent("player_dimension", f, event);
                }
            } catch (Throwable t) {
                DaveBridge.LOG.warn("Hooks: player_dimension failed", t);
            }
        }

        @SubscribeEvent(priority = EventPriority.LOWEST)
        public void onDeath(LivingDeathEvent event) {
            try {
                EntityLivingBase dead = event.entityLiving;
                if (dead == null || dead.worldObj == null || dead.worldObj.isRemote) {
                    return;
                }
                boolean isPlayer = dead instanceof EntityPlayerMP;
                String type = isPlayer ? "player_death" : "entity_death";
                if (!HookService.REG.watches(type)) {
                    return;
                }
                Map<String, Object> f = isPlayer ? player((EntityPlayer) dead) : new LinkedHashMap<>();
                if (!isPlayer) {
                    f.put("dim", dead.dimension);
                    f.put("x", Math.floor(dead.posX));
                    f.put("y", Math.floor(dead.posY));
                    f.put("z", Math.floor(dead.posZ));
                }
                entity(f, "entity", dead);
                if (event.source != null) {
                    f.put("damage", event.source.getDamageType());
                    Entity killer = event.source.getEntity();
                    if (killer instanceof EntityPlayer) {
                        f.put("killer", killer.getCommandSenderName());
                    } else if (killer != null) {
                        entity(f, "killerEntity", killer);
                    }
                    try {
                        f.put(
                            "message",
                            event.source.func_151519_b(dead)
                                .getUnformattedText());
                    } catch (Throwable ignored) {
                        // some mods' damage sources build messages that need a client
                    }
                }
                HookService.onEvent(type, f, event);
            } catch (Throwable t) {
                DaveBridge.LOG.warn("Hooks: death event failed", t);
            }
        }

        @SubscribeEvent(priority = EventPriority.LOWEST)
        public void onBreak(BlockEvent.BreakEvent event) {
            try {
                if (event.world == null || event.world.isRemote || !HookService.REG.watches("block_break")) {
                    return;
                }
                Map<String, Object> f = block(event, event.block, event.blockMetadata);
                if (event.getPlayer() != null) {
                    f.put(
                        "player",
                        event.getPlayer()
                            .getCommandSenderName());
                }
                HookService.onEvent("block_break", f, event);
            } catch (Throwable t) {
                DaveBridge.LOG.warn("Hooks: block_break failed", t);
            }
        }

        @SubscribeEvent(priority = EventPriority.LOWEST)
        public void onPlace(BlockEvent.PlaceEvent event) {
            try {
                if (event.world == null || event.world.isRemote || !HookService.REG.watches("block_place")) {
                    return;
                }
                Map<String, Object> f = block(
                    event,
                    event.placedBlock,
                    event.world.getBlockMetadata(event.x, event.y, event.z));
                if (event.player != null) {
                    f.put("player", event.player.getCommandSenderName());
                }
                HookService.onEvent("block_place", f, event);
            } catch (Throwable t) {
                DaveBridge.LOG.warn("Hooks: block_place failed", t);
            }
        }

        @SubscribeEvent(priority = EventPriority.LOWEST)
        public void onChat(ServerChatEvent event) {
            try {
                if (event.player == null || !HookService.REG.watches("chat")) {
                    return;
                }
                Map<String, Object> f = player(event.player);
                f.put("message", event.message);
                f.put(
                    "triggered",
                    TriggerMatcher.addressed(event.message, com.tuxprogrammer.davebridge.common.Config.triggerPrefixes)
                        != null);
                HookService.onEvent("chat", f, event);
            } catch (Throwable t) {
                DaveBridge.LOG.warn("Hooks: chat failed", t);
            }
        }

        private static Map<String, Object> block(BlockEvent event, net.minecraft.block.Block block, int meta) {
            Map<String, Object> f = new LinkedHashMap<>();
            f.put("dim", event.world.provider.dimensionId);
            f.put("x", event.x);
            f.put("y", event.y);
            f.put("z", event.z);
            f.put("block", com.tuxprogrammer.davebridge.server.undo.ChangeJournal.name(block));
            f.put("meta", meta);
            return f;
        }
    }
}
