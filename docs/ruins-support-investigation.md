# Ruins support investigation — 2026-09-27

This records the pre-fix investigation at `a2780e37`. The original reproduction is retained in the tests; see [the subsequent wall-sign correction](ruins-wall-sign-fix.md) for current behaviour and validation.

## Findings and recommendation

A reproducible gap exists for vanilla wall signs: real Ruins decay can omit the intended backing block while retaining the sign, and the sign can remain floating after RC finishes. In the inspected Minecraft/Forge implementation, torch placement already checks the specific attachment and removes an unsupported torch. A different surviving wall beside either decoration is not a substitute for its actual attachment.

This supports considering a **separate, narrowly scoped Ruins wall-sign cleanup**, not a general unsupported-block sweep or a change to Gravity. A conservative follow-up would require all of the following: the source sign had valid backing; Ruins specifically omits that backing; its world position can be observed without loading a chunk; and the world has no valid backing there. Leave cases with unavailable evidence alone. Limit the initial proposal to wall signs, preserve intentionally unsupported source designs, and do not infer modded support rules. This would deliberately be incomplete cleanup, rather than a guarantee for every decoration.

Do not hold the prepared release for broad cleanup. The reproduction warrants a follow-up, but it does not identify Brick's blocks or prove that this is the cause in their structure. No cleanup implementation was added in this investigation.

## Confirmed code facts

- `GenericStructure.generate` places full blocks before non-full blocks **within each generation pass**, invokes transformer skip decisions, and then invokes AFTER transformers. Normal structure writes use flag 2, which does not request ordinary neighbour notifications.
- Ruins skips source blocks rather than always replacing their world positions with air. Existing terrain can remain at an omitted support position.
- With Gravity, Erosion, Vine Growth and Cobweb Growth disabled, Ruins performs no final attachment validation. Its decay decision independently considers a block's position and material.
- `BlockTorch.onBlockAdded` invokes its attachment check. That check uses the torch state's facing, not merely whether some neighbouring wall exists.
- `BlockWallSign` has no equivalent support validation on addition. Its `neighborChanged` checks the material of the block behind the sign's facing and removes the sign when that backing is not solid.
- `Chunk.setBlockState` normally calls `onBlockAdded` when the block type changes. Forge snapshot capture can suppress it for non-tile-entity blocks; the reproduction models the ordinary non-capturing path.
- `RCBlockLogic.canStay` only delegates placement checks for snow layers and `BlockBush` subclasses. It is not a general attachment validator.

## Reproduction and controls

The fixture is a 5×5×5 source collection: decoration at `(2,2,2)`, plank backing at `(1,2,2)`, both decorations facing EAST. The optional alternative plank wall is at `(2,2,1)`. Other source positions are air. Translation places the actual support and decoration on opposite sides of the negative-coordinate chunk boundary at -16. Rotations and mirrors transform both blocks and their states through the real generation code.

Both decay cases use direction DOWN, minimum and maximum decay 0.15, Chaos 0.6, Density 0.1, Gravity false, Erosion/Vine Growth/Cobweb Growth zero. The source positions have directional stability 0.5. These seeds were found with real `GenericStructure.prepareInstanceData` and real Ruins skip decisions; no skip result or decay field was mocked. Raw decay below is before the material exponent.

| Decoration | Java Random seed | Raw backing decay | Raw decoration decay | Raw alternative decay | Final result with omitted backing |
| --- | ---: | ---: | ---: | ---: | --- |
| Wall sign | 289 | 0.11585713041991962 | 0.05031565544976194 | 0.06055921145568349 | Sign remains unsupported |
| Torch | 409 | 0.14006637733419783 | 0.023327585545520432 | 0.05122650230927747 | Placement callback removes torch |

All three sampled raw values are positive; these cases do not depend on fractional powers of negative decay values.

Controls established:

- With intact backing and zero decay, both blocks survive a whole-structure pass or backing-first chunk completion.
- With intact backing but the decoration chunk generated first, the torch removes itself before its backing arrives and is not placed again by the backing chunk's pass. The wall sign temporarily floats and becomes supported when completion places the backing. This separate ordering observation is not evidence that Brick reported missing torches.
- Originally unsupported wall signs also remain until a neighbour notification. Consequently, an unsupported sign alone does not prove Ruins removed its backing.
- An alternative wall does not prevent torch removal or make the wall sign's neighbour check accept invalid backing.
- If valid backing already exists in the world, omitting the source backing leaves it in place and both decorations remain supported. A cleanup rule based solely on the source skip result would wrongly remove them.
- Invoking the real wall-sign neighbour callback removes an unsupported sign and keeps a correctly supported one.

