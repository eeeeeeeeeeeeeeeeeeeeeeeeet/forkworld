package com.example.forkworld;

import com.mojang.blaze3d.platform.InputConstants;
import net.minecraft.client.KeyMapping;
import net.neoforged.api.distmarker.Dist;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.client.event.RegisterKeyMappingsEvent;
import org.lwjgl.glfw.GLFW;

@EventBusSubscriber(modid = ForkWorld.MODID, bus = EventBusSubscriber.Bus.MOD, value = Dist.CLIENT)
public final class ForkKeys {
    private static final String CATEGORY = "key.categories.forkworld";

    public static final KeyMapping FORK = key("fork", GLFW.GLFW_KEY_G);
    public static final KeyMapping SLOW_FORK = key("slow_fork", GLFW.GLFW_KEY_J);
    public static final KeyMapping REWIND = key("rewind", GLFW.GLFW_KEY_N);
    public static final KeyMapping DISCARD = key("discard", GLFW.GLFW_KEY_M);
    public static final KeyMapping MERGE = key("merge", GLFW.GLFW_KEY_B);
    public static final KeyMapping DEBUG_RECORD = key("debug_record", GLFW.GLFW_KEY_V);

    private ForkKeys() {
    }

    private static KeyMapping key(String name, int code) {
        return new KeyMapping("key.forkworld." + name, InputConstants.Type.KEYSYM, code, CATEGORY);
    }

    @SubscribeEvent
    public static void register(RegisterKeyMappingsEvent e) {
        e.register(FORK);
        e.register(SLOW_FORK);
        e.register(REWIND);
        e.register(DISCARD);
        e.register(MERGE);
        e.register(DEBUG_RECORD);
    }
}
