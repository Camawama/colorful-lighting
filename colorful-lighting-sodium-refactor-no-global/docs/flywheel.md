# How the Flywheel compat works (and how it is per-level)

This is the explanation of the flywheel integration, written for anyone taking over or reviewing
that code. It also answers the blocker that stalled the flywheel side of the no-global refactor:
**"I don't see a way to acquire a level instance in the flywheel code."** Short version: Flywheel
is per-level all the way down, and the exact class we mixin into — `LightStorage` — holds the
level and exposes it through a public `level()` method. The compat now resolves each level's
`ColoredLightEngine` through `LevelAttachments`, the same way the rest of this branch does.

Class names below are from Flywheel 1.0.x (`dev.engine_room.flywheel`), verified against the
1.0.5 jar we compile against.

## 1. Where Flywheel connects to the game

Flywheel looks disconnected because none of its rendering is called from mod-facing API — it wires
itself in with its own mixins (package `dev.engine_room.flywheel.impl.mixin`):

- **`LevelRendererMixin`** — the main hook. Injects into `LevelRenderer.renderLevel` to begin/end
  the flywheel frame, dispatch rendering at the right stages, and cancel vanilla rendering of any
  entity/block-entity that flywheel has taken over (`flywheel$decideNotToRenderEntity`).
- **`ClientChunkCacheMixin`** — injects into the client's light-update handling and forwards
  `(LightLayer, SectionPos)` to flywheel. This is where vanilla light changes enter flywheel.
- **`BlockEntityTypeMixin` / `EntityTypeMixin`** — attach visualizers to types.
- **`LevelMixin` / `MinecraftMixin`** — entity iteration and resource-reload plumbing.

From those entry points everything is instantiated **per level**:

```
Level (any LevelAccessor: ClientLevel, Ponder's fake level, VS shipworlds...)
 └─ VisualizationManagerImpl        ← stored in a LevelAttached<> map, get(LevelAccessor)
     └─ Engine (EngineImpl)         ← created by the active Backend (indirect or instancing)
         ├─ DrawManager             ← indirect (GL 4.6) or instancing (older GL)
         └─ LightStorage(level)     ← THE class we hook; holds the level
```

`VisualizationManagerImpl.get(level)` is how anything resolves "the flywheel state for this level".
A `VisualizationManager` exists for a level only if `supportsVisualization(level)` — this is why
Ponder scenes get their own separate manager, engine, and `LightStorage`.

## 2. Flywheel's own lighting pipeline

Flywheel-rendered objects don't use vanilla's per-vertex lightmap coords sampled at mesh time —
instances move without remeshing, so light has to be sampled on the GPU. `LightStorage` is the
machine that makes that possible:

1. Visuals (Create contraption parts etc.) tell the engine which chunk sections they occupy
   (`EngineImpl.lightSections`). `LightStorage` tracks those sections.
2. For each tracked section, `collectSection(long sectionPos)` reads vanilla block+sky light for an
   **18×18×18** volume (16³ plus a 1-block border for interpolation at section boundaries) via
   `level.getBrightness(...)`, and packs it into a slot of a `CpuArena` — a pool of equally-sized
   CPU-side buffers where each tracked section owns one slot (an integer index).
3. Light updates arriving from `ClientChunkCacheMixin` mark sections for re-collection in the next
   frame plan.
4. The arena is mirrored to the GPU, differently per backend:
   - **indirect** backend: `LightBuffers` — SSBOs filled through a `StagingBuffer`.
   - **instancing** backend: `InstancedLight` — buffer textures (SSBOs may not exist pre-GL 4.3).
5. Shaders can't index by section coordinate directly, so flywheel builds a **LightLut**: a
   3-level lookup table (y→z→x) from section coordinate to arena index. GLSL-side this is
   `_flw_chunkCoordToSectionIndex(...)` in `flywheel:internal/light_lut.glsl`, used by
   `flw_lightFetch`/`flw_light`.

## 3. How Colorful Lighting piggybacks on it

The whole design is: **run a second, parallel arena with the same section→index layout, filled
with our colored light instead of vanilla light, and let the shaders reuse flywheel's own LUT to
index it.** We never build our own LUT, never track sections ourselves, never touch visuals.

Java side (`compat/flywheel`, mixins in `mixin/compat/flywheel`):

