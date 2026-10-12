package com.example.forkworld;

import net.minecraft.core.BlockPos;
import net.minecraft.core.registries.Registries;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.ListTag;
import net.minecraft.nbt.Tag;
import net.minecraft.resources.ResourceKey;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.level.GameRules;
import net.minecraft.world.level.GameType;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.chunk.LevelChunk;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * Copies a {@link ForkSnapshot} into the sandbox server and puts the player where the
 * snapshot was taken. Both methods MUST run on the sandbox server thread
 * ({@code server.submit(...)}).
 */
public final class SnapshotLoader {
    private SnapshotLoader() {
    }

    /** Rebuilds the world around the fork origin. Safe to call again to rewind. */
    public static String apply(MinecraftServer server, ForkSnapshot snap) {
        ServerLevel level = resolveLevel(server, snap.dimension);

        // Keep the sandbox calm: no random spawns, frozen daylight cycle.
        server.getGameRules().getRule(GameRules.RULE_DOMOBSPAWNING).set(false, server);
        server.getGameRules().getRule(GameRules.RULE_DAYLIGHT).set(false, server);
        level.setDayTime(snap.dayTime);

        // 1. Remove anything worldgen / earlier runs left behind (players stay).
        List<Entity> toRemove = new ArrayList<>();
        for (Entity e : level.getAllEntities()) {
            if (!(e instanceof Player)) toRemove.add(e);
        }
        toRemove.forEach(Entity::discard);

        // 2. Blocks. Group captured sections by chunk, then overwrite EVERY section of those
        //    chunks (sections that were air-only on the client are not in the snapshot).
        Map<Long, Map<Integer, ForkSnapshot.Section>> byChunk = new HashMap<>();
        for (ForkSnapshot.Section s : snap.sections) {
            byChunk.computeIfAbsent(ChunkPos.asLong(s.chunkX(), s.chunkZ()), k -> new HashMap<>())
                    .put(s.sectionY(), s);
        }

        BlockState air = Blocks.AIR.defaultBlockState();
        BlockPos.MutableBlockPos pos = new BlockPos.MutableBlockPos();
        int changed = 0;
        for (Map.Entry<Long, Map<Integer, ForkSnapshot.Section>> ce : byChunk.entrySet()) {
            ChunkPos cp = new ChunkPos(ce.getKey());
            LevelChunk chunk = level.getChunk(cp.x, cp.z); // forces the chunk to load/generate
            for (int sy = level.getMinSection(); sy < level.getMaxSection(); sy++) {
                ForkSnapshot.Section s = ce.getValue().get(sy);
                if (s == null && chunk.getSection(chunk.getSectionIndexFromSectionY(sy)).hasOnlyAir()) continue;
                int baseY = sy << 4;
                for (int y = 0; y < 16; y++) {
                    for (int z = 0; z < 16; z++) {
                        for (int x = 0; x < 16; x++) {
                            BlockState want = s == null ? air : s.palette().get(s.indices()[(y << 8) | (z << 4) | x]);
                            pos.set((cp.x << 4) + x, baseY + y, (cp.z << 4) + z);
                            if (chunk.getBlockState(pos) != want) {
                                chunk.setBlockState(pos, want, false);
                                changed++;
                            }
                        }
                    }
                }
            }
        }

        // 3. Block entities (chests, signs, ...). Tags come from saveWithFullMetadata.
        int bes = 0;
        for (Map.Entry<BlockPos, CompoundTag> e : snap.blockEntities.entrySet()) {
            BlockPos p = e.getKey();
            BlockEntity be = BlockEntity.loadStatic(p, level.getBlockState(p), e.getValue(), level.registryAccess());
            if (be != null) {
                level.getChunkAt(p).setBlockEntity(be);
                bes++;
            }
        }

        // 4. Entities.
        int ents = 0;
        for (CompoundTag tag : snap.entities) {
            Entity ent = EntityType.loadEntityRecursive(tag, level, en -> en);
            if (ent != null) {
                level.addFreshEntityWithPassengers(ent);
                ents++;
            }
        }

        return changed + " blocks changed, " + bes + " block entities, " + ents + " entities";
    }

    /**
     * Moves the sandbox copy of the player to the fork origin and gives it the real
     * player's inventory / health / food / xp.
     *
     * @return false if the player has not finished joining the sandbox yet (retry next tick).
     */
    public static boolean placePlayer(MinecraftServer server, ForkSnapshot snap, GameType mode, UUID id) {
        ServerPlayer sp = server.getPlayerList().getPlayer(id);
        if (sp == null) return false;
        ServerLevel level = resolveLevel(server, snap.dimension);
        CompoundTag d = snap.playerData;

        sp.setGameMode(mode);
        sp.getInventory().clearContent();
        sp.getInventory().load(d.getList("Inventory", Tag.TAG_COMPOUND));
        sp.getInventory().selected = d.getInt("SelectedItemSlot");
        sp.getFoodData().readAdditionalSaveData(d);
        sp.experienceLevel = d.getInt("XpLevel");
        sp.experienceProgress = d.getFloat("XpP");
        sp.totalExperience = d.getInt("XpTotal");
        if (d.contains("Health")) sp.setHealth(Math.max(1.0F, d.getFloat("Health")));
        sp.fallDistance = 0;
        sp.setDeltaMovement(0, 0, 0);
        sp.teleportTo(level, snap.px, snap.py, snap.pz, snap.yaw, snap.pitch);
        sp.inventoryMenu.sendAllDataToRemote();
        return true;
    }

    private static ServerLevel resolveLevel(MinecraftServer server, String dimension) {
        try {
            ResourceKey<Level> key = ResourceKey.create(Registries.DIMENSION, ResourceLocation.parse(dimension));
            ServerLevel l = server.getLevel(key);
            if (l != null) return l;
        } catch (Exception ignored) {
        }
        return server.overworld(); // modded dimensions don't exist in the sandbox yet
    }
}
