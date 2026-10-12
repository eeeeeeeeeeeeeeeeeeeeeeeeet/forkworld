package com.example.forkworld;

import net.minecraft.client.Minecraft;
import net.minecraft.network.chat.Component;
import net.neoforged.api.distmarker.Dist;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.fml.loading.FMLPaths;
import net.neoforged.neoforge.client.event.ClientTickEvent;
import net.neoforged.neoforge.client.event.MovementInputUpdateEvent;

import java.io.IOException;
import java.nio.file.Path;

@EventBusSubscriber(modid = ForkWorld.MODID, bus = EventBusSubscriber.Bus.GAME, value = Dist.CLIENT)
public final class ForkClient {
    /** Real-world recording is a debug aid only; enable with -Dforkworld.debug=true. */
    private static final boolean DEBUG = Boolean.getBoolean("forkworld.debug");
    private static final int SNAPSHOT_RADIUS_CHUNKS = 4;

    public static final ForkBackend BACKEND = new ForkBackend();

    static {
        ForkWorld.backend = BACKEND;
    }

    private static final InputRecorder DEBUG_RECORDER = new InputRecorder();
    private static final Replayer REPLAYER = new Replayer();

    private static Recording last;

    private ForkClient() {
    }

    private static Path file() {
        return FMLPaths.GAMEDIR.get().resolve("forkworld").resolve("last.json");
    }

    private static void msg(Minecraft mc, String s, boolean overlay) {
        if (mc.player != null) mc.player.displayClientMessage(Component.literal("[Fork] " + s), overlay);
    }

    @SubscribeEvent
    public static void onTickPre(ClientTickEvent.Pre e) {
        Minecraft mc = Minecraft.getInstance();
        if (mc.player == null || mc.level == null) return;
        if (REPLAYER.isActive()) {
            // Abort hotkey must work during a merge.
            while (ForkKeys.DISCARD.consumeClick()) {
                REPLAYER.abort(mc, "cancelled by player");
            }
        }
        REPLAYER.pre(mc);
    }

    @SubscribeEvent
    public static void onInput(MovementInputUpdateEvent e) {
        REPLAYER.overrideInput(e.getInput());
    }

    @SubscribeEvent
    public static void onTickPost(ClientTickEvent.Post e) {
        Minecraft mc = Minecraft.getInstance();

        // Must run even while the client has no world (the sandbox login happens then).
        BACKEND.tick();

        if (mc.player == null || mc.level == null) return;

        DEBUG_RECORDER.sample(mc);

        boolean gPressed = false, jPressed = false;
        while (ForkKeys.FORK.consumeClick()) gPressed = true;
        while (ForkKeys.SLOW_FORK.consumeClick()) jPressed = true;
        if (gPressed || jPressed) {
            if (BACKEND.isActive()) {
                msg(mc, "Already in a fork (" + BACKEND.statusLine() + ").", true);
            } else if (!REPLAYER.isActive() && !DEBUG_RECORDER.isActive()) {
                ForkSnapshot snapshot = ForkSnapshot.capture(mc.level, mc.player, SNAPSHOT_RADIUS_CHUNKS);
                msg(mc, "Captured " + snapshot.summary() + (jPressed ? " (slow motion)." : "."), false);
                if (jPressed) BACKEND.startSlow(snapshot);
                else BACKEND.start(snapshot);
            }
        }

        while (ForkKeys.REWIND.consumeClick()) {
            if (BACKEND.isActive()) {
                BACKEND.rewind();
                msg(mc, "Rewinding...", true);
            }
        }

        while (ForkKeys.MERGE.consumeClick()) {
            if (REPLAYER.isActive() || DEBUG_RECORDER.isActive()) continue;
            Recording r;
            if (BACKEND.isActive()) {
                r = BACKEND.finish(); // swaps the real session back in
                if (r == null || r.frames.isEmpty()) {
                    msg(mc, "Fork ended, nothing was recorded.", false);
                    continue;
                }
                try {
                    r.save(file());
                } catch (IOException ex) {
                    msg(mc, "Could not save the recording: " + ex.getMessage(), false);
                }
            } else {
                r = last;
                if (r == null) {
                    try {
                        r = Recording.load(file());
                    } catch (IOException ex) {
                        msg(mc, "Nothing to merge.", true);
                        continue;
                    }
                }
            }
            last = r;
            String err = REPLAYER.start(mc, r);
            if (err != null) {
                msg(mc, "Cannot merge: " + err, false);
            } else {
                msg(mc, "Merging " + r.frames.size() + " ticks... ("
                        + ForkKeys.DISCARD.getTranslatedKeyMessage().getString() + " to cancel)", false);
            }
        }

        while (ForkKeys.DISCARD.consumeClick()) {
            if (BACKEND.isActive()) {
                BACKEND.discard();
                msg(mc, "Fork discarded.", true);
            }
        }

        while (ForkKeys.DEBUG_RECORD.consumeClick()) {
            if (!DEBUG) {
                msg(mc, "Debug recording is off (launch with -Dforkworld.debug=true).", true);
                continue;
            }
            if (BACKEND.isActive()) continue;
            if (!DEBUG_RECORDER.isActive()) {
                DEBUG_RECORDER.start(mc);
                msg(mc, "DEBUG: recording in the REAL world", true);
            } else {
                last = DEBUG_RECORDER.stop();
                try {
                    last.save(file());
                } catch (IOException ex) {
                    msg(mc, "Could not save: " + ex.getMessage(), false);
                }
                msg(mc, "DEBUG: saved " + last.frames.size() + " ticks. Walk back to the start and press merge.", false);
            }
        }
    }
}
