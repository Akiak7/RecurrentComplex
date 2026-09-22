**Diagnostic log reference and validation**

| Log record | What it establishes |
| --- | --- |
| `BEGIN` / `PLANNED` / `PASS` | Correlation `trace`, structure/generation ID, actual caller stack, Y, full and clipped bounds, seed and stage. `RCForgeEventHandler.onPreChunkDecoration` identifies the native population caller; `RecurrentHandler$RCHandler` identifies RLTweaker's replacement. Script/decoration callers remain visible. A completion BEGIN also carries the saved entry UUID. |
| `RESULT` / `WRITE_RESULT` / `COMPLETION_SKIP` | Success, caught failure or propagated exception; explicit prevent-completion, missing-structure or missing-instance-data skips. Generation success does not prove blocks survived later population. |
| `ENTRY_ADD` / `ENTRY_REMOVE` / `ENTRY_RESET` / `ENTRY_LOADED` / `DATA_LOAD_*` / `CHUNK_CHECK` | Saved UUID, bounds, first-time/prevent-completion flags, instance-data presence, load tag presence and checked history. Caller stacks distinguish RLTweaker cleanup from commands or ordinary loads. `ENTRY_RESET` means a read is replacing the in-memory entry; it is not alone proof the entry stays absent—check the subsequent load records. |
| `SAMPLE` / `PLACER` / `PROBE_EXCLUDED` | Existing evaluated ray/match positions and outcomes, baseline/preset, average offsets/spread decisions and ray results. Sampling is not repeated. Candidate hits are observations, not a guarantee of final support. |
| `SUPPORT` | The **same fixed positions** for a `watch` before the main RC write, immediately after it, and at population-end for its own/relevant neighboring chunks. Each record reads only an already-loaded chunk and a fixed Y±4 window. `UNAVAILABLE` means unknown, not air. Fallback points are explicitly labeled when no ray hits were captured. |

There are at most 64 active watches, five positions per watch, 16 relevant population-end observations per watch, and 16 detailed sample records plus 16 placer-detail records per generation attempt. Neighbor observations cover population chunks within one physical chunk of a watched column. Watches end on eviction, expiry or world unload, with explicit markers. The total record cap is per JVM (maximum 50,000); `TRUNCATED` stops further diagnostic terrain reads. If coverage ends before the symptom, repeat from the untouched snapshot with a narrower area or exact structure filter. Absence after a coverage marker is not evidence of absence in the game.

Compare the first differing generation/entry record in A/B, then inspect support positions. Ground present immediately after RC but changed by population-end suggests later population changed it; this build does **not** attribute the writer to OTG or another mod. A changed Y window also includes legitimate RC writes, so distinguish `before-write` from `after-write`. Reads do not provide chunks, scan unloaded terrain, consume worldgen randomness, rerun placement, cancel events or change generation timing/locks. Logging has timing overhead. Unit tests verify safeguards; only these pack runs can establish the reported behavior.


Validation used Zulu Java 8 and the existing offline Gradle 5.6.4 / Forge 14.23.5.2859 development environment. Final commands:

```sh
JAVA_HOME=/Library/Java/JavaVirtualMachines/zulu-8.jdk/Contents/Home ./gradlew --offline test --tests '*RCGenerationDiagnosticsTest' --tests '*SelectivePlacerTest' --tests '*WorldStructureGenerationDataCompletionTest'
JAVA_HOME=/Library/Java/JavaVirtualMachines/zulu-8.jdk/Contents/Home ./gradlew --offline test shadowJar
```

The focused run passed 20 tests (13 diagnostics, 3 placement, 4 completion data). The normal unfiltered `test` task passed 126 tests in 25 suites, with zero failures, errors or skips. `shadowJar` and its `reobfShadowJar` finalizer succeeded. The resulting jar contains the shaded MCOpts classes and diagnostic hooks, with no test fixtures. `git diff --check` passed. XML results are preserved in the local build output.

The diagnostic tests cover default-OFF behavior, invalid/filter settings, nested scope isolation, already-loaded-only observations, immutable support positions through immediate/neighbor observations, watch/record caps, logging failures, unchanged ray results/predicate calls/random state, one placement invocation, reflective caller visibility, saved-data reset/removal attribution, initial and direct pre-completion passes, and failures/skip records. The reflective caller fixture is a test stub, not an execution of RLTweaker. No Minecraft client/server, Dregora installation, OTG world, or valuable save was run or changed.
