# Flywheel per-level compat — change summary (2026-08-02)

This documents the changes that made the flywheel compat per-level on the no-global refactor
branch. For the full architecture explanation (how flywheel connects to the game, how the compat
piggybacks on it, why the design looks the way it does), read `docs/flywheel.md` — this file is
just what changed and why, for review and merge.

## The problem

The flywheel compat was the last code still calling `ColoredLightEngine.getInstance()`, which on
this branch throws `"Unsupported."` — so flywheel colored light crashed the moment a section was
collected. The compat also kept a single global `ColoredLightFlywheelStorage`, which cannot work
per-level: with Immersive Portals (or a Ponder scene) two levels have live flywheel engines at
once, their section coordinates overlap, and one shared arena both mixes dimensions' colors and
breaks the arena-index parity the shaders rely on.

The key that unblocks everything: flywheel is per-level all the way down, and
`dev.engine_room.flywheel.backend.engine.LightStorage` — the exact class we already mixin into —
is constructed per level and exposes its level through a **public `level()` method**. Every one of
our hook points either is a `LightStorage` or receives one as a parameter.

## Changes by file

### New: `compat/flywheel/ColoredLightStorageHolder.java`
Duck interface implemented on flywheel's `LightStorage` by our mixin. Anything holding a
`LightStorage` reaches the matching per-level colored storage through it instead of a global.

### `compat/flywheel/ColoredLightFlywheelStorage.java`
- Constructor now takes the MC `LevelAccessor` it collects for (null only for the placeholder,
  below) and passes it to its `SlowLightCollector`.
- Self-registers in `FlywheelCompat`'s live-storage list on construction, unregisters in
  `delete()`.
- New `isForLevel(LevelAccessor)` (identity compare) for the level-filtered dirty-section path.
- `debugReport()` lines are now prefixed with `[<dimension id>]` via new `describeLevel()`.

### `compat/flywheel/SlowLightCollector.java`
The core fix. The collector resolves the engine for **its own level**, once, in the constructor:

```java
this.engine = level instanceof LevelAttachments att ? att.colorfullighting$getEngine() : null;
```

Safe to resolve eagerly: `LevelMixin.postInit` creates the engine in the `Level` constructor,
long before flywheel can build a `LightStorage` on that level. `collectLightData` samples
`engine.sampleLightColor(...)` instead of the old `getInstance()` call, and bails (leaving the
section zeroed) when `engine == null || !ColoredLightEngine.isEnabled()`. Zeroed data is the
shader-side signal for "fall back to vanilla lighting", so levels without an engine (a flywheel
`LevelAccessor` that is not a `Level`) degrade cleanly.

This is the Immersive Portals fix on the flywheel side: the Nether's `LightStorage` samples the
Nether's engine, the Overworld's samples the Overworld's, and neither can see the other's colors.

### `mixin/compat/flywheel/LightStorageMixin.java`
Resolves the old `// TODO: compat instance probably attaches here..?` — yes, exactly here. Now
implements `ColoredLightStorageHolder` with a `@Unique` per-instance storage created in the
`<init>` hook from `((LightStorage)(Object)this).level()`, deleted in the `delete` hook. All
lifecycle hooks (`collectSection`, `uploadChangedSections`, `endTrackingSection`) act on the
per-instance storage instead of the global. The paired collect/endTracking hooks are also what
keeps our arena's alloc/free sequence identical to flywheel's per `LightStorage` — the index
parity that lets the shaders reuse flywheel's LightLut.

### `mixin/compat/flywheel/InstancedLightMixin.java` and `LightBuffersMixin.java`
`bind()` has no `LightStorage` parameter, but `flush(...)` does on both classes, and each is 1:1
with its engine's `LightStorage`. So `flush` resolves our storage through the duck and stashes it
in a `@Unique` field; `bind()` uses the stash. Flush runs every frame before any draw of that
engine, so the stash is never stale. (`LightBuffersMixin` previously only hooked `bind`; the
`flush` hook is new. `InstancedLightMixin` keeps its upload call in `flush` as before.)

