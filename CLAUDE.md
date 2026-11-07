# Bounding Box Viewer

Client-only Fabric mod for Minecraft 26.3, singleplayer (or LAN host) only: B toggles boxes around every structure the integrated server has loaded within range (default 128 blocks across the ground): a thick outer box in the kind's colour, thinner boxes per piece, the spawn area shaded (from the structure's own `spawnOverrides`), and the name over the top. Covers every vanilla structure (`StructureKind`, grouped by dimension for the config screen) plus mod/datapack ones as `OTHER` with a colour hashed from the id. Config via Cloth Config + Mod Menu (optional). Built for the user's MultiMC instance at `E:\MultiMC\instances\26.3` (mods in `.minecraft\mods`). Set up like `..\light-level-overlay` and `..\blaze-spawner-finder` (same toolchain, test and VS Code conventions).

## Commands

- `.\gradlew.bat build`: compile, run unit tests, jar in `build\libs` (ship `bounding-box-viewer-<version>.jar`, not `-sources`)
- `.\gradlew.bat runClientGameTest`: real client, default-preset world (Fabric's test worlds are superflat unless the preset is set) with seed 607. Presses B for real; for every `minecraft:` structure in the registry (each in the dimension whose `getGeneratorState().getPlacementsForStructure` isn't empty; fails on one no dimension places, or a `StructureKind` no structure maps to) locates the nearest with `findNearestMapStructure` (radius 100 like `/locate`; End from 1500 0), teleports over it in spectator (12 above its top; outside the Nether at least 20 above the generator's `getBaseHeight`, since `level.getHeight` gives the world's bottom for an unloaded chunk; spectator so a camera inside Nether rock sees through it instead of the in-a-wall overlay) and checks the shown box, pieces, spawn area (`SPAWN_AREAS`, `NONE` for the rest) and kind against the server's `StructureStart` as it is after the chunks load (retried while pieces settle); at each spot checks every start and reference in the 11 x 11 full chunks around the player is in `StructureStore` with the server's box and pieces; hiding a kind, pieces/shading/names off, min range, see-through; B off clears; store empty after the world closes. Screenshots (one per structure, named by its id; a see-through one too for the underground and underwater ones) into `build\run\clientGameTest\screenshots`. Muted via `src/gametest/.../MuteAudio`.
- `.\gradlew.bat runClient`: dev client
- `.\gradlew.bat genSources`: decompiled Minecraft sources (not generated on this machine yet; names were checked with `javap` against `~\.gradle\caches\fabric-loom\minecraftMaven\net\minecraft\minecraft-{common,clientonly}-deobf\26.3\*.jar`)
- `.\tools\check-deps.ps1 -ModsDir 'E:\MultiMC\instances\26.3\.minecraft\mods' -Staged build\libs\bounding-box-viewer-<version>.jar` (PowerShell 7): must print `PROBLEMS: 0`
- `python -I tools/make-icon.py`: redraws `icon.png` (a purple wireframe box around a swamp hut, 32 x 32 scaled to 128)

## How it works (checked in the 26.3 jar)

