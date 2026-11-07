# Bounding Box Viewer

A client-side Fabric mod for Minecraft 26.3 that draws the bounding boxes of structures: swamp huts, pillager outposts, ocean monuments, nether fortresses, end cities, villages, strongholds, mineshafts, temples, mansions, bastions, ancient cities, trial chambers and the rest, plus structures from mods and datapacks. Press **B** to turn them on and off. Works in singleplayer, and on servers and Realms from the world's seed (see below).

## What it shows

- **The whole structure**: a thick box in the structure's colour, the game's own box for it, with its name over the top ("Village (Plains)", "Nether Fortress", "Ruined Portal (Mountain)"). For structures that reshape the ground around them (villages, outposts, strongholds, ancient cities, trail ruins, trial chambers) the game's box reaches 12 blocks past the pieces on every side; that whole box is where an outpost's pillagers spawn.
- **Its pieces**: thinner boxes for each piece inside it: a fortress's corridors and bridges, a village's houses and roads, a mineshaft's tunnels. Structures that are one piece (a swamp hut) just get the one box.
- **Where its mobs spawn**, shaded: witches in a swamp hut, blazes and wither skeletons in a fortress's pieces, pillagers anywhere in an outpost's box, guardians anywhere in a monument's. This is the game's own spawn rule for the structure, so it works for datapack structures with spawn overrides too.

Structures show once the game has loaded them, so as you move around more appear. A structure that starts in a chunk that isn't loaded yet but reaches into one that is (a stronghold's corridor, a village's road) shows as well.

## On a server or Realm

A server never tells the game where its structures are, so on one (a multiplayer server, a Realm, a LAN world someone else hosts) the mod works them out from the world's seed, the same way the server did when it generated them. It needs the seed once per server:

- **If you're an operator**, run `/seed`: the mod picks the seed up from the reply.
- **Otherwise** ask whoever runs the server for it and run `/boxes seed <seed>`. For a world that isn't the default type add the type: `/boxes seed 123456 large_biomes` or `amplified`. A seed in words works as the create-world screen takes it.

The seed is saved for that server (a Realm by its name, any other server by its address), so next time B just works. `/boxes seed` shows the seed in use; `/boxes seed clear` forgets it.

How close it is, from the seed alone:

- **Where each structure is, what it is, and its pieces are exact**: the same calculation the server makes. The in-game tests check this against a real server over seven worlds, all three dimensions and tens of thousands of chunks.
- **A few kinds settle onto the ground when their chunk generates**: swamp huts, desert and jungle temples, igloos, shipwrecks, ocean ruins and buried treasure. Seed mode puts them on the ground the seed makes, before caves, trees, lakes and other structures change it. In the tests (about 900 pieces over seven worlds) swamp huts, jungle temples and igloos landed exactly where the server put them every time; desert temples, shipwrecks and ocean ruins 80 to 95% of the time, the rest mostly a few blocks off and now and then up to 20 where a cave or another ruin was in the way; buried treasure within 2 blocks, and it moves onto its chest once its chunk has loaded.
- **Vanilla world generation only**: on a server whose datapacks or mods change world generation, or a superflat or custom world, the boxes won't match. In a world upgraded from an older version, old chunks keep the old structures; seed mode shows what 26.3 would generate.

## Keys

- **B**: boxes on and off. Says how many structures are within range. On a server with no seed saved, says how to give it one.
- **Boxes through blocks** (unbound at first): draws them on top of everything, to see strongholds, mineshafts and fortresses inside the ground.

Both are under Options → Controls → Key Binds → Bounding Box Viewer.

## Settings

With Mod Menu and Cloth Config installed, the mod's config screen has:

- **Range** (16 to 512, 128 by default): how far out, across the ground, a box can be and still show.
- **Show through blocks**, **Show pieces**, **Shade spawn areas**, **Show names**.
- **Structures**: a switch for each kind, grouped by dimension, each in its box colour.

Settings are saved in `config/bounding-box-viewer.json`.

## Limits

- **On servers it needs the seed** (above). A Realm's seed is saved by the Realm's name, so after switching the Realm to another world slot, give the new world's seed with `/boxes seed`.
- **Boxes are where the structure was planned**, not checked block by block: a piece that the terrain or another structure cut into still shows whole.

## Install

1. Build it (below) or take `bounding-box-viewer-<version>.jar` from `build/libs` (not the `-sources` jar).
2. Put it in the instance's `mods` folder next to Fabric API (and Mod Menu and Cloth Config for the settings screen).
3. Restart the game.

Requires Minecraft 26.3, Fabric Loader 0.20.1+, Fabric API and Java 25.

## Development (VS Code)

1. Install JDK 25 (this machine has it at `C:\Program Files\Java\jdk-25.0.5.0.2`; `.vscode/settings.json` points there).
2. Open the folder in VS Code and install the recommended extensions when asked (Extension Pack for Java, Gradle for Java).
3. Run **Terminal → Run Task → Decompile Minecraft sources** once, then **Java: Clean Java Language Server Workspace** from the command palette.
4. Press **F5** (launch config "Minecraft Client") to start a dev client through Gradle with the debugger attached.

Gradle tasks, from VS Code's task menu or a terminal:

```
.\gradlew.bat build               # compile, unit tests, jar in build\libs
.\gradlew.bat runClient           # dev client without the debugger
.\gradlew.bat runClientGameTest   # in-game tests (muted): visits one of every structure in the game and checks its box against the server's; seed mode against the server in seven worlds; seed mode end to end on a dedicated server
.\gradlew.bat genSources          # decompiled Minecraft sources for the editor
```

The icon is drawn by `python -I tools/make-icon.py`.

## Licence

MIT
