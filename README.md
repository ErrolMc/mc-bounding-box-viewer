# Bounding Box Viewer

A client-side Fabric mod for Minecraft 26.3 that draws the bounding boxes of structures in a singleplayer world: swamp huts, pillager outposts, ocean monuments, nether fortresses, end cities, villages, strongholds, mineshafts, temples, mansions, bastions, ancient cities, trial chambers and the rest, plus structures from mods and datapacks. Press **B** to turn them on and off.

## What it shows

- **The whole structure**: a thick box in the structure's colour, the game's own box for it, with its name over the top ("Village (Plains)", "Nether Fortress", "Ruined Portal (Mountain)"). For structures that reshape the ground around them (villages, outposts, strongholds, ancient cities, trail ruins, trial chambers) the game's box reaches 12 blocks past the pieces on every side; that whole box is where an outpost's pillagers spawn.
- **Its pieces**: thinner boxes for each piece inside it: a fortress's corridors and bridges, a village's houses and roads, a mineshaft's tunnels. Structures that are one piece (a swamp hut) just get the one box.
- **Where its mobs spawn**, shaded: witches in a swamp hut, blazes and wither skeletons in a fortress's pieces, pillagers anywhere in an outpost's box, guardians anywhere in a monument's. This is the game's own spawn rule for the structure, so it works for datapack structures with spawn overrides too.

Structures show once the game has loaded them, so as you move around more appear. A structure that starts in a chunk that isn't loaded yet but reaches into one that is (a stronghold's corridor, a village's road) shows as well.

## Keys

- **B**: boxes on and off. Says how many structures are within range.
- **Boxes through blocks** (unbound at first): draws them on top of everything, to see strongholds, mineshafts and fortresses inside the ground.

Both are under Options → Controls → Key Binds → Bounding Box Viewer.

## Settings

With Mod Menu and Cloth Config installed, the mod's config screen has:

- **Range** (16 to 512, 128 by default): how far out, across the ground, a box can be and still show.
- **Show through blocks**, **Show pieces**, **Shade spawn areas**, **Show names**.
- **Structures**: a switch for each kind, grouped by dimension, each in its box colour.

Settings are saved in `config/bounding-box-viewer.json`.

## Limits

- **Singleplayer only** (and the host of a LAN world): the mod reads the structures from the game's built-in server. A multiplayer server doesn't send structures to the game, so there it says so and draws nothing.
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
.\gradlew.bat runClientGameTest   # in-game test (muted): a world with a known seed, visits one of every structure in the game and checks its box against the server's
.\gradlew.bat genSources          # decompiled Minecraft sources for the editor
```

The icon is drawn by `python -I tools/make-icon.py`.

## Licence

MIT
