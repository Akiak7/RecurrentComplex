# MegaBattleTower diagnostic return — 27 September 2026

The return contains different floor-generation requests for the same tower slots on different chunk passes. A demonstrated child-layout reuse bug provides a mechanism for that mismatch. A minimal local correction and regression tests are included. This is not yet an in-game confirmation of a Dregora fix, nor an explanation of the separately reported floating towers.

## Input and effective environment

Tester observation: “Have one run here that has a MegaBattleTower with overlapping placement per chunk.” The supplied screenshot shows mismatched walls/floors; it does not by itself distinguish overlapping whole towers from inconsistent assembly of one tower.

- Archive: `RC-diagnostic-2026-09-27_12.32.00.406-0.zip`, SHA-256 `9f1d87ecb82c55e6cf1948b9a1c113a7c7fa5921e3f7a9d048e3ae29aec51e07`.
- RC: diagnostic build 2, source `8ec5acc39bde11dab0c7d6dbb69e241fc2071764`, clean source at build time. Jar SHA-256 `0bcdc9f8f8440b348d8c8a9597b8d9c8ee771e6f894eb8903c5b0995540b5ecc`.
- RLTweaker 0.7.5; OTG v9.7; DregoraRL 3.9; Distant Horizons 3.3.3-dev; Forge 14.23.5.2860; Java 25.0.4.1.
- RLTweaker RC master, manage events, generate partially and cleanup all report `false`. With the master disabled, its partial-generation flag does not select RC's generation mode.
- RC: `rayAverageMaxHeightSpread=0`, `avoidOverlappingGeneration=true`, `avoidPlacerChunkGeneration=false`. The separately committed SelectivePlacer coordinate fix is inactive with that last setting.
- Dimension 0, OTG world, seed `9202168224662614708`. Embedded UTC session times: 17:32:00.406 through 17:35:29.130. Clean session close, no output error, no metadata truncation.

## Retained runtime evidence

References below are entry names inside the supplied archive and its global `seq`/`trace` identifiers. No tester needs to collect these manually.

`structures/context-162.txt` identifies the root `RLCraft_MegaBattleTower`, saved entry `880b6f46-c32c-4524-8d4e-5346660d0fc2`:

- Initial trace 693, population chunk `(-4,32)`. The actual caller is native `RCForgeEventHandler.onPreChunkDecoration` → `WorldGenStructures` → `StructureGenerator`, invoked from OTG's population event. The recorded completion callers use the same native handler.
- Placement chooses Y=74 (`seq=15330`); base-template full bounds are `(-73,74,499)..(-17,82,555)`, with initial clip `(-56,1,520)..(-41,512,535)` (`seq=15331`). These are the base template's bounds; scripted floors extend above them.
- `ENTRY_ADD` records instance data present and `preventCompletion=false` (`seq=15947`). The initial call succeeds (`seq=27253`). Retained later completion calls refer to the same entry and retain its bounds, Y, rotation and mirroring. This is positive evidence that completion runs with the saved root entry, rather than evidence of its removal by cleanup.
- Completion trace 4810, population `(-3,30)`, retains Y=74 and clips to `(-40,1,488)..(-25,512,503)` (`seq=29334–29337`); it succeeds (`seq=29872`). The clip intersects the child footprint's north edge at Z=502–503.

The initial child records and later eviction identities show these different requests at the same X=-70, Z=502 floor anchors (each floor footprint is 51×51):

| Floor Y | Initial child, saved context / trace | Later completion request, evicted context / trace |
| --- | --- | --- |
| 88 | `BasicFloor`, 164 / 697 | `DecentFloor`, 2207 / 4814 |
| 93 | `BasicFloor`, 165 / 699 | `GoodFloor`, 2208 / 4816 |
| 98 | `DecentFloor`, 166 / 701 | `GoodFloor`, 2209 / 4818 |

All IDs have the prefix `RLCraft_MegaBattleTower_`. Initial details are in `structures/context-164.txt` through `context-166.txt`; later identities are in `evictions.txt`. Those later child details/results were evicted, so the archive establishes different generation requests, not a complete block-by-block account of their success. Initial child entries have `preventCompletion=true`: parent scripts perform their completion, so that flag alone is not a defect.

The final anonymous player position is approximately `(-80.44,148.60,527.63)`, beside this tower and facing toward it. This supports associating the retained tower with the screenshot without asking for coordinates.

The base's existing ray calculation has 517 hits at heights 70–76 and chooses 74. Bounded support observations also show terrain in that vicinity. This return does not demonstrate the separate world-height floating issue or show that OTG later removed its supporting terrain.

## Demonstrated code defect and local correction

In `WorldScriptStructureGenerator.generate`, a new child `StructureGenerator` was passed to `structureData.load(generator)`. That helper attaches data only when its wrapper still contains serialized NBT. If it already contains a prepared Java instance, it returns without giving that instance to the new generator. Generation then prepares another layout, potentially selecting different nested floors for another chunk.

The serialized case also caches the decoded instance in the wrapper while leaving NBT attached to the first generator: that pass can decode twice, and a subsequent generator receives neither representation.

The one-line correction uses the existing `structureData.register(generator)` API. It attaches the already prepared instance or serialized data on every pass. It does not change chunk clips, event timing, locks, defaults, cleanup, or generation mode. It avoids the unintended repeated preparation; it adds no random calls or chunk access.

The incorrect call predates Volts: local Git history attributes it to `eb5482860` (2017), and it is also present in upstream history at `908f8d6a`. The helper behavior and chunk-dependent completion seeds also predate Volts. Consequently this is a confirmed defect in the returned Volts build, but the reported difference from the original mod remains unexplained. This run alone cannot establish which prior change or environmental difference exposes it.

## Validation and limits

Executed with Java 8 and cached dependencies:

```sh
./gradlew --offline test --tests '*WorldScriptStructureGeneratorInstanceDataTest'
./gradlew --offline test --tests '*WorldScriptStructureGenerator*Test' --tests '*RCGenerationDiagnostic*Test' --tests '*WorldStructureGenerationDataCompletionTest'
./gradlew --offline test
```

- Before the correction: both new regressions fail, demonstrating lost prepared data and failure to reuse restored data.
- After the correction: 36 focused tests pass.
- Full available suite: 141 tests across 27 suites, zero failures/errors/skips.
- The two regressions exercise the real child-generation method across two clips with prepared and serialized layouts. They verify reuse, absence of re-preparation, and no extra parent random calls or world access. They do not run Minecraft, OTG, or Dregora terrain generation.

Retention limits matter: 2,131 structure contexts were evicted across the session; the retained root has 113 truncated initial-detail records and 32 evicted later-detail records. Its reserved initial summary survives. Later child identities survive in the eviction list, but their full details do not. Terrain watches also report eviction. Absence of a removal, write or terrain change in this bounded archive cannot prove that it never happened. The retained passes do not establish complete coverage of every tile in the tower.

No additional tester work is needed to establish this code defect. The smallest useful follow-up is a candidate diagnostic jar with only this correction, using the existing install → fresh disposable world/explore → screenshot/quit/send-one-file workflow, with all settings left as they were. There is no need for a matrix, old-world recovery or manual coordinates. A live result is still needed before describing this as a Dregora fix. No new jar or release is produced as part of this return analysis; the existing diagnostic build 2 does not contain this correction.
