Recurrent Complex
============

[![Build Status](https://cloud.drone.io/api/badges/Ivorforce/RecurrentComplex/status.svg)](https://cloud.drone.io/Ivorforce/RecurrentComplex)
![Curse Version](http://cf.way2muchnoise.eu/versions/223150.svg)
![Curse Downloads](http://cf.way2muchnoise.eu/223150.svg)

[Download](https://mods.curse.com/mc-mods/minecraft/223150-recurrent-complex#) | [Wiki](https://github.com/Ivorforce/RecurrentComplex/wiki) | [Forum](http://www.minecraftforum.net/topic/563257-172-ivorius-mods-drugs-statues-flags-boxes-of-doom-hamsters/) 

Contributing
============

Quick guide:

Requires: [Gradle](https://gradle.org), [Java 8 JDK](http://www.oracle.com/technetwork/java/javase/downloads/index.html)
* gradle setupDecompWorkspace
* Depending on your IDE:
  * IntelliJ idea: Import gradle project, then: gradle genIntellijRuns
  * Eclipse: gradle eclipse
* And you're done!

This mod is written with [Minecraft Forge](http://www.minecraftforge.net/). Visit [its repo](https://github.com/MinecraftForge/MinecraftForge) for detailed setup instructions.

Experimental maze layout queue
==============================

Set `B:mazeLayoutQueue=true` in the `balancing` section of `config/reccomplex.cfg`
to try preparing fresh maze layouts in the background. It is disabled by default.
Each prepared result is consumed once; this is not a fixed pool of reusable mazes.
`I:mazeLayoutQueueSize=2` is the target number of unused layouts **per cached maze
configuration**, not a total across structures (allowed range 1–8). A structure can
have several configurations depending on rotation, variables and environment.
`I:mazeLayoutQueueMaxConfigurations=16` controls how many configurations are retained.
When full, a new encounter replaces the least recently used configuration. Set it
to `0` to disable capacity eviction; this can use more memory and does not promise
to discover or prepare every possible variation upfront. One low-priority worker
serves all queues. Each layout is used once, and the queue is held in RAM, not on disk.

Startup warmup discovers maze scripts in active naturally generated structures and
prepares representative configurations, prioritizing biomes at loaded worlds' spawn
points, followed by rotation/mirror variant and generation weight. The default
`I:mazeLayoutWarmupMillis=0` starts discovery on server ticks and solves layouts in
the background without a deliberate startup wait. It does **not** wait for the
first maze encounter. A positive value optionally waits up to that many milliseconds
before Forge permits player logins; remaining work continues in the background.
An early encounter can still arrive before its layout is ready.

`S:mazeLayoutBackgroundTimeoutMultiplier=2.0` gives every background solve attempt
twice `mazeTimeout` (allowed multiplier 1–10). Initial preparation retries up to
three times; foreground generation keeps the original `mazeTimeout`. Longer
background attempts can delay other jobs on the single worker. Cooperative
cancellation still applies, and optional startup waiting does not wait for an
individual worker attempt to finish.

Existing config values are preserved when updating the jar. If an earlier experiment
saved `I:mazeLayoutWarmupMillis=20000`, explicitly change it to `0` to try the new default.

The same resolved request builder is used during warmup and generation. A result is
used only for matching rules, components, weights, variables and transforms. Unmatched
configurations and empty queues use the normal solver and can still stall; rapid
exploration can outpace replenishment. Warmup covers representative parent-variable
assignments and skips oversized or unusually complex definitions. Discovery still
has scan, candidate and context-resolution limits even with unlimited capacity.
Custom maze rule
classes use the normal solver. Component/environment evaluation and actual structure
placement remain on the server thread; only detached layout solving uses the worker.
Actual demand takes priority over queued speculative jobs.

With this option enabled, new layouts can depend on exploration order and background
timing, rather than only world seed and position. Existing structures keep their
saved layouts. Structure and balancing config reloads clear the queue and schedule
fresh warmup incrementally on server ticks, without another startup wait. Server
shutdown clears the queue and cancels pending warmup. Failed or timed-out background
results are discarded.

After maze requests, `Maze queue summary` lines are written to the server log at most
once per minute when counters change, and flushed on queue reset or shutdown. They
show eligible requests, ready hits and hit rate, slow-solver fallbacks, discarded
background attempts, configuration capacity, cached configurations, ready layouts,
and evictions. The first actual capacity eviction warns once per reset. Counts
start again after a queue reset. Custom rule classes
that bypass the queue are excluded. Discarded attempts include speculative timeouts;
they do not mean an already placed structure failed. Startup timing is logged
separately as `Maze startup warmup` when a positive wait is configured. `Maze layout
discovery` reports supported maze-bearing natural structure definitions found,
configurations admitted, and whether discovery or preparation was limited. Those
are bounded discovery counts, not a count of all possible configurations.

The local solver benchmark runs with
`./gradlew --offline -I tools/benchmark-maze-layouts.gradle mazeLayoutBenchmark`;
add `-PmazeQueue` to exercise the queue, including cold and exhausted cases. It
measures layout generation only, without loading chunks or placing blocks.

For the test-only headless Forge workload, use
`./gradlew --offline -I tools/explore-maze-layouts.gradle runServer -PmazeWork=build/maze-investigation/exploration/NEW-RUN -PmazeScenario=queue-spaced`.
Each run requires a fresh directory and Minecraft's server EULA acceptance there.
The scenarios are `baseline-burst`, `queue-burst` and `queue-spaced` (six controlled
BigPyramid encounters each). The spaced scenario leaves 15 seconds between completed
encounters. CSV files record total generation, preparation, partial placement, chunk
exploration, queue counters, failed spawns and server tick durations. This exercises
actual block placement in flat desert worlds; it does not measure natural maze frequency
or a particular modpack. Only local launch copies of libraries receive compatibility
fixes needed by the cached Forge development runtime.

To test startup warming, enable `B:mazeLayoutQueue=true` in the isolated run's
`config/reccomplex.cfg` before launch, set a positive `I:mazeLayoutWarmupMillis`, and use `-PmazeScenario=queue-warmup-first -PmazeEncounters=1` or `-PmazeScenario=queue-warmup-spaced`. These preserve the startup
queue and request the first pyramid on the first server tick, with no artificial
post-readiness delay. `startup-warmup.csv` reports startup warmup time separately;
`encounters.csv` also records how soon each encounter started after readiness.
Use `-PmazeScenario=queue-background-spaced` with the zero startup-wait default to
preserve automatic preparation and allow 30 seconds of server ticks before the
first encounter, followed by 15 seconds between encounters. The driver logs queue
statistics before the first encounter so preparation before demand can be checked.