- Data: the client never gets structures from a server, so it reads the integrated server's (`StructureCollector`, server thread). `ServerChunkEvents.CHUNK_LOAD` gives each full chunk's `getAllStarts()`; its `getAllReferences()` name starts in other chunks, maybe unloaded, which are queued and read 8 per server tick (`LOADS_PER_TICK`) with `getChunk(x, z, STRUCTURE_STARTS, true)` + `getStartForStructure` (starts are saved at that stage, so this doesn't generate terrain). Everything is cleared on `SERVER_STARTING` / `SERVER_STOPPED`. Collection always runs, whether the boxes are on or not, so turning them on shows what's already loaded.
- Boxes: the outer box is `structure.adjustBoundingBox(union of the pieces' current boxes)` (`StructureCollector.bounds`), what `StructureStart.getBoundingBox()` gives after a reload. Not `getBoundingBox()` itself: it caches that on first call (at the references stage), but `ScatteredFeaturePiece`s (swamp huts, temples, igloos) and template pieces (shipwrecks, ocean ruins) move onto the ground when their chunk is decorated, so the cache keeps the planned height until the world reloads (seed 607's first swamp hut: planned y 64-70). `adjustBoundingBox` inflates by 12 when `terrainAdaptation() != NONE` (villages, outposts, strongholds, ancient cities, trail ruins, trial chambers). For the same reason, when a chunk loads with `generated` true (Fabric: it wasn't loaded back from disk as full, so it was just decorated), its starts are recorded again and its references to known starts are queued again (`QUEUED` dedupes the queue; `SEEN` stops unknown ones being looked for twice). The game's STRUCTURE-type spawn check (`StructureManager.getStructureAt`) uses the cached box, but outposts and monuments don't move, so it's the same box.
- `StructureStore`: per dimension, keyed by (structure id, start chunk), `ConcurrentHashMap` because the server thread writes and the client thread reads. Nothing is ever evicted within a world.
- Spawn area: `Structure.spawnOverrides()`; an override with an empty `spawns()` list only blocks spawns, so it's ignored. `BoundingBoxType.STRUCTURE` (outposts, monuments) shades the whole box; `PIECE` (swamp huts, fortresses) shades each piece.
- Drawing: vanilla gizmos, no custom render types or mixins. `BoxOverlay` rebuilds a `List<Gizmo>` every 5 client ticks (immediately on a dimension change or `rebuildSoon()`): `new CuboidGizmo(aabb, style, false)` per box and `new TextGizmo(pos, text, TextGizmo.Style.forColorAndCentered(argb).withScale(s))` per name (billboarded; scale / 16 world units per pixel; names grow with distance past 12 blocks so they keep their size on screen). `BoxRenderer` adds that list every frame from `LevelRenderEvents.BEFORE_GIZMOS` (fired just before `LevelRenderer.finalizeGizmoCollection`) inside `levelRenderer().collectPerFrameRenderThreadGizmos()`, with `Gizmos.addGizmo(g).setAlwaysOnTop()` when see-through is on. Not `DrawableGizmoPrimitives.submit(collector, camera, true)` from `COLLECT_SUBMITS`: the game only sets up its always-on-top pass (`frameHasAlwaysOnTopGizmos`, its own depth target) when its own list has always-on-top gizmos, so those submits were silently dropped (depth-tested ones worked).
- Range is horizontal distance from the eye to the box (any height), so strongholds and ancient cities under you count.
- Keys: `KeyMappingHelper.registerKeyMapping`, category `KeyMapping.Category.register(Identifier)`. B is unbound in vanilla 26.3. Off in multiplayer: `minecraft.hasSingleplayerServer()` false → a message, nothing turns on.

## Gotchas

- Needs JDK 25; `gradle/gradle-daemon-jvm.properties` makes the wrapper use it although `JAVA_HOME` is JDK 17.
- 26.x is unobfuscated Mojang names: `Identifier` (not `ResourceLocation`), `ResourceKey.identifier()`, `ChunkPos` is a record (`x()`, `z()`, `pack()`, `unpack(long)`, `getX(long)`, `ChunkPos.containing`), `player.sendOverlayMessage`.
- Loom plugin is `net.fabricmc.fabric-loom` (no remapping): plain `implementation`/`compileOnly`/`localRuntime`.
- `StructureKind.of` only maps `minecraft:` ids, by key or key + `_variant` (`village_plains`, `abandoned_camp_cherry_grove`, `ruined_portal_nether`); anything else is `OTHER`. A new vanilla structure needs a kind, a lang entry and a line in `StructureKindTest`.
- Game tests: load chunks inside `computeOnServer` with `getChunk(x, z, STRUCTURE_STARTS, true)`; reset `BoxConfig` fields at the start (the test client reads the dev run's `config/bounding-box-viewer.json`).
- IDE is VS Code. `.vscode/launch.json` is hand-written; don't run `gradlew vscode`.

## Layout

- `src/main`: `structure/` is pure Java (no Minecraft), unit tested in `src/test`: `Box` (inclusive block corners, distances), `StructureKind` (id → kind, variant, colour), `SpawnArea`.
- `src/client`: `world/` (`StructureCollector`, `StructureStore`, `StructureInfo`); `overlay/` (`BoxOverlay` lays out, `BoxRenderer` submits, `Labels`); `input/BoxKeys`; `config/` (`BoxConfig` Gson, `ConfigScreen` Cloth Config, `ModMenuIntegration`). No mixins.
- `src/gametest`: the client game test and `MuteAudio`.

## Working with the user

Terse; wants things done and recommended, not surveyed. Ask before committing, pushing, or touching the MultiMC `mods` folder. Don't touch `..\light-level-overlay`, `..\inventory-sorter` or `..\villager-trade-helper` (someone's work in progress).

