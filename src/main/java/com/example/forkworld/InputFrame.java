package com.example.forkworld;

/** One client tick of input plus the position the player ended that tick at. */
public record InputFrame(
        float forward, float strafe,
        boolean jump, boolean sneak, boolean sprint,
        float yaw, float pitch,
        int slot,
        boolean attack, boolean use,
        double x, double y, double z) {
}