The removed-support and intact-support matrices each cover 96 combinations: two block types, four rotations, two mirror states, three pass orders (single pass, backing first, decoration first), and prepared versus restored data. Restoration uses full structure NBT before generation and again between completion passes. Ordered writes, callback invocations, support reads and intermediate/final states are recorded in the fixture trace. Representative traces are preserved in `build/ruins-support-investigation/reproduction-output.txt`.

## Harness limits

The test executes real `GenericStructure.prepareInstanceData`, `generate`, `loadInstanceData`, Ruins decisions, state transformations, and vanilla attachment callbacks. The map-backed mocked server world models changed-block addition callbacks and flag-1 neighbour notifications. Unexpected world calls, including chunk requests, fail the test.

Real chunk storage, lighting/heightmap maintenance, block ticks, observer updates, item entities, arbitrary Forge events and sign text/tile-entity persistence are not simulated. Heightmap freeze/melt are bypassed. Item-drop/harvest processing is suppressed using `restoringBlockSnapshots`, which is separate from snapshot capture. All source blocks are allowed by a fixture safety registry. Plain-JUnit obfuscation detection is bypassed for the cached event-queue field lookup; registry/event-handler globals and all accessor cache fields are restored afterward. These are controlled callback-level reproductions, not a launched game or proof about every modpack environment. No claim is made that RC's complete live generation path never accesses chunks.

No new production RNG calls or chunk requests were introduced: production code is unchanged. The tests do not claim to reproduce arbitrary modded decorations, identify a Volts regression, or justify a blanket neighbour-update change.

## Verification and inspected revisions

- RC production revision: `ba32f4b2312e4bc1d9554f70e34ec735e3c35a27` (the existing uncommitted 2.0.1.2 version edits remain).
- Minecraft 1.12.2 / Forge 14.23.5.2859, mappings `snapshot_20171003-1.12`. Inspected mapped binary SHA-256: `0d9d9134f6d5995d17746e867776d73414472bb6db7acf0ea0dc0702848297dd`. `javap -c -p` outputs for BlockTorch, BlockWallSign, BlockSign, Block, World and Chunk are retained under `build/ruins-support-investigation/`. The cached Forge BlockTorch patch was also checked; it changes the top-support hook, not the wall-attachment path characterized here.
- Java 8 (Zulu), cached dependencies: `./gradlew --offline test --tests '*Ruins*Test'` passed **12 tests across 2 suites** (6 new support characterizations plus 6 existing decoration tests).
- `./gradlew --offline test` passed **168 tests across 34 suites**, with zero failures, errors or skips. Logs and XML results are under `build/ruins-support-investigation/`.
- `git diff --check` passed. No game client was launched and no tester work is needed for this reproduction.
- No production change, release rebuild or publication. The prepared 2.0.1.2 jar remains SHA-256 `b84a961c3b9a6c0ddd13a4fd73cb05ae73c44974b4ac09b54d001f29f70a3080`.

## Saved prepared instances

These records are emitted by the real preparation path and round-trip through the structure loader. They contain the original prepared noise fields, not replacement test-generated fields.

Wall sign, seed 289:

```snbt
{variables:{},transformer:{transformers:[{data:{baseDecay:0.15000000596046448d,field:{size:[I;5,1,5],offset:[I;0,0,0],values:[{pos:[I;0,0,3],value:0.28294704226234746d},{pos:[I;2,0,2],value:-0.13584874028716293d},{pos:[I;4,0,0],value:0.670276553436551d}]},volumeField:{size:[I;5,5,5],offset:[I;0,0,0],values:[{pos:[I;2,4,2],value:0.03577351936204157d},{pos:[I;2,0,4],value:0.0450713069367885d},{pos:[I;0,0,4],value:-0.01606137032985016d}]},fallingBlocks:[]},id:"ruins"}],deactivated:0b},tileEntities:{},foreignTransformer:{transformers:[],deactivated:0b},allowedGTECoords:[]}
```

Torch, seed 409:

```snbt
{variables:{},transformer:{transformers:[{data:{baseDecay:0.15000000596046448d,field:{size:[I;5,1,5],offset:[I;0,0,0],values:[{pos:[I;2,0,3],value:-0.02210555196253055d},{pos:[I;0,0,1],value:0.06657109611214612d}]},volumeField:{size:[I;5,5,5],offset:[I;0,0,0],values:[{pos:[I;3,3,0],value:-0.10332209694249445d},{pos:[I;1,1,4],value:0.04581535051086682d},{pos:[I;2,4,1],value:-0.17981391384301118d}]},fallingBlocks:[]},id:"ruins"}],deactivated:0b},tileEntities:{},foreignTransformer:{transformers:[],deactivated:0b},allowedGTECoords:[]}
```
