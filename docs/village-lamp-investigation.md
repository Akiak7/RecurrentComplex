# Village lamp report — assessment, 27 September 2026

Input: an older tester report that RC produces many village lamp posts and Volts reduces the frequency, plus `2026-08-21_06.38.44.png`. The image shows numerous vanilla-style fence/black-wool/torch posts on steep terrain beside buildings. It does not establish the installed version, settings, piece-selection history, or whether every post has the same cause.

## Confirmed mechanism

Inspected current RC at `5045f725` and the cached Forge 1.12.2-14.23.5.2859 development binary's `StructureVillagePieces` bytecode. Vanilla's private `generateComponent` makes up to five weighted selection attempts, then falls back to `Torch` if its small bounding box fits. Ordinary footprint collisions and selection restrictions can therefore create lamps without an RC defect. This fallback happens while assembling village components, before their terrain-adjusted block generation.

There is also an inherited RC defect worth addressing separately. `GenericVillageCreationHandler.kill` marks a permanently rejected piece as fully spawned, but leaves its positive weight in the village's selection list. Vanilla's `updatePieceWeight` still adds that weight to the total whenever at least one limited piece remains available. A draw landing on the exhausted entry consumes an attempt and breaks out to the next draw. Vanilla removes exhausted entries after a successful placement; RC's rejection returns null and misses that removal path.

`VillageLampFallbackCharacterizationTest` calls the actual vanilla selector with an RC-rejected entry of weight 9 and an available vanilla house of weight 1. A deterministic random source selecting the rejected range five times produces a Torch despite the house fitting. With the rejected entry absent, the same choice produces the house. This is a controlled demonstration of the mechanism, not a statistical estimate or reproduction of the screenshot's world.

The same exhaustion-only code is present in original RC 1.4.8.2 source (`a78a7aae`) and originates in Ivorius's 2017 change `664799ed`. It is not newly introduced by the current release work.

Volts commit `1a2985d6` already corrected a related problem: a fixed-facing building rejected on one orientation is no longer permanently exhausted. That could contribute to the reported improvement, but the screenshot does not establish that causal link. The upstream orientation report is https://github.com/Ivorforce/RecurrentComplex/issues/423.

## Recommendation

Worth a targeted follow-up: permanently unsuitable RC entries should stop consuming selection weight; temporary orientation/footprint failures must remain eligible for later suitable positions. Do not simply suppress lamp posts or globally lower village weights. Any fix must account for vanilla's active iterator and cached total weight—removing an entry inside RC's callback without handling those can cause an iterator failure or inconsistent draws. This is not assumed to be a one-line change.

Do not block the prepared 2.0.1.2 release on this older, separate village-layout issue. No production code, default, configuration, or release jar was changed during this assessment.

Validation: Java 8 `./gradlew --offline test --tests '*Village*Test'` passed all 5 tests across 2 suites (3 existing tests plus 2 new characterization tests), with no failures/errors/skips. `git diff --check` passed. The full suite was not rerun for this test-only assessment; no game/world reproduction was performed.

## Implemented follow-up

At the user's request, all four permanent rejection paths now retire the RC entry from the owning village's selection pool: missing structure, invalid generation type, incompatible biome, and failure to create the village piece. The handler copies the current pool, removes only the rejected entry, and assigns the copy to the village start. It then retains the existing exhaustion marking. The old public `kill(piece)` entry point remains available for binary compatibility; RC's normal callbacks now use the private start-aware overload.

Copying is intentional: vanilla may continue iterating its old list after RC returns null. Mutating that list directly can throw `ConcurrentModificationException`. Subsequent selector iterations/positions read the new list. Entry identities, other mods' pieces, ordering and spawn quotas are preserved. Temporary orientation and footprint failures do not retire entries.

Vanilla has already cached the total selection weight for the current invocation. This fix does not replace that selector or rewrite its in-flight random draw. That bounded invocation can still fall back to a lamp; the next position calculates its total without the rejected weight. Normal collision/selection fallback lamps are unchanged. No lamp-density setting, global village-weight change, ASM patch or new random draw/terrain lookup was added to retirement. Successful building selection can naturally change later village layouts and random consumption.

Five new regression cases failed against the old implementation and passed after the correction. A sixth covers multiple rejections followed by a valid house exhausting its quota within the same iterator. Tests call the actual vanilla selector and Forge village handler bridge; they cover iterator safety, cached weights across successive calls, exhausted pools, unrelated entries, successful quota removal, and temporary versus permanent failures. The old exhaustion-only reproducer remains to document why the start-aware path is necessary.

The final focused run passed 55 tests across 11 suites (including all 11 village tests), with no failures/errors/skips:

```sh
JAVA_HOME=/Library/Java/JavaVirtualMachines/zulu-8.jdk/Contents/Home ./gradlew --offline test --tests '*Village*Test' --tests '*NaturalAir*Test' --tests '*WorldScriptStructureGenerator*Test' --tests '*RCGenerationDiagnostic*Test' --tests '*WorldStructureGenerationDataCompletionTest' --tests '*SelectivePlacerTest'
```

Before/after logs and focused test XML are retained in `build/village-lamp-investigation/`. The previous unpublished jar is preserved in `build/releases/2.0.1.2-before-village-fix/`. This is a demonstrated correction to one excess-lamp mechanism, not an in-game reproduction or a guarantee that the supplied screenshot would contain no lamps.

The subsequent full Java 8 suite (`./gradlew --offline test`) passed all **156 tests across 32 suites**, with no failures, errors or skips. `git diff --check` passed. No release was published.
