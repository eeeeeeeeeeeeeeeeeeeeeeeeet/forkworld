package com.example.forkworld;

import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.screens.GenericMessageScreen;
import net.minecraft.client.multiplayer.ClientHandshakePacketListenerImpl;
import net.minecraft.network.Connection;
import net.minecraft.network.chat.Component;
import net.minecraft.network.protocol.login.ServerboundHelloPacket;
import net.minecraft.world.level.GameType;

import java.net.SocketAddress;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;

/**
 * Runs the private simulation.
 *
 * Flow: boot (or reuse) the {@link SandboxServer} -> copy the snapshot into it ->
 * park the real session and log into the sandbox over an in-memory connection ->
 * move the sandbox player to the fork origin -> record inputs -> on finish/discard swap
 * the real session back in and replay the packets that were held meanwhile.
 *
 * Everything here runs on the client thread, driven by {@link #tick()}.
 */
public final class ForkBackend implements SandboxBackend {

    private enum State { IDLE, BOOTING, LOADING, CONNECTING, PLACING, ACTIVE }

    /** Leave the fork automatically if the real player loses this much health meanwhile (4 = 2 hearts). */
    private static final float AUTO_RETURN_DAMAGE = 4.0f;
    private static final int TIMEOUT_TICKS = 20 * 60;

    private final InputRecorder recorder = new InputRecorder();

    private State state = State.IDLE;
    private int stateTicks;
    private ForkSnapshot snap;
    private GameType mode = GameType.SURVIVAL;
    private float realHealthAtFork;

    private SandboxServer server;
    private CompletableFuture<String> loadTask;
    private CompletableFuture<Boolean> placeTask;

    private ClientSession real;
    private ClientSession sandbox;
    private Connection pending;
    private boolean slowMo;

    private static Minecraft mc() {
        return Minecraft.getInstance();
    }

    // ------------------------------------------------------------------ SandboxBackend

    @Override
    public void start(ForkSnapshot snapshot) {
        begin(snapshot, false);
    }

    /** Same as start, but the sandbox runs at one tenth speed; the merge replays at normal speed. */
    public void startSlow(ForkSnapshot snapshot) {
        begin(snapshot, true);
    }

    private void begin(ForkSnapshot snapshot, boolean slow) {
        Minecraft mc = mc();
        if (state != State.IDLE || mc.player == null || mc.level == null || mc.gameMode == null) return;
        slowMo = slow;
        snap = snapshot;
        mode = mc.gameMode.getPlayerMode();
        realHealthAtFork = mc.player.getHealth();
        try {
            if (server == null || !server.isRunning()) {
                say("Booting sandbox server...");
                server = SandboxServer.boot(mc);
            }
        } catch (Throwable t) {
            ForkWorld.LOGGER.error("Sandbox boot failed", t);
            say("Sandbox boot failed: " + t + " (see latest.log)");
            return;
        }
        setState(State.BOOTING);
    }

    @Override
    public boolean isActive() {
        return state != State.IDLE;
    }

    @Override
    public void rewind() {
        if (state != State.ACTIVE) return;
        recorder.stop();
        Minecraft mc = mc();
        if (mc.player != null && mc.player.isDeadOrDying()) mc.player.respawn();
        startLoad();
    }

    @Override
    public Recording finish() {
        Recording r = state == State.ACTIVE ? recorder.stop() : null;
        recorder.stop();
        endSession();
        return r;
    }

    @Override
    public void discard() {
        recorder.stop();
        endSession();
    }

    // ------------------------------------------------------------------ for the HUD

    public String statusLine() {
        return switch (state) {
            case IDLE -> "idle";
            case BOOTING -> "starting sandbox...";
            case LOADING -> "copying area...";
            case CONNECTING -> "entering sandbox...";
            case PLACING -> "placing player...";
            case ACTIVE -> "recording " + recorder.size() + " ticks";
        };
    }

    // ------------------------------------------------------------------ state machine

    /** Call every client tick (also while the client has no world, during connecting). */
    public void tick() {
        if (state == State.IDLE) return;
        stateTicks++;
        try {
            switch (state) {
                case BOOTING -> tickBooting();
                case LOADING -> tickLoading();
                case CONNECTING -> tickConnecting();
                case PLACING -> tickPlacing();
                case ACTIVE -> tickActive();
                default -> {
                }
            }
        } catch (Throwable t) {
            ForkWorld.LOGGER.error("Fork failed", t);
            fail(String.valueOf(t));
        }
    }

    private void tickBooting() {
        if (server.isReady()) {
            startLoad();
        } else if (stateTicks > TIMEOUT_TICKS) {
            fail("sandbox server did not finish starting");
        }
    }

    private void startLoad() {
        final SandboxServer s = server;
        final ForkSnapshot sn = snap;
        loadTask = s.submit(() -> SnapshotLoader.apply(s, sn));
        setState(State.LOADING);
    }

    private void tickLoading() throws Exception {
        if (!loadTask.isDone()) {
            if (stateTicks > TIMEOUT_TICKS) fail("copying the area timed out");
            return;
        }
        String result = loadTask.get();
        say("Sandbox ready (" + result + ")");
        if (sandbox == null) {
            beginConnect();
        } else { // rewind: already inside the sandbox
            placeTask = null;
            setState(State.PLACING);
        }
    }

