# Redstone Circuit

**English** · [简体中文](README.zh-CN.md)

Put redstone components *inside* blocks and see them with the **redstone goggles** - and mount components on **any face** of a block.

* Minecraft **1.21.1** (required on both client and server)
* **NeoForge**: 21.1.100+ (**21.1.250 or newer recommended**) · version **1.3.1** · `redstonecircuit-1.3.1-neoforge-1.21.1.jar`
* **Fabric**: Fabric Loader 0.15.11+ and **Fabric API** · version **1.2.1** · `redstonecircuit-1.2.1-fabric-1.21.1.jar`
* The two loaders are versioned and released independently - install the jar for the loader you use
* Author: 江月风雨

---

## What it is

Vanilla redstone has to lie on the ground, which takes space and does not look good. This mod lets dust, repeaters,
comparators and the rest be stored **inside** a block (stone, planks, any full opaque block) while that block still
emits redstone exactly as the component would - and the redstone goggles let you see the wiring, facing, delay and
connections inside it. On top of that it adds some new redstone components, and every one of them can also be
**mounted on any face** of a block.

## Features

| Feature | What it does |
|---|---|
| Components inside blocks | Dust, repeaters, comparators, torches, levers and buttons go inside a block with shift-right-click; empty-handed shift-right-click takes them back |
| Same as vanilla | No vanilla redstone behaviour is changed: emission side, weak/strong power, delays and decay all follow the vanilla rules |
| **Surface components** | Vanilla's **repeater, comparator, redstone dust, redstone torch, stone / oak / light weighted / heavy weighted pressure plate**, plus this mod's **superconducting dust, retaining repeater, double repeater, double retaining repeater, trigger, controller, redstone transformer, clock source, signal extender and weather detector**, can all be mounted on the top, bottom and four side faces of a block. They have **no item of their own**: use the matching item on the faces vanilla has no answer for (a repeater's top-face click is still the vanilla repeater; a wall side or a block's underside becomes the surface version), and breaking one gives that item back |
| One cell, six faces | Each face of one block can carry a piece of its own, independently; a left click removes only the layer you are looking at |
| Redstone goggles | Worn on the head (no armour value), they turn blocks that hold redstone translucent and draw the component inside; taking them off restores the normal look. **What is drawn inside is vanilla's own model and texture**: repeaters, comparators, torches, levers and buttons borrow the vanilla block's own model, and the dust is drawn as vanilla redstone dust |
| Redstone wrench | Right-click a component to select it, then shift-right-click a neighbouring block to lock that one line and cut the rest automatically; several lines can be locked, and the far end may be a piston, a redstone lamp or anything else that has no redstone inside |
| Superconducting redstone dust | Copper ingot in the middle plus 8 redstone → 9. Used exactly like dust, but the **signal does not decay**, however far it runs; right-click a block to lay it, shift-right-click to store it inside |
| Retaining repeater | Looks like a repeater, but it **passes on the strength it was given** (a vanilla repeater always restores 15). Same recipe as the repeater, with the three stone replaced by three iron ingots |
| Double repeater | Two **repeaters** stacked vertically. The settings look the same, but **every delay is doubled**: settings 1/2/3/4 take 4/8/12/16 game ticks (vanilla takes 2/4/6/8) |
| Double retaining repeater | Two **retaining repeaters** stacked vertically. Same doubling, and it still passes on the strength it was given |
| Weather detector | Like a daylight detector, but it watches the weather: an empty-hand right-click **cycles** clear / rain / thunder, and while the weather matches it outputs a steady 15. The three settings are mutually exclusive - thunder only counts as thunder, it does not make the rain setting output. **It never needs to see the sky**, so it works in a basement. It can also be **worn on the head** (an easter egg): +4 attack damage in clear weather, +4 max health in rain, and a lightning bolt lands on whatever you hit during a thunderstorm |
| Rotation rod | Turns a component that has a facing: each click steps it one place round a **fixed six-step cycle** (north, east, south, west, up, down, back to north), so **up and down are reachable**; the face you click on does not matter. It also turns **vanilla repeaters and comparators and every surface component**; the **four plates (controller / redstone transformer / clock source / signal extender)** turn within their own plane (on a wall, up and down are part of that cycle) and the face they hang on never moves. Dust has no facing, so it cannot be turned (the goggles must be worn) |
| Jade support | With [Jade](https://www.curseforge.com/minecraft/mc-mods/jade) installed: aiming at a retaining / double / double retaining repeater shows "delay: setting N (M ticks)", a weather detector shows its mode and output, a **block holding a repeater** shows the setting of the one inside, and a **surface component** shows which face it sits on. **Not having Jade changes nothing** - the mod does not depend on it |
| Goggles into a helmet | Craft the **redstone goggles** together with **any helmet** (crafting table or the 2×2 grid) and that helmet stays itself - same name, enchantments and durability - with the goggles' effect added |
| Sounds inside a structure | While a structure runs, the sounds of its repeaters, levers, buttons and note blocks come from **the block that owns the structure**, with normal distance falloff |
| Settings with an empty hand | Right-click a repeater's block to change its delay, a comparator's block to switch compare/subtract (exactly like right-clicking the component on the ground) |
| Non-invasive | No vanilla block and no vanilla world data is changed; everything is kept in the world's own saved data |
| Redstone structure block | Select a region to **capture** it: **the first operation only shows the border, the second one really does it**. What you capture goes into a 结构 (Structure) item you can carry, and it can be **released** in place or anywhere else by the block. What travels keeps working - the pistons, redstone and hoppers inside are **real blocks**. The item carries a 3D preview |
| Driving a structure | **Face table**: each of the six faces injects into the cells you name while it is energized. **Key panel**: a **6×6 grid of buttons with pages**, each bound to a name and a cell, pressing one pushes a single pulse. **Output table**: a cell carrying a signal makes one face answer with a set strength. All cells are counted from **the selection's first cell** (0,0,0 is the corner you started the box on), so the numbers survive a capture |
| Logic integrator | Seven logic gates, one input face and one output face |
| Transmission gate | Main input on the front, main output on the back, four side inputs; the main input passes only while **exactly one side input** carries a signal |
| Block placer | Nine slots of storage; it places what it holds in front of itself |
| Controller | Compares the front signal against a set strength and only then outputs on the back |
| Redstone transformer | Outputs the signal it receives on the front at a set strength on the back; with no input it outputs 0 |
| Clock source | One pulse of a set strength every (seconds × 20 + ticks), lasting the pulse length you set (typed the same way, seconds + ticks, 2 ticks by default; longer than the period means it never stops); 0 seconds 0 ticks switches it off |
| Signal extender | Passes the input through while it is there, then holds the output for a set duration |
| Where the four plates mount | Controller / redstone transformer / clock source / signal extender can be mounted on **any face** (floor, wall, ceiling - all six). On a wall or a ceiling the "front" (input) face lies in the plate's own plane and is chosen by where you look when you place it; if the block behind it is broken (or pushed away by a piston) the plate drops |

## Installation

**NeoForge**

1. Install **NeoForge 21.1.100 or newer** for Minecraft **1.21.1** (**21.1.250 or newer recommended**).
   * The minimum declared in the jar is `[21.1.100,)`, and that floor is **measured**: this build compiles against it and
     passes 300 classes / 2083 unit tests + 326 GameTests. An older 21.1.x is rejected by the loader as a version mismatch.
2. Put `redstonecircuit-1.3.1-neoforge-1.21.1.jar` into `.minecraft/mods` (client and server both).
3. Start the game; there are no other dependencies.

**Fabric**

1. Install **Fabric Loader 0.15.11 or newer** for Minecraft **1.21.1**, and put **Fabric API** into `mods` as well.
   * The minimum loader declared in the metadata is `>=0.15.11`, also **measured**: this build is pinned to it and
     passes 300 classes / 2118 unit tests + 327 GameTests.
   * **Fabric API is a dependency of the Fabric build** (declared in `fabric.mod.json`'s `depends`); the NeoForge build needs nothing else.
2. Put `redstonecircuit-1.2.1-fabric-1.21.1.jar` into `.minecraft/mods` (client and server both).
3. Start the game.

The gameplay, the configuration keys and the commands are one design on both loaders; the two jars are **versioned and
released independently** (NeoForge 1.3.x, Fabric 1.2.x), so please say which one you use when reporting a problem. The one
functional difference: **the Create / Create: Aeronautics physics-structure adaptation exists only in the NeoForge build**
(the Fabric build does not include it); Jade is supported on both.

Optional: with [Jade](https://www.curseforge.com/minecraft/mc-mods/jade) installed, aiming at a repeater, a weather
detector, a surface component or a block holding redstone shows its setting, mode or mounting face - the adaptation ships
with the mod, so Jade alone is enough. Without it everything works as usual (on both loaders).

## Crafting

* **Redstone goggles**: `glass · redstone · glass` (one row)
* **Redstone wrench**: three `redstone` stacked vertically
* **Superconducting redstone dust**: a **copper ingot** in the middle of a 3×3 with 8 **redstone** around it → **9**
* **Retaining repeater**: exactly like a vanilla repeater (`torch · redstone · torch` on top, three **iron ingots** below), with the stone replaced by iron
* **Double repeater**: two **repeaters** stacked vertically
* **Double retaining repeater**: two **retaining repeaters** stacked vertically
* **Rotation rod**: three `redstone` stacked vertically, plus one more **redstone** to the **left** of the top one (a mirrored `┐`)
* **Weather detector**: `lightning rod` on the top middle; `quartz · sculk sensor · quartz` in the middle row; three `stone` below
* **Redstone structure block**: `redstone·cobblestone·redstone / cobblestone·nether star·cobblestone / redstone·cobblestone·redstone` (**nether star** in the middle)
* **Logic integrator**: `copper·lapis·copper / quartz·redstone·quartz / copper·lapis·copper`
* **Transmission gate**: `redstone / redstone·quartz block·redstone / redstone` (a cross with a **quartz block** in the middle)
* **Block placer**: `cobblestone·planks·cobblestone / planks·iron ingot·planks / cobblestone·planks·cobblestone` (any planks)
* **Controller**: top row empty; one `redstone` in the middle; three `gold ingots` below
* **Redstone transformer**: `quartz·copper·quartz / redstone·redstone·redstone / quartz·copper·quartz`
* **Clock source**: `empty·quartz·empty / gold·clock·gold / empty·quartz·empty` (a **vanilla clock** in the middle)
* **Signal extender**: `iron·gold·iron / lapis·redstone·lapis / iron·gold·iron`
* **Goggles into a helmet**: put the **goggles** and **any helmet** in the same crafting grid (crafting table or the 2×2 grid) and you get that helmet with the goggles' effect - name, enchantments and durability are kept

**Surface components have no recipe**: each one uses the item of its own family, and no new item was added.

## Usage

* **Into a block**: hold the component and **shift-right-click** the target block (the face you click becomes its input face).
* **Back out**: empty-handed **shift-right-click** that block.
* **Onto a face**: hold the matching item and right-click **any face**. A face vanilla can already use keeps the vanilla
  block (a repeater's top face, dust on the ground); a face vanilla has no answer for (a wall side, a block's underside,
  a ceiling) becomes the surface version, with the same signal rules as the floor version.
* **Remove one layer**: left-click that face; the other faces of the same block are untouched.
* **Switches and settings**: **right-click** a block holding a lever, a button, a repeater or a comparator with an empty hand.
* **Rewiring**: hold the **redstone wrench**, right-click a component to select it, then **shift-right-click** a neighbouring
  block to lock the connection. Repeating the same gesture cycles `locked → cut → automatic`; shift-right-clicking the
  selected block itself clears all of its overrides. (The goggles must be worn.)

## Configuration

`config/redstonecircuit-common.toml` (the Fabric build uses `config/redstonecircuit.json` with the same keys):

| Key | Default | Meaning |
|---|---|---|
| `hostOverlayMode` | `GOGGLES` | `GOGGLES` draws the inside only while the goggles are worn; `ALWAYS` without them; `OFF` never |
| `hostOverlayAlpha` / `Color` / `Frame*` | 150 / light blue / red | Look of the shell and the frame |
| `superconductorFrameColor` | `FF8C00` | Blocks holding **superconducting dust** get their own frame colour (the dust's orange by default) so they stand out from dust blocks; set it equal to `hostOverlayFrameColor` to turn that off |
| `showInnerComponents` | `true` | Whether the component inside is drawn |
| `seeInnerComponentsThroughWalls` | `false` | Turns the line-of-sight test off (true x-ray) |
| `wrenchNeedsGoggles` | `true` | Whether the wrench requires the goggles |
| `hostAcceptsStrongPower` | `true` | Whether the block accepts the strong power a neighbouring solid block conducts (vanilla semantics) |
| `hostConductsStrongPower` | `false` | Whether a block holding a component conducts that **strong power on to its own neighbours** the way a plain stone block would. Off (the default) means a repeater pointing at another repeater's block only delivers the signal to the component inside and does not light the dust beside that block; on is exactly the vanilla-block semantics |
| `slotSyncRange` | `0` | Sync range for component data; 0 = every player in the dimension |
| `structureMaxSize` | `48` | Longest edge a structure block can select (48³ = 110,592 cells) |
| `structurePreviewCells` | `4096` | Most cells in the 3D preview; beyond that the preview is coarsened (together with `structurePreviewBytes`, the coarser of the two wins) |
| `structureMaxLoadedPockets` / `structureMaxLoadedColumns` | `16` / `256` | How many structures may be loaded at once (counted in chunk columns). Raising it lets more structures run at the same time, at the cost of memory and load time |
| `structurePocketSounds` | `true` | Whether sounds made inside a structure play at the block that owns it (off leaves them silent, inside the pocket only) |
| `debugLog` | per build | Detailed logging. **A release build defaults to `false`** (so the log stays quiet); development builds default to `true`. Even a release build can be asked for it with `-Dredstonecircuit.debugLog=true` |

## Reporting problems

Please attach `logs/latest.log` (this mod's lines start with `[redstonecircuit]` and carry the block state, where the
signal came from and any stack trace), say whether you use the **NeoForge or the Fabric build**, and describe what you were doing.

## License

**MIT** - see [LICENSE](LICENSE). Use it, change it, ship it; keep the copyright notice.

Minecraft itself and its original assets belong to Mojang Studios; a few textures in this mod are recolours of vanilla
textures (see Notes below) and are not covered by this project's MIT licence.

`TEMPLATE_LICENSE.txt` is the MIT licence of the NeoForged MDK template itself and has nothing to do with this mod's licence.

## Notes

* The components inside a block **use the vanilla block's own model and textures**: repeaters, comparators, torches,
  levers and buttons borrow the state of the matching vanilla block to take its baked model, and the dust is assembled
  from vanilla `redstone_wire` parts. Only vanilla resources are **referenced** (`minecraft:block/...`) and the game loads
  them from its own jar - no vanilla asset is copied. The side effect is that a resource pack changes what you see inside a
  block too. The retaining repeater, both double repeaters and the weather detector use **this mod's own models and
  textures** (geometry borrowed from the vanilla repeater / daylight detector, textures made for this mod), and they are
  drawn as themselves inside a block as well.
* **Surface components** (since 1.3.1 / 1.2.1) reference existing textures only: repeaters, comparators, torches and the four
  pressure plates use the vanilla block's own textures, dust uses vanilla `redstone_dust_line` plus the overlay, and the
  superconducting sheet uses this mod's orange dust texture. **No new bitmap was added**; each face combination gets its own
  model file, which is why `models/block/surface_*.json` is numerous - that is state expansion, not new art.
* Jade support is a **soft dependency**: only Jade's API is used at compile time (`compileOnly`), the shipped jar contains
  none of Jade's code, and the metadata marks it `optional`, so a player without Jade is unaffected. With Jade installed it
  finds our plugin class on its own and shows repeater settings, weather detector modes and which face a surface component
  sits on.
* The superconducting dust and wire textures are **recolours** (orange) of vanilla's redstone dust texture, and the goggles'
  head texture is a recolour of the vanilla leather helmet. Every other texture (goggles, wrench, icon) was made for this
  mod. If you have concerns about derived assets, replace the files under
  `src/main/resources/assets/redstonecircuit/textures/`.
* The mod uses Mixin to let a vanilla block "emit the signal of the component inside it" (each loader has its own mixin
  configuration); no vanilla logic is overwritten - the injections only return early.
* The repository holds `neoforge/` and `fabric/`, two independent Gradle builds that do not interfere: `cd` into one and
  run `./gradlew build` there.
* **Known and deliberate**: pushing a block that holds redstone away with a piston leaves a residual signal at the old
  position (only the "broken by a player" path drops the component). The author keeps that as a small quirk.
