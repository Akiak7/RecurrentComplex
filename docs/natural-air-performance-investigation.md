# Natural Air performance investigation — 27 September 2026

The captured Dregora profile identifies Natural Air as the largest server-thread hotspot during the reported 42.8-second tick. The matching diagnostic archive lost the relevant structure context to retention limits. This investigation therefore reproduces specific work locally, not the original stall or its unknown structure.

## Child-layout correction and workload

`ChildNaturalAirWorkloadTest` exercises the real generator binding/dispatch with the old `load(generator)` call and the corrected `register(generator)` call. It uses controlled child layouts and the real Natural Air AFTER phase over an empty mock world, with one initial and two completion passes. Preparation is stubbed to select known layouts; these are operation counts, not preparation-cost benchmarks.

| Input, identical one-block cloud | Old call | Corrected call |
| --- | --- | --- |
| Already prepared layout | 3 preparations, 0 loads | 0 preparations, 0 loads |
| Restored layout | 2 preparations, 2 loads | 0 preparations, 3 loads |
| Natural Air AFTER invocations, either input | 3 | 3 |
| Terrain reads, either input | 372 | 372 |

The corrected saved-data path deserializes the same saved layout each pass. It does not currently cache the decoded object in the wrapper. That is a real workload difference; replacing preparation with loading is not a measured whole-game performance guarantee.

Controlled rerolls between one-block and eight-block clouds demonstrate both directions: keeping an eight-block prepared layout yields 624 reads instead of the old rerolled layout's 372; keeping a one-block layout yields 372 instead of 624. Restored equivalents have old read counts of 456 or 540, versus corrected counts of 624 or 372. Both paths still invoke AFTER three times. Thus the fix can change work by preserving the intended selection, without intrinsically adding generation passes for a fixed child. Nested real layouts may contain different numbers of children; this small harness does not model every tower script or establish which layout the profiled run used.

## Narrow optimization retained

The old algorithm adds every candidate to a reused `HashSet`, traverses it, then clears it. After a foliage search grows that set, its backing table remains large. Iterating and clearing a later singleton scans that table even when its block cannot expand the search.

Natural Air now evaluates the first block directly. If it does not expand, it skips the singleton set bookkeeping. If it expands, the implementation primes/clears the reused table as before and follows the same alternating wave sets, insertion order and early exits. It deliberately retains capacity: shrinking the set could change iteration order. The existing general cloud traversal remains unchanged.

Candidate collection, block callbacks, support checks, traversal cap, neighbour enumeration, generation clipping, and removal decisions are unchanged. No terrain result is cached between passes. No extra random call, chunk request, setting, public API, dependency or runtime tracing facility was added.

A loop-based candidate collector was also measured but not adopted: gains were inconsistent and small cases regressed. Its experimental implementation remains only in the manual benchmark, never in the release jar.

## Measurements

