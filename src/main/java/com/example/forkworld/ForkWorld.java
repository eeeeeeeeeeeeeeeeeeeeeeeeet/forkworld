package com.example.forkworld;

import net.neoforged.fml.common.Mod;

/**
 * Fork World: fork the area around you into a private local simulation,
 * rehearse freely, then merge the winning input sequence back into the real
 * world by replaying it through the real client (ordinary packets).
 */
@Mod(ForkWorld.MODID)
public class ForkWorld {
    public static final String MODID = "forkworld";
    public static final org.slf4j.Logger LOGGER = com.mojang.logging.LogUtils.getLogger();

    /** Set by the client once it is up. Null on a dedicated server. */
    public static volatile SandboxBackend backend = null;
}
