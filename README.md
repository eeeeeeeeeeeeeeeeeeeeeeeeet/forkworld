# Fork World (NeoForge 1.21.1)

Fork the area around you into a private sandbox, rehearse, then **merge** the winning
input sequence into the real world by replaying it through your real client. The real
server only ever sees a normal player sending normal packets.

## Status

All pieces are written. **Nothing has been compiled or run** - expect small API fix-ups
on first build (see "Verify first" below).

| Piece | File |
|---|---|
| Snapshot of blocks / block entities / entities | `ForkSnapshot` |
| Copy snapshot into the sandbox, place the player | `SnapshotLoader` |
| Second integrated server inside the client | `SandboxServer` |
| Session swap + sandbox lifecycle | `ForkBackend`, `ClientSession` |
| Park the real connection's packets, keep it alive | `PacketHolder`, `mixin/ConnectionMixin` |
| Input recorder / replayer (merge) | `InputRecorder`, `Recording`, `InputFrame`, `Replayer` |
| Keys, HUD panel, wiring | `ForkKeys`, `ForkHud`, `ForkClient` |

## Keys (rebindable)

- **G** fork: capture the area, boot the sandbox, move you into it, start recording
- **N** rewind the fork (also happens automatically if you die in it)
- **B** finish the fork, return to the real world and merge the recording
- **M** discard the fork (or cancel a merge in progress)
- **V** debug only (`-Dforkworld.debug=true`, on by default in `runClient`): record in the REAL world

## How it works

1. G captures a `ForkSnapshot` and boots (or reuses) the `SandboxServer`.
2. `SnapshotLoader` rebuilds the area in the sandbox world.
3. The real session (`ClientLevel`, `LocalPlayer`, `MultiPlayerGameMode`, packet listener)
   is parked. While parked, the real connection stays open but `ConnectionMixin` diverts its
   packets into `PacketHolder`; keep-alives and pings are answered immediately.
4. The client logs into the sandbox over an in-memory connection and the sandbox player is
   teleported to the fork origin with your inventory/health.
5. B / M: the sandbox connection is closed, the real session is swapped back in and the held
   packets are replayed in order. B then starts the merge (`Replayer`).

Safety: you are automatically returned if you lose 2+ hearts in the real world meanwhile.
Merge refuses to start unless you stand within 0.3 blocks of the fork origin, and aborts as
soon as you are more than 0.5 blocks from where the recording expects you.

## Verify first (written without compiler or decompiled sources)

1. `Minecraft.updateLevelInEngines` - the access transformer in
   `META-INF/accesstransformer.cfg` assumes this private method name.
2. `ForkBackend.beginConnect` - compare with `Minecraft.doWorldLoad`
   (`Connection.connectToLocalServer`, `initiateServerboundPlayConnection`,
   `ClientHandshakePacketListenerImpl` constructor).
3. `ConnectionMixin` - target `Connection.channelRead0`.
4. Packet getters in `PacketHolder` (`getId()`, `getHealth()`).
5. `SnapshotLoader.placePlayer` - `Inventory.load`, `FoodData.readAdditionalSaveData`.
6. Whether anything still ticks the real connection badly while parked, or whether some
   vanilla handler touches `mc.player` outside `PacketHolder`'s coverage.

## Known limits

- The corner "window" is a status panel (real world paused, held packets, real health), not
  a second render of the real world.
- Server reconfiguration packets (`ClientboundStartConfigurationPacket`) are held too, which
  can desync the protocol state if the real server sends one while you are away.
- The sandbox uses a generated overworld that is overwritten around the fork origin; dimensions
  from other mods fall back to the overworld.
- Mobs, other players and lag break exact replays; a merge is best-effort.
- Replaying recorded input counts as macro/TAS use on many servers. Check the rules first.

## Build

`./gradlew runClient` (copy the Gradle wrapper from the NeoForge MDK if missing). If
`neo_version` in `gradle.properties` does not resolve, pick the latest `21.1.x` from
https://projects.neoforged.net/neoforged/neoforge.
