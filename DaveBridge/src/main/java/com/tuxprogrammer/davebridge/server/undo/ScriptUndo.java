package com.tuxprogrammer.davebridge.server.undo;

import net.minecraft.server.MinecraftServer;
import net.minecraft.world.World;

/**
 * The {@code undo} variable of server scripts. In a confirmed write it adds positions to the change journal before
 * the script changes them in ways block capture cannot see (metadata, inventories, machine settings); everywhere else
 * (read scripts, hooks, a server with undo off) it is inactive and every call does nothing.
 *
 * <pre>
 * undo.save(world, x, y, z)                    // one block, tile entity NBT included
 * undo.region(world, x1, y1, z1, x2, y2, z2)   // a cuboid (undo.undoMaxRegionBlocks per script)
 * undo.active                                  // true when changes are being journaled
 * </pre>
 */
public final class ScriptUndo {

    private final ChangeJournal journal;

    public ScriptUndo(ChangeJournal journal) {
        this.journal = journal;
    }

    public boolean isActive() {
        return journal != null && journal == ChangeJournal.active();
    }

    public boolean save(World world, int x, int y, int z) {
        return isActive() && journal.saveBlock(world, x, y, z);
    }

    public boolean save(int dim, int x, int y, int z) {
        return isActive() && journal.saveBlock(world(dim), x, y, z);
    }

    public int region(World world, int x1, int y1, int z1, int x2, int y2, int z2) {
        return isActive() ? journal.saveRegion(world, x1, y1, z1, x2, y2, z2) : 0;
    }

    public int region(int dim, int x1, int y1, int z1, int x2, int y2, int z2) {
        return isActive() ? journal.saveRegion(world(dim), x1, y1, z1, x2, y2, z2) : 0;
    }

    private static World world(int dim) {
        return MinecraftServer.getServer()
            .worldServerForDimension(dim);
    }

    @Override
    public String toString() {
        return isActive() ? "undo journal (active)" : "undo journal (inactive)";
    }
}
