**Landon — tower fix candidate (diagnostic build 3)**

1. **With the game/server closed, install the two supplied files.** Replace the previous RC jar with the new jar in `mods`. Put `reccomplex-diagnostics.properties` in `config` (replace the old diagnostic properties file if present). Keep just one RC jar installed. **Keep your usual `reccomplex.cfg` and all RC/RLTweaker settings unchanged from the run you sent.** For multiplayer, install these on the server.

2. **Start a fresh disposable world and explore or pregenerate normally.** Recording starts automatically. No commands, JVM arguments, coordinates or comparison runs are needed.

3. **If something looks wrong, take a screenshot and quit reasonably soon afterwards.** Send the screenshot and the newest **`RC-diagnostic-….zip`** from **`diagnostics/recurrentcomplex`** in the instance folder (the server folder for multiplayer). The ZIP is saved automatically; send it as-is. You do not need to open it, inspect logs, or gather configs.

Each session gets its own file, so another launch will not overwrite it. Recent history is limited, which is why quitting soon after seeing a problem helps. This build corrects a demonstrated floor-layout reuse bug; it is not yet a confirmed Dregora fix. It is not expected to repair towers already generated. If you see no problem, just say so; that alone does not prove it is fixed. Remove the supplied diagnostic config when finished to stop recording.
