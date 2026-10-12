package com.example.forkworld;

import net.minecraft.client.Minecraft;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.client.multiplayer.ClientPacketListener;
import net.minecraft.client.multiplayer.MultiPlayerGameMode;
import net.minecraft.client.player.LocalPlayer;
import net.minecraft.network.Connection;

/**
 * Everything that makes up "being connected to a world" on the client. Swapping these fields
 * is how the client is moved between the real world and the sandbox.
 */
record ClientSession(ClientLevel level, LocalPlayer player, MultiPlayerGameMode gameMode,
                     ClientPacketListener listener, Connection connection) {

    static ClientSession capture(Minecraft mc) {
        ClientPacketListener l = mc.player.connection;
        return new ClientSession(mc.level, mc.player, mc.gameMode, l, l.getConnection());
    }

    /** Detach this session from the client so another connection can log in from scratch. */
    void park(Minecraft mc) {
        mc.getSoundManager().stop();
        mc.level = null;
        mc.player = null;
        mc.gameMode = null;
        mc.hitResult = null;
        mc.crosshairPickEntity = null;
        mc.setCameraEntity(null);
        mc.updateLevelInEngines(null); // access transformer, see accesstransformer.cfg
    }

    /** Make this session the one the client is playing in. */
    void install(Minecraft mc) {
        mc.getSoundManager().stop();
        mc.level = level;
        mc.player = player;
        mc.gameMode = gameMode;
        mc.hitResult = null;
        mc.crosshairPickEntity = null;
        mc.setCameraEntity(player);
        mc.updateLevelInEngines(level);
        // Must come after the fields are set, otherwise vanilla opens the title screen.
        mc.setScreen(null);
    }
}
