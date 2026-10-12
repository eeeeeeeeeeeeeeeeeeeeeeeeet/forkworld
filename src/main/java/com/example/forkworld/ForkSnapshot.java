package com.example.forkworld;

import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.client.player.LocalPlayer;
import net.minecraft.core.BlockPos;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.chunk.LevelChunk;
import net.minecraft.world.level.chunk.LevelChunkSection;
import net.minecraft.world.level.chunk.status.ChunkStatus;
import net.minecraft.world.phys.AABB;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * Immutable copy of everything the client currently knows about the area
 * around the player. This is the input a sandbox backend turns into a
 * private ServerLevel.
 *
 * Caveat: the client only knows what the server synced to it, so entity
 * data (mob AI state, some attributes) and block entity data may be partial.
 */
public final class ForkSnapshot {

    /** One 16x16x16 section: palette + 4096 indices, index = (y<<8)|(z<<4)|x. */
    public record Section(int chunkX, int chunkZ, int sectionY, List<BlockState> palette, short[] indices) {
    }

    public final String dimension;
    public final long gameTime, dayTime;
    public final double px, py, pz;
    public final float yaw, pitch;
    public final CompoundTag playerData;
    public final List<Section> sections = new ArrayList<>();
    public final Map<BlockPos, CompoundTag> blockEntities = new HashMap<>();
    public final List<CompoundTag> entities = new ArrayList<>();

    private ForkSnapshot(ClientLevel level, LocalPlayer p) {
        dimension = level.dimension().location().toString();
        gameTime = level.getGameTime();
        dayTime = level.getDayTime();
        px = p.getX();
        py = p.getY();
        pz = p.getZ();
        yaw = p.getYRot();
        pitch = p.getXRot();
        playerData = p.saveWithoutId(new CompoundTag());
    }

    public static ForkSnapshot capture(ClientLevel level, LocalPlayer player, int radiusChunks) {
        ForkSnapshot s = new ForkSnapshot(level, player);
        ChunkPos center = player.chunkPosition();
        int minSection = level.getMinSection();
        for (int dx = -radiusChunks; dx <= radiusChunks; dx++) {
            for (int dz = -radiusChunks; dz <= radiusChunks; dz++) {
                int cx = center.x + dx, cz = center.z + dz;
                LevelChunk chunk = level.getChunkSource().getChunk(cx, cz, ChunkStatus.FULL, false);
                if (chunk == null) continue;
                LevelChunkSection[] secs = chunk.getSections();
                for (int i = 0; i < secs.length; i++) {
                    LevelChunkSection sec = secs[i];
                    if (sec == null || sec.hasOnlyAir()) continue;
                    Map<BlockState, Integer> index = new HashMap<>();
                    List<BlockState> palette = new ArrayList<>();
                    short[] data = new short[4096];
                    for (int y = 0; y < 16; y++) {
                        for (int z = 0; z < 16; z++) {
                            for (int x = 0; x < 16; x++) {
                                BlockState st = sec.getBlockState(x, y, z);
                                Integer id = index.get(st);
                                if (id == null) {
                                    id = palette.size();
                                    palette.add(st);
                                    index.put(st, id);
                                }
                                data[(y << 8) | (z << 4) | x] = (short) (int) id;
                            }
                        }
                    }
                    s.sections.add(new Section(cx, cz, minSection + i, palette, data));
                }
                for (Map.Entry<BlockPos, BlockEntity> e : chunk.getBlockEntities().entrySet()) {
                    s.blockEntities.put(e.getKey().immutable(),
                            e.getValue().saveWithFullMetadata(level.registryAccess()));
                }
            }
        }
        double r = radiusChunks * 16.0 + 8.0;
        AABB box = player.getBoundingBox().inflate(r, 512, r);
        for (Entity e : level.getEntities((Entity) null, box, ent -> !(ent instanceof Player))) {
            CompoundTag tag = new CompoundTag();
            if (e.save(tag)) {
                s.entities.add(tag);
            }
        }
        return s;
    }

    public String summary() {
        return sections.size() + " sections, " + blockEntities.size() + " block entities, "
                + entities.size() + " entities";
    }
}
