# libs/ — no binaries are committed here

**This folder contains exactly one file: this README.** The two jars it names are not
committed; `.gitignore` covers them (`fabric/libs/*.jar`).

Both are third-party binaries that the Fabric build compiles against, `modCompileOnly` only —
they are **not** bundled into the mod jar and not shipped:

| file | what it is | why it is needed |
|---|---|---|
| `modmenu-11.0.5.jar` | Mod Menu (MIT) | `RedstoneCircuitModMenu` implements its `ModMenuApi`, so the
  mod-list entry (icon, links) compiles; at runtime Mod Menu is optional |
| `placeholder-api-2.4.2+1.21.jar` | Text Placeholder API (CC0) | two of the shipped language files use
  placeholder tokens, so the build must see the API to compile against it; optional at runtime |

## Where they come from

Copied by hand from a normal Fabric instance's `mods/` folder (both are drag-and-drop mods), and
can be re-obtained the same way at any time. The build only needs the file name to match what
`fabric/build.gradle` names in its `modCompileOnly files("libs/…")` lines.

A machine without them fails to compile the two client-side integration classes; nothing else in
the mod depends on them.
