# Ruins cobweb-only correction

The AFTER decoration pass was gated by `blockErosion > 0 || vineGrowth > 0`, although cobwebs are placed inside that pass. With both zero, positive Cobweb Growth had no effect. The gate now also includes `cobwebGrowth > 0`. The placement algorithm, traversal, exclusions, clipping, defaults and serialization are unchanged.

Cobweb-only configurations now consume the existing algorithm's random draws and may therefore affect subsequent random outcomes. Configurations that already ran the pass retain their ordered operations and random consumption. This does not add support-aware decoration removal, change Gravity, or infer modded erosion mappings.

The existing English Chaos, Density, Erosion and Gravity tooltips were clarified without adding or renaming translation keys. No in-game visual verification was performed.

## Tests

Java 8 (Zulu), existing cached dependencies:

```sh
JAVA_HOME=/Library/Java/JavaVirtualMachines/zulu-8.jdk/Contents/Home ./gradlew --offline test --tests '*RuinsDecorationTest'
JAVA_HOME=/Library/Java/JavaVirtualMachines/zulu-8.jdk/Contents/Home ./gradlew --offline test
```

Before the fix, the six-test focused suite had three failures: eligible cobweb-only corner placement, cobweb-only processing in open air, and clipped initial/completion placement. After the fix all six passed. The full suite passed 162 tests across 33 suites, with zero failures, errors or skips.

The tests use the real transform entry point, real cobweb placement and decay exclusions. They cover an eligible corner, unsuitable open air, all effects disabled, fully decayed positions, and initial/completion clipping. Three erosion/vine configurations across four rotations and both mirror states compare cumulative ordered reads, skip checks, writes and random draws against the frozen pre-fix decoration dispatch. That reference shares the unchanged decayBlock implementation; it is a dispatch regression test, not an independent reimplementation of erosion. Gravity is disabled in these fixtures. The mocked world rejects unexpected methods, including chunk requests, and the global event handler is restored after every test.

The baseline failed run, focused results, full results and build logs are preserved with the local release. Timing, world generation compatibility and tooltip appearance were not tested in-game.
