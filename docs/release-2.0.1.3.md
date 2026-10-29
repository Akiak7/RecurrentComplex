# Recurrent Complex Volts 1.12.2 — 2.0.1.3

Adds an optional maze layout queue to reduce pauses when generating maze structures.
It prepares fresh layouts in the background and uses each one only once.

**The feature is off by default.** With it disabled, maze generation uses the original
solver. Existing structures keep their saved layouts.

## Enable it

In the `balancing` section of `config/reccomplex.cfg`, set:

```properties
B:mazeLayoutQueue=true
```

Then restart. Layout preparation starts automatically; no maze encounter is required
to begin filling the cache. Default settings:

| Setting | Default | Meaning |
| --- | --- | --- |
| `mazeLayoutQueueSize` | `2` | Fresh, unused layouts per cached configuration. |
| `mazeLayoutQueueMaxConfigurations` | `16` | Cached configurations; `0` disables capacity eviction. |
| `mazeLayoutBackgroundTimeoutMultiplier` | `2.0` | Background attempts get twice the normal `mazeTimeout`. |
| `mazeLayoutWarmupMillis` | `0` | No deliberate startup wait; positive values optionally wait before player logins. |

One structure can have several configurations depending on rotation, variables and
environment. Automatic preparation favors spawn biomes, then rotation/mirror variant
and generation weight. Actual encounters get refill priority.

## What to expect

- Ready layouts avoid solving that maze during generation. Empty or unmatched queues
  still use the original solver and can cause pauses. Block placement stays on the
  server thread, and custom maze rules use the original solver.
- One low-priority worker uses extra CPU and RAM. Larger caches use more memory;
  longer background attempts can delay other jobs. Unlimited retention does not
  prepare every possible variation upfront: discovery remains bounded.
- With the queue enabled, new layouts can depend on exploration order and background
  timing rather than only the world seed and position.
- The cache is held in RAM, clears on restart or relevant reloads, and is not saved
  to disk. Initial preparation retries up to three times, including after discovery
  has finished.
- Server logs report discovery, ready hits, slow fallbacks, cached configurations,
  ready layouts and evictions. The first capacity eviction warns once per reset.

Existing config values stay saved when updating. Users of the experimental builds
should explicitly change `mazeLayoutWarmupMillis=20000` to `0` if they want the new
zero-wait default. To disable the feature, set `mazeLayoutQueue=false` and restart.
