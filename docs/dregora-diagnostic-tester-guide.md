**Landon — diagnostic A/B test, Minecraft 1.12.2**

Use the bundled `RecurrentComplexVolts-1.12.2-2.0.1.1-dregora-diag1.jar`. This is a diagnostic build, not a Dregora fix or release. It still reports version 2.0.1.1; `BUILD.txt` identifies its exact source and SHA-256. Diagnostics default OFF. The jar does not change RLTweaker settings.

1. Make two disposable copies of the same complete installation, A and B. Replace each copy's existing RC jar with the test jar; keep only one RC/Volts jar in `mods`. For a dedicated server, install it and enable tracing on the server; for single-player, use the launcher instance's JVM arguments.
2. Use identical **complete offline snapshots from before the target region generated**, or two fresh worlds with the same seed/preset and route. Keep an untouched starting copy. Never copy a running save, reuse A's generated result as B's start, or delete region/structure-data files from valuable worlds.
3. Keep all other mods, configs, structure files, view distance, pregeneration settings and traversal order constant. Choose one short route through the affected structure area, beyond the supplied 500-block spawn exclusion.
4. In both `reccomplex.cfg` files, keep `B:avoidPlacerChunkGeneration=false` and `I:rayAverageMaxHeightSpread=7`. The separate coordinate fix (`7379fd31`) is included but inactive with avoidance false. To repeat the earlier spread-zero experiment, use **0 in both runs**, labeled as a separate pair. Never compare A=7 with B=0.

Set these values inside `config/rltweaker.cfg`'s `"recurrent complex"` section in the disposable copies:

| Setting | A | B |
| --- | --- | --- |
| `B:ENABLED` | `false` | `true` |
| `B:"Manage Recurrent Complex Events"` | `true` | `true` |
| `B:"Generate Structures Partially"` | `true` | `true` |
| `B:"Clean Structure Data"` | `false` | `false` |
| `I:"Clean Structure Threshold"` | `100` | `100` |

**Fully exit Minecraft/stop the server before changing settings; use a new JVM for each run.** Returning to the title screen is insufficient. Both runs should use native Volts generation, with cleanup OFF.

Add these JVM arguments to both launches, replacing the example area with the same target rectangle in A and B:

```text
-Dreccomplex.trace=true
-Dreccomplex.trace.dimension=0
-Dreccomplex.trace.area=80,80,88,88
-Dreccomplex.trace.maxRecords=20000
```

Area order is `minChunkX,minChunkZ,maxChunkX,maxChunkZ`, inclusive, at most 64×64 chunks. Convert block coordinates with floor(block/16), including negatives. The example covers X/Z blocks 1280–1423; it is **not** a known reproduction location. Include the footprint and a small margin. Optionally add `-Dreccomplex.trace.structure=ExactStructureID` once known; the filter is exact/case-sensitive. Remove trace arguments to turn diagnostics off. Invalid/missing areas log `CONFIG_ERROR` and disable tracing.

Check `logs/latest.log` for `[RC-DIAG] SESSION`, `MOD` and `SETTINGS`. These record relevant loaded versions/jar hashes and effective simple RC/RLTweaker settings. Follow the same route and let surrounding chunks populate. Confirm `BEGIN` records exist for the target: `RCForgeEventHandler.onPreChunkDecoration` in the caller identifies native population; `RecurrentHandler$RCHandler` identifies RLTweaker's replacement.

Capture coordinates/screenshots of each cutoff or floating tower. Stop each process and preserve its complete `latest.log` and `debug.log` if present **before relaunching**. Return separately labeled A/B logs, both configs, mod list, seed/dimension, route/coordinates, snapshot identity and `BUILD.txt`. Logs include local mod paths.

Compare `PLANNED`/`PASS`/`RESULT`, saved `ENTRY_*` records and `SUPPORT` records with the same watch ID. Support is observed before/after RC writes and after relevant population, using only already-loaded chunks. `UNAVAILABLE` is unknown, not air. Later changes do not identify their writer as OTG. Bounds fallback and candidate ray hits are labeled.

Limits are 64 watches, five fixed positions each (Y±4), 16 relevant population-end observations per watch, and bounded sample/log records. `WATCH_EVICTED`, `WATCH_EXPIRED`, `WATCH_UNLOAD` and `TRUNCATED` mark lost/ended coverage. If reached too early, restart from the untouched snapshot with a narrower filter. Do not interpret absent later logs as proof that nothing happened.

The [log reference and validation notes](dregora-diagnostic-notes.md) explain the record types and exact tests. All 20 focused and 126 normal tests passed; no live Dregora world has been tested. Logging adds timing overhead, but does not provide chunks, rerun placement, consume worldgen randomness or alter generation decisions/events/locks.