- **`LightStorageMixin`** gives every `LightStorage` its own `ColoredLightFlywheelStorage`
  (a `@Unique` field, exposed through the `ColoredLightStorageHolder` duck interface) and mirrors
  the lifecycle into it:
  - `<init>` → create our storage **with the level from `LightStorage#level()`**; `delete` →
    delete it (which also unregisters it from `FlywheelCompat`'s live-storage list).
  - `collectSection(long)` TAIL → we collect the same section into our own `CpuArena`
    (same slot layout: `SlowLightCollector` samples that level's engine per block over the same
    18³ volume, packs RGB8 + magic alpha nibble into one int per block).
  - `endTrackingSection(long)` TAIL → free our slot.
  - Because our arena sees the same alloc/free sequence keyed by the same section longs, **our
    arena indices coincide with flywheel's** — that index parity is what lets the shader use
    flywheel's LUT for our buffer. It's the load-bearing trick of the whole integration, and it
    only holds per-`LightStorage`: sharing one storage across levels would interleave two levels'
    alloc/free sequences and silently break parity (that was a real latent bug of the old global
    storage whenever a second level — a Ponder scene, an Immersive Portals remote dimension —
    had a live flywheel engine).
  - `uploadChangedSections(StagingBuffer, ...)` TAIL → enqueue our dirty slots into our own SSBO
    (binding 8) using flywheel's staging machinery (indirect backend path).
- **`InstancedLightMixin`** — the instancing backend never touches `LightBuffers`/`StagingBuffer`,
  so `InstancedLight.flush/bind` get parallel hooks: `uploadChangedSectionsDirect()` (plain
  `glBufferSubData`, or `GlBuffer` uploads in texture-fallback mode) and `bindBuffers()`.
  `flush(LightStorage)` resolves our storage through the duck interface and stashes it for
  `bind()`, which has no `LightStorage` parameter — sound because an `InstancedLight` is 1:1 with
  its engine's `LightStorage` and flush runs every frame before any draw.
- **`LightBuffersMixin`** — same stash-on-flush pattern for the indirect backend:
  `flush(StagingBuffer, LightStorage)` stashes, `bind()` binds our SSBO alongside flywheel's
  light buffers.
- **`GlCompatMixin` / `GlProgramMixin`** — GL-version plumbing, level-independent. Flywheel stamps
  `GlCompat.MAX_GLSL_VERSION` as the `#version` of every shader it compiles; below GLSL 430 an SSBO
  is illegal, so `FlywheelCompat.init` flips to a **buffer-texture fallback** (same bytes, unit 10,
  `texelFetch`) and `GlProgramMixin` points the `_cl_coloredLightSections` sampler at unit 10 on
  each program's first bind. `colored_light.glsl` makes the identical choice with
  `#if __VERSION__ >= 430`, so Java and GLSL stay in lockstep by construction.

Shader side: flywheel loads its shaders through the resource manager, so the built-in
`colorful_lighting_core_shaders` resource pack simply **overrides flywheel's own shader assets**
(`assets/flywheel/flywheel/...`). The overridden instance shaders (`transformed.vert`,
`oriented.vert`, Create's `rotating.vert`/`scrolling.vert`) call `vertexLightColor(...)`, which:

1. maps worldpos → section index via flywheel's `_flw_chunkCoordToSectionIndex` (their LUT!),
2. fetches our packed int from our SSBO / buffer texture at `sectionIndex * 18³ + localOffset`,
3. checks the magic alpha nibble `0xF`: if present, the entry is colored light and gets mixed
   against the lightmap texture; if the entry is 0 (engine disabled or absent, section unknown,
   fake level), the shader falls back to the instance's vanilla lightmap coords. Zero-filled data
   therefore degrades to exactly vanilla behavior — that's the safety net for every "we don't
   have data" case.

## 4. How the per-level wiring works on this branch

The blocker was "the flywheel code has no way to get a level." It does — `LightStorage#level()`
(see the table in §3's intro; every hook either is a `LightStorage` or receives one). From there
the compat plugs straight into this branch's per-level architecture:

- **`LevelMixin` gives every `Level` its engine** at construction
  (`ColoredLightEngine.create(level, ...)`, exposed via
  `LevelAttachments.colorfullighting$getEngine()`). That happens in the `Level` constructor —
  long before flywheel can build a `LightStorage` for that level — so `SlowLightCollector`
  resolves the engine once, in its constructor:

  ```java
  this.engine = level instanceof LevelAttachments att ? att.colorfullighting$getEngine() : null;
  ```

  A flywheel `LevelAccessor` that is not a `Level` has no attachments and therefore no engine;
  the collector then leaves sections zeroed and the shaders show vanilla light. This is the whole
  Immersive Portals fix on the flywheel side: the Nether's `LightStorage` samples the Nether's
  engine, the Overworld's samples the Overworld's, and neither can see the other's colors.

- **The dirty-section refresh is level-filtered.** `ColoredLightEngine.onLightUpdate` calls
  `FlywheelCompat.recollectSectionIfTracked(level, section)`, and only storages whose level
  matches recollect. Section coordinates overlap across dimensions, so an unfiltered refresh
  would recollect the other dimension's storage for a change that never happened there.

- **The global refresh paths iterate all live storages.** `FlywheelCompat` keeps a list of live
  storages (self-registered in the constructor, removed on delete). The enable/disable toggle
  recollects them all — each one samples its own engine — and `/cl flywheel report` prints one
  line per storage tagged with its dimension id, which is the quickest way to verify the
  per-level state in-game with a portal open.

- A level-less placeholder storage (created once in `FlywheelCompat`) keeps texture unit 10
  holding a complete zero-filled buffer texture in fallback mode from init onward — macOS
  validates sampler bindings at draw time; see the comment on it.

## 5. Version notes

Supported range is flywheel `1.0.0-beta-214` – `1.0.8` (see `ColorfulLightingMixinPlugin` and the
class probe in `FlywheelCompat.init`). Everything referenced here (`LightStorage`, `CpuArena`,
`StagingBuffer`, `GlCompat`) is `flywheel.backend`/`flywheel.impl` internals, not API — which is
why `FlywheelCompat.init` probes for the classes — and now also for the `LightStorage#level`
method — and degrades to vanilla-lit flywheel rendering on unknown versions instead of crashing.
When bumping the supported range, note that `LightBuffers.flush(StagingBuffer, LightStorage)` and
`InstancedLight.flush(LightStorage)` are mixin targets, so a signature change there fails mixin
application rather than the probe.
