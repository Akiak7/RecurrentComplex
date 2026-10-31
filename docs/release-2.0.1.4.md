# Recurrent Complex Volts 1.12.2 — 2.0.1.4

- Fixed legacy spawners losing their mob type and appearing as pig spawners when
  structures are imported or generated, including `PyramidMazeSpiderTurn`.
- Preserves intended mob types, weighted spawn entries, spawn settings and custom
  entity data. Modern spawner data and unrelated tile entities retain their behavior.
- Older structure caches rebuild automatically on loading; no config changes or
  manual cache deletion are needed.

The fix applies to future imports and generation. Already placed spawners are not
automatically repaired. Maze queue settings and defaults are unchanged.
