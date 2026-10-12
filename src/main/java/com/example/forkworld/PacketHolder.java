package com.example.forkworld;

import net.minecraft.network.Connection;
import net.minecraft.network.PacketListener;
import net.minecraft.network.protocol.Packet;
import net.minecraft.network.protocol.common.ClientboundKeepAlivePacket;
import net.minecraft.network.protocol.common.ClientboundPingPacket;
import net.minecraft.network.protocol.common.ServerboundKeepAlivePacket;
import net.minecraft.network.protocol.common.ServerboundPongPacket;
import net.minecraft.network.protocol.game.ClientboundSetHealthPacket;

import java.util.ArrayList;
import java.util.List;

/**
 * While the player is inside the sandbox the REAL connection stays open but its packets are
 * parked here instead of being applied (many vanilla handlers touch {@code minecraft.player},
 * which is the sandbox player at that point). Keep-alives and pings are answered immediately so
 * the real server never times us out. On return the parked packets are replayed in order.
 */
public final class PacketHolder {
    private static final Object LOCK = new Object();
    private static Connection held;
    private static final List<Packet<?>> QUEUE = new ArrayList<>();

    /** Most recent health the real server told us about while we were away; -1 = unknown. */
    public static volatile float lastHealth = -1f;

    private PacketHolder() {
    }

    public static void hold(Connection c) {
        synchronized (LOCK) {
            held = c;
            QUEUE.clear();
            lastHealth = -1f;
        }
    }

    /** Netty thread. @return true if the packet was taken (and must not be processed now). */
    public static boolean offer(Connection c, Packet<?> p) {
        synchronized (LOCK) {
            if (c != held) return false;
            if (p instanceof ClientboundKeepAlivePacket k) {
                c.send(new ServerboundKeepAlivePacket(k.getId()));
                return true;
            }
            if (p instanceof ClientboundPingPacket ping) {
                c.send(new ServerboundPongPacket(ping.getId()));
                return true;
            }
            if (p instanceof ClientboundSetHealthPacket h) {
                lastHealth = h.getHealth();
            }
            QUEUE.add(p);
            return true;
        }
    }

    public static boolean isHolding() {
        synchronized (LOCK) {
            return held != null;
        }
    }

    public static int size() {
        synchronized (LOCK) {
            return QUEUE.size();
        }
    }

    public static void clear() {
        synchronized (LOCK) {
            held = null;
            QUEUE.clear();
        }
    }

    /** Client thread: stop holding and replay everything that was parked, in order. */
    @SuppressWarnings({"unchecked", "rawtypes"})
    public static void release(Connection c, PacketListener listener) {
        List<Packet<?>> drained;
        synchronized (LOCK) {
            if (held == c) held = null;
            drained = new ArrayList<>(QUEUE);
            QUEUE.clear();
        }
        for (Packet<?> p : drained) {
            try {
                ((Packet) p).handle(listener);
            } catch (Throwable t) {
                ForkWorld.LOGGER.warn("Replaying held packet {} failed", p.getClass().getSimpleName(), t);
            }
        }
    }
}
