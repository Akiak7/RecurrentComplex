**Landon — three-step diagnostic test**

1. **Install the two supplied files.** Replace your existing RC jar with the diagnostic jar in `mods`. Put `reccomplex-diagnostics.properties` in `config`. Keep just one RC jar installed. Leave your other settings alone. For multiplayer, install these on the server.

2. **Start a fresh disposable world and explore or pregenerate normally.** Recording starts automatically. No commands, JVM arguments, coordinates or comparison runs are needed.

3. **If something looks wrong, take a screenshot and quit reasonably soon afterwards.** Send the screenshot and the newest **`RC-diagnostic-….zip`** from **`diagnostics/recurrentcomplex`** in the instance folder (the server folder for multiplayer). The ZIP is saved automatically; send it as-is. You do not need to open it, inspect logs, or gather configs.

Each session gets its own file, so another launch will not overwrite it. Recent history is limited, which is why quitting soon after seeing a problem helps. This is a diagnostic build, not a confirmed fix. Remove the supplied diagnostic config when finished to stop recording.
