package com.example.forkworld;

import net.minecraft.client.DeltaTracker;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.Font;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.resources.ResourceLocation;
import net.neoforged.api.distmarker.Dist;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.client.event.RegisterGuiLayersEvent;

/**
 * The "window" onto the real world. It is a status panel, not a second render of the world:
 * it shows that the real world is paused, how many packets are waiting, and the real health.
 */
@EventBusSubscriber(modid = ForkWorld.MODID, bus = EventBusSubscriber.Bus.MOD, value = Dist.CLIENT)
public final class ForkHud {
    private ForkHud() {
    }

    @SubscribeEvent
    public static void register(RegisterGuiLayersEvent e) {
        e.registerAboveAll(ResourceLocation.fromNamespaceAndPath(ForkWorld.MODID, "real_world"), ForkHud::render);
    }

    private static void render(GuiGraphics g, DeltaTracker delta) {
        ForkBackend b = ForkClient.BACKEND;
        if (!b.isActive()) return;
        Minecraft mc = Minecraft.getInstance();
        Font font = mc.font;

        float hp = PacketHolder.lastHealth;
        int w = 158, h = 46, x = g.guiWidth() - w - 6, y = 6;
        g.fill(x, y, x + w, y + h, 0xAA000000);
        g.renderOutline(x, y, w, h, 0xFF55FFFF);
        g.drawString(font, "FORK  " + b.statusLine(), x + 5, y + 5, 0xFFFFFFFF, false);
        g.drawString(font, "Real world: paused", x + 5, y + 17, 0xFFAAAAAA, false);
        g.drawString(font, "Held packets: " + PacketHolder.size(), x + 5, y + 28, 0xFFAAAAAA, false);
        String health = hp < 0 ? "Real health: unchanged" : String.format("Real health: %.1f hearts", hp / 2f);
        g.drawString(font, health, x + 5, y + 39 - 1, hp >= 0 && hp < 8 ? 0xFFFF5555 : 0xFFAAAAAA, false);
    }
}
