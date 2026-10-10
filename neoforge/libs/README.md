# libs/ — no binaries are committed here

**This folder contains exactly one file: this README.** The two jars it names
(`sable-companion-common-1.21.1-1.6.0.jar`, ~35 KB, and `ponder-neoforge-1.0.82+mc1.21.1.jar`,
~800 KB) are not committed; `.gitignore` covers them.

They are third-party binaries: Sable's nested `companion` jar and Create's nested `ponder`
(catnip) jar, redistributed inside their respective mod jars. They are **unpacked at build
time, on the machine that has the mods installed**, into:

```
neoforge/build/physics-libs/
```

## Where they come from and what pulls them in

`gradlew build` runs the `extractNestedPhysicsLibs` task before anything is compiled. It looks in
the directory named by **`physicsModsDir`** in `neoforge/gradle.properties`:

```properties
physicsModsDir=D:/MC mod/红石电路/模组
```

and finds each jar in two ways, in this order:

1. **already unpacked** — a file matching the name is lying directly in that folder, e.g.
   `sable-companion-common-1.21.1-1.6.0.jar` or `ponder-neoforge-1.0.82+mc1.21.1.jar`;
2. **nested in a mod jar** — otherwise every `*.jar` in that folder is opened and the first
   `META-INF/jarjar/<name>` entry matching the needed name is extracted.

Needed, with why:

| file | nested inside | needed because |
|---|---|---|
| `sable-companion-common-*.jar` | `sable-neoforge-*.jar` → `META-INF/jarjar/` | `dev.ryanhcode.sable.companion.*` (the only position→structure lookup) is not in `dev.ryanhcode.sable.api`. Without it the compat source set does not compile. |
| `ponder-*.jar` | `create-*.jar` → `META-INF/jarjar/` | `ContraptionWorld extends net.createmod.catnip.levelWrappers.WrappedLevel`; Java resolves the whole supertype chain before any assignment, so catnip has to be on the compile classpath. |

Everything extracted there is wired in as **`compileOnly`**: it is never bundled into the mod jar
and never looked for at runtime.

## What happens when they are missing

Nothing fails. If neither form can be found, the build logs one line starting with
`[redstonecircuit]` and **excludes the optional compat source set** (`neoforge/src/compat/java`,
i.e. `com.jiangyuefengyu.redstonecircuit.compat.physics`) from compilation. The main mod compiles,
the tests run, and a clone with no mods installed builds with `gradlew build`. `/rc physics` then answers
"physics compatibility is not built into this jar" instead of doing anything.

## How to refresh / change the version

Nothing to rebuild by hand - put the new mod jar (or the new inner jar) in `physicsModsDir` and
run any Gradle command. The task derives the file name it wants from the latest matching candidate
in that folder, so a version bump is picked up automatically. To point at a different folder, edit
`physicsModsDir` in `neoforge/gradle.properties` or pass `-PphysicsModsDir=...` on the command
line. To confirm what it chose, look for the

```
[redstonecircuit] physics compat compileOnly: <path>  (source: <path>)
```

lines in the build log, one per jar.
