Changelog

Planned
- port to all major Forge and NeoForge versions (1.20.2 through 26.2), Fabric later
- full Sodium support alongside Embeddium
- keep Valkyrien Skies compatibility on every version where VS exists
- add Sable / Create Aeronautics compatibility alongside Valkyrien Skies where possible

2026-08-04
- fixed dark held and dropped items with Flerovium installed
- fixed dark block-breaking cracks overlay with Flerovium installed
- fixed dark particles with AsyncParticles installed

2026-08-03
- fixed crash with the Starlight (Create fix fork) mod installed
- fixed Flywheel rendering and made the Flywheel compat more reliable
- fixed the light propagation thread not shutting down when leaving a world
- added detection of which levels support colored lighting

2026-07-15 to 2026-07-19
- reduced memory usage during chunk light baking
- fixed rendering without Embeddium installed
- removed internal global state for better stability across worlds and dimensions
- split the bundled resource packs
- started groundwork for baseline Sodium support

2026-07-14
- fixed crash when riding boats with the Wakes mod installed
- added support for OpenGL 4.3 and lower (Flywheel compat no longer requires GL 4.5+)
- known issue: beacon enclosed in tinted glass still leaks light at the corners
- known issue: shut doors bleed light around the edges and bottom on the dark side

2026-07-13
- added Valkyrien Skies compatibility: colored lighting works on ships
- added colored dynamic lighting on ships
- fixed Lively Lighting compat: light color crosses between world and ships correctly
- added automatic shader patchers for BSL and Complementary shader packs
- added "/cl off" to load unpatched shader packs

2026-07-09 to 2026-07-12
- sped up the light engine (faster chunk light loading, new config-controlled algorithm)
- fixed a ConcurrentModificationException in the engine
- re-implemented the NBT system for block entity light colors
- updated entity light definitions

2026-07-07 to 2026-07-08
- tweaked light colors

2026-07-04 to 2026-07-05
- added automatic shader pack patching for compatibility
- finished dynamic lighting mod compat (held and dropped item light)
- fixed the shader pack patcher
- fixed lighting inconsistency between Embeddium and vanilla rendering

2026-07-02 to 2026-07-03
- fixed doors and trapdoors: light blocks and blends correctly as they open and close
- fixed registry load times
- fixed Flywheel backend rendering
- fixed resource pack reload on boot
- fixed wrong light values for two blocks
- reduced lag, added a config for light update speed

April to May 2026
- colors wash out more in direct sunlight
- changed the default glowstone color
- fixed stairs lighting
- fixed shading and smoothing
- fixed light attenuation
