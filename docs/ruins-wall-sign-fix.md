# Conservative Ruins wall-sign correction

Ruins now omits a new vanilla wall sign if its originally valid backing is omitted by the same Ruins instance and the backing is verified absent during this generation pass. This extends the existing per-block skip decision; it does not sweep or delete world blocks, notify neighbours or schedule work.

The guard requires the source sign and backing to be inside the source collection, valid original backing using vanilla wall-sign material rules, consistent transformed attachment, and both positions included by the current generation bounds and predicate. It leaves cases with pending falling blocks alone. The actual backing is read only through `getLoadedChunk(...).getBlockState(...)`; an unavailable chunk is uncertainty, not air. Existing terrain can therefore continue to support the sign even when its source backing is skipped.

Only exact vanilla wall signs are covered. Intentionally unsupported designs, source-boundary support, other sign types, changed attachments and support outside the current pass remain unchanged. In particular, this deliberately leaves some cross-chunk floating signs unresolved. It does not repair already-existing world signs or address the separate torch-before-backing ordering observation. No new setting, default, saved-data field, public API, generation event or lock change was added; Gravity's fall processing is unchanged.

## Random consumption and cost

The guard makes no random draws, performs no full-structure scan and adds at most one loaded-chunk lookup per eligible skip decision. Existing decay calculations are reused; preparation remains terrain-independent. Non-sign blocks exit before any added terrain access.

For an unaffected, terrain-supported sign with erosion/vines/cobwebs enabled, ordered block operations and random draws match the retained original implementation (apart from the added loaded-only observation). Actual corrected layouts can change subsequent decoration random consumption: in the recorded erosion-only fixture, the original makes 892 primitive random draws and the corrected layout makes 900 because the existing decoration algorithm sees air instead of a sign. No promise of identical generated output/random streams is made for corrected layouts.

## Verification

The original reproducer is retained with a frozen pre-fix skip implementation. Before the production fix, the initial ten-test support suite failed its whole-pass omission, same-chunk omission and loaded-observation checks. The completed suite adds boundary, existing-world-sign and effect/random controls.

Java 8 (Zulu), cached dependencies:

```sh
JAVA_HOME=/Library/Java/JavaVirtualMachines/zulu-8.jdk/Contents/Home ./gradlew --offline test --tests '*Ruins*Test'
JAVA_HOME=/Library/Java/JavaVirtualMachines/zulu-8.jdk/Contents/Home ./gradlew --offline test
```

Focused: **20 tests across 2 suites**. Full: **176 tests across 34 suites**. Zero failures, errors or skips. The support tests cover real preparation and generation, all rotations/mirrors, negative coordinates, initial/completion passes, full NBT restoration before and between passes, terrain-backed and intentionally unsupported signs, unavailable backing, generation predicates, source bounds, changed attachment, pending falls, and pre-existing world signs. Unexpected chunk-provider calls fail; only loaded-chunk reads are permitted. The original harness limitations remain: no live chunks, lighting, tile-entity persistence or game-client validation. See `ruins-support-investigation.md` for details.

Logs, failed-before results and current XML results are retained under `build/ruins-wall-sign-fix/` and copied with the unpublished release. No claim is made about Brick's unidentified modded blocks. The previous jar is preserved in `build/releases/2.0.1.2-before-wall-sign-fix/`; its SHA-256 is `b84a961c3b9a6c0ddd13a4fd73cb05ae73c44974b4ac09b54d001f29f70a3080`. No release is published automatically.