Local ARM64 OpenJDK runtimes: Zulu Java 8 `1.8.0_302` and Zulu Java 25 `25.0.2` (not the tester's exact `25.0.4.1`). Each benchmark uses five warmups, fifteen alternating-order measurement pairs and median elapsed time, with a 1 GiB heap. Diagnostics are not initialized. Allocation figures are current-thread allocated bytes from the JVM, sampled in a separate extra run after timing; they are not retained heap or GC pause measurements.

For 20,000 terminal roots after growing the reused set to 300 elements, Java 8 measured 3.548 ms before versus 0.162 ms after; Java 25 measured 5.813 versus 0.043 ms. The extreme 8,192-element case measured 99.535 versus 0.069 ms on Java 8. These isolated microbenchmarks show the redundant table cost, not a speedup prediction for Minecraft.

The combined model includes the **unchanged** collection algorithm and three partial passes. Dense clouds include every position in a cube; sparse clouds include positions where `(x+y+z)%5 == 0`. The foliage case uses a deterministic 5×12×5 patch of 300 foliage positions. Writes are modeled outside the active clip, so terrain stays unchanged between passes. This stresses repeated traversal; it is not a full Minecraft world.

| Cloud and terrain | Java 8 before → after, ms | Java 25 before → after, ms |
| --- | --- | --- |
| Width 4, dense, empty | 0.979 → 0.925 | 0.927 → 0.855 |
| Width 4, dense, foliage | 17.194 → 16.850 | 21.662 → 20.998 |
| Width 4, sparse, empty | 0.146 → 0.111 | 0.234 → 0.190 |
| Width 4, sparse, foliage | 12.731 → 12.526 | 17.015 → 16.384 |
| Width 24, dense, empty | 62.067 → 61.088 | 78.049 → 76.997 |
| Width 24, dense, foliage | 98.344 → 85.659 | 134.166 → 107.616 |
| Width 24, sparse, empty | 26.377 → 24.496 | 40.598 → 37.750 |
| Width 24, sparse, foliage | 95.989 → 63.862 | 149.186 → 84.943 |

The large dense/sparse foliage cases are about 13%/33% faster on Java 8 and 20%/43% faster on Java 25 in this run. Small foliage cases improve only about 2–4%, within the range where timing noise matters. This is evidence for a targeted improvement, not elimination of the captured stall.

For the large sparse foliage model, all three passes together produce 57,384 candidate visits at the outer level, 505,755 traversal callbacks and 82,800 scheduled removals before clipping, identically before/after, with an identical ordered-position checksum. Java 8 allocations decrease from 170,184,144 to 166,061,328 bytes; Java 25 from 143,387,256 to 139,723,512. Most allocation and search work remains.

## Verification and reproduction

The behavior tests compare complete ordered terrain reads, foliage/wood/leaves/replaceability/support callbacks, attempted writes and accepted clipped writes against the original AFTER algorithm. Cases cover empty terrain, obstacles and early exits, supported/unsupported foliage, unsupported plants, 299/300/301 foliage blocks, all eight rotations/mirrors at negative coordinates, initial/completion passes, and serialized clouds. Mock worlds fail on unexpected chunk/world access; random mocks must remain untouched. The separate traversal test compares 180 sequences of reused sets, including large retained capacities and partial-wave exits. No timing threshold is used as a unit-test assertion.

Focused command (44 tests, 9 suites, no failures/errors/skips):

```sh
JAVA_HOME=/Library/Java/JavaVirtualMachines/zulu-8.jdk/Contents/Home ./gradlew --offline test --tests '*NaturalAir*Test' --tests '*WorldScriptStructureGenerator*Test' --tests '*RCGenerationDiagnostic*Test' --tests '*WorldStructureGenerationDataCompletionTest' --tests '*SelectivePlacerTest'
```

Manual benchmarks (Gradle itself runs under Java 8):

```sh
JAVA_HOME=/Library/Java/JavaVirtualMachines/zulu-8.jdk/Contents/Home ./gradlew --offline -I tools/benchmark-natural-air.gradle naturalAirBenchmark
JAVA_HOME=/Library/Java/JavaVirtualMachines/zulu-8.jdk/Contents/Home ./gradlew --offline -I tools/benchmark-natural-air.gradle -PbenchmarkJava=/Library/Java/JavaVirtualMachines/zulu-25.jdk/Contents/Home/bin/java naturalAirBenchmark
```

Raw benchmark outputs and focused test XML are preserved in `build/lag-investigation/`. Earlier fixture-only compilation/initialization failures are also retained there; they were corrected before the successful focused run. The old release jar and its checksum remain in `build/releases/2.0.1.2-before-lag-work/`.

The full available Java 8 suite (`./gradlew --offline test`) passed **148 tests across 31 suites**, with no failures, errors or skips. `git diff --check` also passed. These tests include the existing wizard-placement replay; they do not rerun the separate original-jar comparison or launch Minecraft/OTG.

The child fix remains in place. The optimization does not alter gameplay defaults, cleanup, partial-generation mode, event timing or locking. The remaining large scans, foliage searches and saved-layout decoding may still be expensive. No OTG run or in-game confirmation of a stall fix was performed, and no release was published.