### `compat/flywheel/FlywheelCompat.java`
- Removed the global `flywheelColoredLightStorage` field.
- Added the live-storage registry plus static helpers:
  - `recollectAllTracked()` — engine toggle path, refreshes every level's buffers.
  - `recollectSectionIfTracked(LevelAccessor, long)` — dirty-section path, **filtered by level**
    because section coords overlap across dimensions.
  - `debugReportAll()` — one report line per live storage.
- Added a `hasMethod` probe for `LightStorage#level` next to the existing class probes, so an
  unsupported flywheel still degrades to vanilla-lit rendering instead of crashing.
- The constructor now creates a level-less, never-registered **placeholder storage** whose only
  job is keeping texture unit 10 holding a complete zero-filled buffer texture in the GLSL<430
  fallback mode from init onward (macOS validates sampler bindings at draw time). Previously the
  eagerly-created global storage provided this guarantee incidentally.

### `common/ColoredLightEngine.java` (two call sites only)
- `setEnabled`: `FlywheelCompat.recollectAllTracked()` (all levels).
- `onLightUpdate`: `FlywheelCompat.recollectSectionIfTracked(this.level.getLevel(), dirtySection)`
  (this engine's level only).

### `event/ClientEventListener.java`
`/cl flywheel report` now prints `FlywheelCompat.debugReportAll()` — one line per live storage,
tagged with its dimension id.

Unchanged: `GlCompatMixin`, `GlProgramMixin` (GL-version plumbing, level-independent), all
shaders, the mixin json (no mixins added or removed; `ColoredLightStorageHolder` is a plain
interface).

## Bonus fix

The old global design had a latent bug independent of this branch: whichever `LightStorage` was
constructed last **replaced** the global storage, so with any second level alive (Ponder scene,
IP remote dimension) two levels' alloc/free sequences interleaved in one arena and index parity
silently broke. Per-`LightStorage` storage makes that structurally impossible.

## Fix found by in-game testing (2026-08-02): virtual block getters in getLightColor

First in-game test (creative motor + mechanical bearing): all flywheel objects invisible, and
disabling the flywheel backend crashed. Both had one root cause — **not in the flywheel compat**,
but in `mixin/render/LevelRendererMixin`: its `getLightColor(BlockAndTintGetter, ...)` hook cast
the getter straight to `LevelAttachments`. That cast was fine for real levels and the attachment-
carrying meshing views (`RenderChunkRegion`, sodium's `WorldSlice`), but:

- Flywheel bakes its instance meshes against `EmptyVirtualBlockGetter` on its worker threads.
  The cast threw `ClassCastException`, flywheel's create-visual task died (`Error running task`
  in the log), the visual was never created → every flywheel object invisible. The flywheel
  compat itself never even ran.
- With the backend off, Create renders the same blocks through catnip's `BakedModelBufferer`,
  which uses its own virtual getter — same cast on the render thread, nothing catches it → crash.

Fix: the hook now bails (vanilla lighting) when the getter is not a `LevelAttachments`. Virtual
levels have no engine, and zero colored-light data is already the "fall back to vanilla" signal
everywhere else. `LiquidBlockRendererMixin` needs no change — it routes back through the fixed
hook. This was a latent regression of the no-global refactor itself (the old code sampled the
global engine here without casting), surfaced by flywheel because flywheel is the biggest user
of virtual getters.

## Status and testing

- `gradlew compileJava` passes (only pre-existing warnings).
- In-game: first test crashed on the virtual-getter cast (fixed above, see that section);
  retest pending.
- Suggested tests:
  1. Create contraption with colored lights nearby, on both backends
     (`/cl debug flywheel texture` forces the instancing/buffer-texture path).
  2. A Ponder scene while standing near colored lights (scene objects should be vanilla-lit, the
     world unaffected).
  3. An Immersive Portal with colored light sources on both sides — flywheel objects on each side
     must show only their own dimension's colors.
  4. `/cl flywheel report` with a portal open should list one storage per dimension.
  5. Toggle the engine off/on (`/cl` toggle path) and confirm flywheel objects follow.
