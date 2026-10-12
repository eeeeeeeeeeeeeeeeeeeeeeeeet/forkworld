package com.example.forkworld;

import net.minecraft.client.KeyMapping;
import net.minecraft.client.Minecraft;
import net.minecraft.client.player.Input;
import net.minecraft.client.player.LocalPlayer;
import net.minecraft.network.chat.Component;

/**
 * Merges a recording into the real world by driving the real client's input,
 * one frame per tick. The server just sees an ordinary player sending
 * ordinary movement / interaction packets.
 *
 * Safety: if the player's actual position drifts from where the recording
 * says it should be, the replay aborts immediately rather than continuing
 * blind in a world that no longer matches the simulation.
 */
public final class Replayer {
    private static final double START_TOLERANCE = 0.3;
    private static final double DIVERGENCE_LIMIT = 0.5;

    private Recording rec;
    private int tick;
    private boolean prevAttack, prevUse;

    public boolean isActive() {
        return rec != null;
    }

    public int tick() {
        return tick;
    }

    public int total() {
        return rec == null ? 0 : rec.frames.size();
    }

    /** @return null on success, otherwise the reason it could not start. */
    public String start(Minecraft mc, Recording r) {
        LocalPlayer p = mc.player;
        if (p == null || mc.level == null) return "not in a world";
        if (r == null || r.frames.isEmpty()) return "recording is empty";
        if (!mc.level.dimension().location().toString().equals(r.dimension)) {
            return "wrong dimension (recorded in " + r.dimension + ")";
        }
        double d = Math.sqrt(p.distanceToSqr(r.x, r.y, r.z));
        if (d > START_TOLERANCE) {
            return String.format("stand at the fork origin first (%.1f blocks away: %.1f %.1f %.1f)",
                    d, r.x, r.y, r.z);
        }
        rec = r;
        tick = 0;
        prevAttack = false;
        prevUse = false;
        return null;
    }

    public void abort(Minecraft mc, String why) {
        finish(mc);
        if (mc.player != null) {
            mc.player.displayClientMessage(Component.literal("[Fork] Merge aborted: " + why), false);
        }
    }

    /** Call at the START of each client tick, before the game reads keybinds. */
    public void pre(Minecraft mc) {
        if (rec == null) return;
        LocalPlayer p = mc.player;
        if (p == null || mc.level == null) {
            finish(mc);
            return;
        }
        if (tick >= rec.frames.size()) {
            finish(mc);
            p.displayClientMessage(Component.literal("[Fork] Merge complete (" + tick + " ticks)"), true);
            return;
        }
        if (tick > 0) {
            InputFrame last = rec.frames.get(tick - 1);
            double dev = Math.sqrt(p.distanceToSqr(last.x(), last.y(), last.z()));
            if (dev > DIVERGENCE_LIMIT) {
                abort(mc, String.format("world diverged at tick %d (%.2f blocks off)", tick, dev));
                return;
            }
        }
        InputFrame f = rec.frames.get(tick);
        p.setYRot(f.yaw());
        p.setXRot(f.pitch());
        p.getInventory().selected = f.slot();
        press(mc.options.keyAttack, f.attack(), prevAttack);
        press(mc.options.keyUse, f.use(), prevUse);
        mc.options.keySprint.setDown(f.sprint());
        prevAttack = f.attack();
        prevUse = f.use();
        tick++;
    }

    /** Call from MovementInputUpdateEvent, after the game has built this tick's input. */
    public void overrideInput(Input in) {
        if (rec == null || tick == 0 || tick > rec.frames.size()) return;
        InputFrame f = rec.frames.get(tick - 1);
        in.forwardImpulse = f.forward();
        in.leftImpulse = f.strafe();
        in.up = f.forward() > 0;
        in.down = f.forward() < 0;
        in.left = f.strafe() > 0;
        in.right = f.strafe() < 0;
        in.jumping = f.jump();
        in.shiftKeyDown = f.sneak();
    }

    private static void press(KeyMapping key, boolean down, boolean wasDown) {
        key.setDown(down);
        if (down && !wasDown) {
            KeyMapping.click(key.getKey());
        }
    }

    private void finish(Minecraft mc) {
        rec = null;
        mc.options.keyAttack.setDown(false);
        mc.options.keyUse.setDown(false);
        mc.options.keySprint.setDown(false);
    }
}
