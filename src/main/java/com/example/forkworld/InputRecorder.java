package com.example.forkworld;

import net.minecraft.client.Minecraft;
import net.minecraft.client.player.Input;
import net.minecraft.client.player.LocalPlayer;

/** Samples the local player's input once per client tick (call at the end of the tick). */
public final class InputRecorder {
    private Recording recording;

    public boolean isActive() {
        return recording != null;
    }

    public int size() {
        return recording == null ? 0 : recording.frames.size();
    }

    public void start(Minecraft mc) {
        LocalPlayer p = mc.player;
        Recording r = new Recording();
        r.dimension = mc.level.dimension().location().toString();
        r.x = p.getX();
        r.y = p.getY();
        r.z = p.getZ();
        r.yaw = p.getYRot();
        r.pitch = p.getXRot();
        r.startSlot = p.getInventory().selected;
        recording = r;
    }

    public Recording stop() {
        Recording r = recording;
        recording = null;
        return r;
    }

    public void sample(Minecraft mc) {
        if (recording == null || mc.player == null) return;
        LocalPlayer p = mc.player;
        Input in = p.input;
        recording.frames.add(new InputFrame(
                in.forwardImpulse, in.leftImpulse,
                in.jumping, in.shiftKeyDown, p.isSprinting(),
                p.getYRot(), p.getXRot(),
                p.getInventory().selected,
                mc.options.keyAttack.isDown(), mc.options.keyUse.isDown(),
                p.getX(), p.getY(), p.getZ()));
    }
}