    private void beginConnect() throws Exception {
        Minecraft mc = mc();
        real = ClientSession.capture(mc);
        PacketHolder.hold(real.connection());
        real.park(mc);
        mc.setScreen(new GenericMessageScreen(Component.literal("Entering fork...")));

        SocketAddress addr = server.getConnection().startMemoryChannel();
        pending = Connection.connectToLocalServer(addr);
        // The next two calls mirror Minecraft.doWorldLoad; if the signatures differ in your
        // 1.21.1 build, copy that block from Minecraft.java.
        pending.initiateServerboundPlayConnection(addr.toString(), 0,
                new ClientHandshakePacketListenerImpl(pending, mc, null, null, false, null, c -> {
                }, null));
        pending.send(new ServerboundHelloPacket(mc.getUser().getName(), mc.getUser().getProfileId()));
        setState(State.CONNECTING);
    }

    private void tickConnecting() {
        Minecraft mc = mc();
        if (!pending.isConnected()) {
            fail("the sandbox connection closed");
            return;
        }
        // Until login finishes nothing else ticks this connection (vanilla's pendingConnection
        // is private). After login MultiPlayerGameMode.tick() does it.
        if (mc.gameMode == null) pending.tick();

        if (mc.player != null && mc.level != null && mc.player.connection.getConnection() == pending) {
            sandbox = ClientSession.capture(mc);
            placeTask = null;
            setState(State.PLACING);
            return;
        }
        if (stateTicks > TIMEOUT_TICKS) fail("timed out connecting to the sandbox");
    }

    private void tickPlacing() throws Exception {
        Minecraft mc = mc();
        if (mc.player == null) return;
        if (placeTask == null) {
            final SandboxServer s = server;
            final ForkSnapshot sn = snap;
            final GameType m = mode;
            final UUID id = mc.getUser().getProfileId();
            placeTask = s.submit(() -> SnapshotLoader.placePlayer(s, sn, m, id));
            return;
        }
        if (!placeTask.isDone()) return;
        if (!placeTask.get()) { // player has not joined the sandbox yet
            placeTask = null;
            if (stateTicks > TIMEOUT_TICKS) fail("player never joined the sandbox");
            return;
        }
        if (mc.screen == null && mc.player.distanceToSqr(snap.px, snap.py, snap.pz) < 0.25) {
            final SandboxServer ts = server;
            final float rate = slowMo ? 2f : 20f;
            ts.submit(() -> ts.tickRateManager().setTickRate(rate));
            recorder.start(mc);
            setState(State.ACTIVE);
            say("In the fork. " + ForkKeys.REWIND.getTranslatedKeyMessage().getString() + " rewind, "
                    + ForkKeys.MERGE.getTranslatedKeyMessage().getString() + " finish + merge, "
                    + ForkKeys.DISCARD.getTranslatedKeyMessage().getString() + " discard.");
        } else if (stateTicks > TIMEOUT_TICKS) {
            fail("could not place the player at the fork origin");
        }
    }

    private void tickActive() {
        Minecraft mc = mc();
        if (!real.connection().isConnected()) {
            say("Lost the connection to the real server.");
            discard();
            return;
        }
        float h = PacketHolder.lastHealth;
        if (h >= 0 && realHealthAtFork - h >= AUTO_RETURN_DAMAGE) {
            say("You are taking damage in the real world - returning.");
            discard();
            return;
        }
        if (mc.player != null && mc.player.isDeadOrDying()) {
            say("Died in the fork - rewinding.");
            rewind();
            return;
        }
        recorder.sample(mc);
    }

    // ------------------------------------------------------------------ teardown

    private void fail(String why) {
        say("Fork failed: " + why);
        recorder.stop();
        endSession();
    }

    private void endSession() {
        if (state == State.IDLE) return;
        Minecraft mc = mc();

        if (server != null && server.isRunning()) {
            final SandboxServer rs = server;
            rs.submit(() -> rs.tickRateManager().setTickRate(20f));
        }

        // 1. Kill the sandbox connection first: queued sandbox packets check isConnected() and
        //    are dropped, so they cannot touch the real player once we swap back.
        Connection sandboxConn = sandbox != null ? sandbox.connection() : pending;
        if (sandboxConn != null) sandboxConn.disconnect(Component.literal("Fork ended"));

        // 2. Put the real session back and replay what the real server said while we were away.
        if (real != null) {
            real.install(mc);
            PacketHolder.release(real.connection(), real.listener());
            if (!real.connection().isConnected()) real.connection().handleDisconnection();
        }
        PacketHolder.clear();

        sandbox = null;
        pending = null;
        real = null;
        loadTask = null;
        placeTask = null;
        setState(State.IDLE);

        // 3. Multiplayer: the sandbox owns NeoForge's global server state, so stop it fully.
        //    Singleplayer: keep it idle and reuse it (halting would break the real world).
        if (server != null && server.ownsLifecycle()) {
            try {
                server.halt(true);
            } catch (Throwable t) {
                ForkWorld.LOGGER.warn("Stopping the sandbox failed", t);
            }
            server.cleanupFiles();
            server = null;
        }
    }

    private void setState(State s) {
        state = s;
        stateTicks = 0;
    }

    private static void say(String s) {
        Minecraft mc = mc();
        if (mc.gui != null) mc.gui.getChat().addMessage(Component.literal("[Fork] " + s));
    }
}