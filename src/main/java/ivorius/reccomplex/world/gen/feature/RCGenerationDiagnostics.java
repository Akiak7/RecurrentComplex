package ivorius.reccomplex.world.gen.feature;

import ivorius.reccomplex.RCConfig;
import ivorius.reccomplex.RecurrentComplex;
import ivorius.reccomplex.world.gen.feature.structure.context.StructureSpawnContext;
import net.minecraft.nbt.NBTTagCompound;
import net.minecraft.entity.player.EntityPlayer;
import net.minecraft.server.MinecraftServer;
import net.minecraft.util.math.BlockPos;
import net.minecraft.world.World;
import net.minecraft.world.WorldServer;
import net.minecraft.world.chunk.Chunk;
import net.minecraft.world.gen.structure.StructureBoundingBox;
import net.minecraftforge.common.MinecraftForge;
import net.minecraftforge.event.terraingen.PopulateChunkEvent;
import net.minecraftforge.event.world.WorldEvent;
import net.minecraftforge.fml.common.Loader;
import net.minecraftforge.fml.common.ModContainer;
import net.minecraftforge.fml.common.eventhandler.EventPriority;
import net.minecraftforge.fml.common.eventhandler.SubscribeEvent;
import net.minecraftforge.fml.common.gameevent.TickEvent;

import java.io.File;
import java.io.FileInputStream;
import java.io.InputStream;
import java.lang.reflect.Field;
import java.lang.reflect.Modifier;
import java.security.MessageDigest;
import java.util.*;
import java.util.function.Consumer;
import java.util.function.Supplier;

/** Temporary, opt-in worldgen observations. Never provides chunks or consults a worldgen RNG. */
public final class RCGenerationDiagnostics
{
    static RCGenerationDiagnostics active = new RCGenerationDiagnostics(Settings.read(new Properties()),
            line -> RecurrentComplex.logger.info(line));

    public static RCGenerationDiagnostics get() { return active; }

    static final int MAX_WATCHES = 64, MAX_POINTS = 5, MAX_POSTS = 16, MAX_SAMPLES = 16;
    final Settings settings;
    private final Consumer<String> sink;
    private File outputDirectory;
    volatile RCGenerationDiagnosticFile file;
    private final Map<EntityPlayer, Integer> playerIDs = new WeakHashMap<>();
    private int nextPlayerID;
    private int records;
    private long nextID;
    private volatile boolean stopped;
    private final ThreadLocal<Scope> current = new ThreadLocal<>();
    private final ThreadLocal<World> loadingWorld = new ThreadLocal<>();
    private final ThreadLocal<Deque<Population>> populations = ThreadLocal.withInitial(ArrayDeque::new);
    private final Map<WorldStructureGenerationData, World> dataWorlds = new WeakHashMap<>();
    private final LinkedHashMap<Long, Watch> watches = new LinkedHashMap<>();

    RCGenerationDiagnostics(Settings settings, Consumer<String> sink)
    {
        this.settings = settings;
        this.sink = sink;
    }

    public static void configure(File configDirectory)
    {
        active = new RCGenerationDiagnostics(Settings.readFile(new File(configDirectory, "reccomplex-diagnostics.properties")),
                line -> RecurrentComplex.logger.info(line));
        active.outputDirectory = new File(configDirectory.getParentFile(), "diagnostics/recurrentcomplex");
    }

    public boolean enabled() { return settings.enabled && !stopped && (outputDirectory == null || file != null); }
    public boolean recording() { return enabled() && selected(current.get()); }

    public void register()
    {
        if (settings.error != null) emit("CONFIG_ERROR", settings.error);
        if (settings.enabled) MinecraftForge.EVENT_BUS.register(this);
    }

    public synchronized void startSession()
    {
        if (!settings.enabled || outputDirectory == null) return;
        finishSession();
        stopped = false;
        try
        {
            file = new RCGenerationDiagnosticFile(outputDirectory.toPath());
            file.metadata("DIAGNOSTIC_SETTINGS enabled=true dimension=" + (settings.allDimensions ? "all" : settings.dimension)
                    + " area=" + (settings.allArea ? "all" : settings.area) + " structure=" + settings.structure
                    + " watches=" + MAX_WATCHES + " pointsPerWatch=" + MAX_POINTS + " postsPerWatch=" + MAX_POSTS);
            try (InputStream stream = getClass().getResourceAsStream("/diagnostic-build.properties"))
            {
                Properties build = new Properties();
                if (stream != null) build.load(stream);
                file.metadata("BUILD " + (stream == null ? "UNAVAILABLE" : build.toString()));
            }
            try { captureSettings(); }
            catch (RuntimeException e) { file.metadata("SETTINGS_UNAVAILABLE " + e.getClass().getSimpleName()); }
            file.startSaving();
            RecurrentComplex.logger.info("RC diagnostics enabled: diagnostics/recurrentcomplex/{}", file.path.getFileName());
        }
        catch (Exception e)
        {
            RecurrentComplex.logger.error("RC diagnostics could not start ({})", e.getClass().getSimpleName());
            finishSession();
        }
    }

    public synchronized void finishSession()
    {
        RCGenerationDiagnosticFile previous = file;
        file = null;
        if (previous != null)
        {
            previous.close();
            if (!"none".equals(previous.lastOutputError()))
                RecurrentComplex.logger.error("RC diagnostic output problem: {}", previous.lastOutputError());
        }
        synchronized (this) { watches.clear(); dataWorlds.clear(); playerIDs.clear(); nextPlayerID = 0; }
        current.remove(); populations.remove(); loadingWorld.remove();
    }

    // This is a diagnostic lock only; no worldgen or saved-data locks are acquired here.
    synchronized void emit(String event, String detail)
    {
        Scope scope = current.get();
        emit(scope == null ? null : scope.evidence, scope != null && scope.initial, event, detail);
    }

    private synchronized void emit(RCGenerationDiagnosticFile.Evidence evidence, boolean initial, String event, String detail)
    {
        if (stopped) return;
        try
        {
            if (file != null)
            {
                file.record(evidence, initial, event, "thread=" + Thread.currentThread().getId() + " " + detail);
                return;
            }
            if (records++ >= settings.maxRecords)
            {
                sink.accept("[RC-DIAG] TRUNCATED record limit reached; further observations disabled until restart");
                stopped = true;
                return;
            }
            sink.accept("[RC-DIAG] " + event + " thread=" + Thread.currentThread().getId() + " " + clean(detail));
        }
        catch (RuntimeException error)
        {
            stopped = true; // Logging must not abort generation.
            if (file != null) file.metadata("CAPTURE_STOPPED reason=" + error.getClass().getSimpleName() + " later-evidence=unavailable");
        }
    }

    private static String clean(Object value)
    {
        return RCGenerationDiagnosticFile.safe(value);
    }

    static String callers()
    {
        StringJoiner result = new StringJoiner(" <- ");
        int count = 0;
        for (StackTraceElement frame : Thread.currentThread().getStackTrace())
        {
            String name = frame.getClassName();
            if (!name.equals(RCGenerationDiagnostics.class.getName())
                    && (name.startsWith("ivorius.reccomplex.") || name.startsWith("com.charles445.rltweaker.")
                    || name.startsWith("com.pg85.otg.") || name.startsWith("com.seibel.")
                    || name.toLowerCase(Locale.ROOT).contains("pregen")))
            {
                result.add(name + "." + frame.getMethodName());
                if (++count == 14) { result.add("[CALLER_LIMIT]"); break; }
            }
        }
        return result.toString();
    }

    public Scope begin(WorldServer world, String id, String generation, String stage, StructureBoundingBox bounds,
                       StructureBoundingBox clip, BlockPos anchor, UUID entry)
    {
        Scope scope = new Scope(current.get(), world, id);
        current.set(scope); // Even an excluded child must not attach its samples to its parent.
        StructureBoundingBox area = clip != null ? clip : bounds;
        scope.selected = enabled() && world != null && settings.dimensionMatches(world.provider.getDimension())
                && settings.idMatches(id) && (settings.allArea || (area != null ? settings.intersects(area)
                : anchor != null && settings.contains(anchor.getX() >> 4, anchor.getZ() >> 4)));
        if (scope.selected)
        {
            synchronized (this) { scope.id = ++nextID; }
            // Natural generation uses SUGGEST; like GenerateMaturity.isFirstTime(), only COMPLEMENT is a later pass.
            scope.initial = !"COMPLEMENT".equals(stage);
            RCGenerationDiagnosticFile output = file;
            if (output != null) scope.evidence = output.begin(entry, scope.initial);
            emit("BEGIN", tag(scope) + " generation=" + generation + " stage=" + stage + " entry=" + entry
                    + " knownBounds=" + bounds + " clip=" + clip + " anchor=" + anchor
                    + " population=" + population() + " caller=" + callers());
        }
        return scope;
    }

    public void end(Scope scope) { current.set(scope.previous); }

    public void result(Scope scope, StructureGenerator.GenerationResult result, Throwable error)
    {
        if (!selected(scope)) return;
        String outcome = error != null ? "threw " + error.getClass().getName()
                : result instanceof StructureGenerator.GenerationResult.Failure
                ? "failure=" + ((StructureGenerator.GenerationResult.Failure) result).description
                : "success=" + (result != null && result.succeeded());
        if (result instanceof StructureGenerator.GenerationResult.Failure.Exception)
            outcome += " exception=" + ((StructureGenerator.GenerationResult.Failure.Exception) result).exception.getClass().getName();
        emit("RESULT", tag(scope) + " " + outcome);
    }

    public void placement(int y, Object placer, StructureBoundingBox unplacedBounds)
    {
        if (!enabled()) return;
        Scope scope = current.get();
        if (selected(scope)) emit("PLACEMENT", tag(scope) + " chosenY=" + y + " unplacedBounds=" + unplacedBounds
                + " placer=" + placer.getClass().getName());
    }

    public void planned(StructureSpawnContext spawn, Long seed)
    {
        if (recording()) emit("PLANNED", tag(current.get()) + " chosenY=" + spawn.boundingBox.minY
                + " full=" + spawn.boundingBox + " clip=" + spawn.generationBB + " seed=" + seed
                + " maturity=" + spawn.generateMaturity + " layer=" + spawn.generationLayer);
    }

    public void detail(String detail)
    {
        Scope scope = current.get();
        if (!selected(scope)) return;
        if (scope.details++ < MAX_SAMPLES) emit("PLACER", tag(scope) + " " + detail);
        else if (scope.details == MAX_SAMPLES + 1) emit("PLACER_LIMIT", tag(scope));
    }

    public void unavailableProbe(BlockPos position)
    {
        Scope scope = current.get();
        if (selected(scope) && scope.unavailable.size() < MAX_POINTS
                && scope.unavailable.add(net.minecraft.util.math.ChunkPos.asLong(position.getX() >> 4, position.getZ() >> 4)))
            emit("PROBE_EXCLUDED", tag(scope) + " pos=" + position + " reason=existing-canProbe-returned-false");
    }

    public void sample(String kind, BlockPos position, String result, boolean support)
    {
        Scope scope = current.get();
        if (!selected(scope)) return;
        if (support && scope.points.size() < MAX_POINTS && scope.points.stream().noneMatch(
                p -> p.getX() == position.getX() && p.getZ() == position.getZ()))
            scope.points.add(position.toImmutable());
        if (scope.samples++ < MAX_SAMPLES) emit("SAMPLE", tag(scope) + " kind=" + kind + " pos=" + position + " " + result);
        else if (scope.samples == MAX_SAMPLES + 1) emit("SAMPLE_LIMIT", tag(scope) + " remaining samples omitted; ray summaries retained");
    }

    public Watch beforeWrite(StructureSpawnContext spawn, Long seed, String stage)
    {
        if (!enabled()) return null;
        Scope scope = current.get();
        if (!selected(scope)) return null;
        Watch watch;
        synchronized (this)
        {
            watch = new Watch(++nextID, scope, population());
            if (watches.size() >= MAX_WATCHES)
            {
                Long oldest = watches.keySet().iterator().next();
                Watch removed = watches.remove(oldest);
                emit(removed.evidence, false, "WATCH_EVICTED", "watch=" + oldest + " coverage=lost");
            }
            if (!scope.points.isEmpty() && !"pre-completion".equals(stage)) watch.points.addAll(scope.points);
            else
            {
                StructureBoundingBox bb = spawn.boundingBox, clip = spawn.generationBB;
                int x0 = clip == null ? bb.minX : Math.max(bb.minX, clip.minX);
                int z0 = clip == null ? bb.minZ : Math.max(bb.minZ, clip.minZ);
                int x1 = clip == null ? bb.maxX : Math.min(bb.maxX, clip.maxX);
                int z1 = clip == null ? bb.maxZ : Math.min(bb.maxZ, clip.maxZ);
                if (x0 <= x1 && z0 <= z1)
                {
                    int y = Math.max(0, Math.min(255, bb.minY - 1));
                    watch.points.add(new BlockPos(x0 + (x1 - x0) / 2, y, z0 + (z1 - z0) / 2));
                    watch.points.add(new BlockPos(x0, y, z0));
                    watch.points.add(new BlockPos(x1, y, z0));
                    watch.points.add(new BlockPos(x0, y, z1));
                    watch.points.add(new BlockPos(x1, y, z1));
                }
            }
            watches.put(watch.id, watch);
        }
        emit("PASS", tag(scope) + " watch=" + watch.id + " stage=" + stage + " seed=" + seed
                + " chosenY=" + spawn.boundingBox.minY + " full=" + spawn.boundingBox + " clip=" + spawn.generationBB
                + " rotation=" + spawn.transform.getRotation() + " mirror=" + spawn.transform.isMirrorX()
                + " points=" + (scope.points.isEmpty() || "pre-completion".equals(stage) ? "bounds-fallback" : "candidate-ray-hits"));
        observe(watch, "before-write");
        return watch;
    }

    public void afterWrite(Watch watch, boolean succeeded)
    {
        if (watch == null || !enabled()) return;
        emit(watch.evidence, watch.initial, "WRITE_RESULT", "watch=" + watch.id + " success=" + succeeded);
        observe(watch, "after-write");
    }

    void observe(Watch watch, String phase)
    {
        RCGenerationDiagnosticFile output = file;
        if (!enabled() || output != null && !output.retained(watch.evidence)) return;
        for (BlockPos point : watch.points)
        {
            if (!enabled()) return;
            try
            {
                // getLoadedChunk is the ONLY chunk lookup made by diagnostics. Never use world.getBlockState/height.
                Chunk chunk = watch.world.getChunkProvider().getLoadedChunk(point.getX() >> 4, point.getZ() >> 4);
                if (chunk == null)
                {
                    emit(watch.evidence, watch.initial && !phase.startsWith("population"), "SUPPORT",
                            "watch=" + watch.id + " phase=" + phase + " pos=" + point + " UNAVAILABLE");
                    continue;
                }
                StringJoiner states = new StringJoiner(",");
                for (int y = Math.max(0, point.getY() - 4); y <= Math.min(255, point.getY() + 4); y++)
                    states.add(y + ":" + chunk.getBlockState(new BlockPos(point.getX(), y, point.getZ())));
                emit(watch.evidence, watch.initial && !phase.startsWith("population"), "SUPPORT", "watch=" + watch.id + " phase=" + phase + " pos=" + point
                        + " terrainPopulated=" + chunk.isTerrainPopulated() + " window=" + states);
            }
            catch (RuntimeException e) { emit(watch.evidence, false, "OBSERVATION_ERROR", "watch=" + watch.id + " phase=" + phase + " " + e.getClass().getName()); }
        }
    }

    public void completionSkip(WorldServer world, WorldStructureGenerationData.StructureEntry entry, int x, int z, String reason)
    {
        RCGenerationDiagnosticFile output = file;
        if (enabled() && settings.dimensionMatches(world.provider.getDimension()) && settings.contains(x, z)
                && settings.idMatches(entry.getStructureID()))
            emit(output == null ? null : output.begin(entry.getUuid(), false), false, "COMPLETION_SKIP",
                    "dim=" + world.provider.getDimension() + " chunk=" + x + "," + z + " " + entry(entry)
                    + " reason=" + reason + " caller=" + callers());
    }

    public WorldStructureGenerationData data(World world, Supplier<WorldStructureGenerationData> load)
    {
        World previous = loadingWorld.get();
        loadingWorld.set(world);
        try
        {
            WorldStructureGenerationData data = load.get();
            synchronized (this) { dataWorlds.put(data, world); }
            return data;
        }
        finally { loadingWorld.set(previous); }
    }

    private synchronized boolean matches(WorldStructureGenerationData data)
    {
        World world = loadingWorld.get();
        if (world == null) world = dataWorlds.get(data);
        return enabled() && world != null && !world.isRemote && settings.dimensionMatches(world.provider.getDimension());
    }

    private synchronized String dimension(WorldStructureGenerationData data)
    {
        World world = loadingWorld.get() != null ? loadingWorld.get() : dataWorlds.get(data);
        return world == null ? "unknown" : String.valueOf(world.provider.getDimension());
    }

    public void entryEvent(String event, WorldStructureGenerationData data, WorldStructureGenerationData.Entry entry)
    {
        if (!enabled()) return;
        if (!matches(data) || !settings.intersects(entry.getBoundingBox())) return;
        if (entry instanceof WorldStructureGenerationData.StructureEntry
                && !settings.idMatches(((WorldStructureGenerationData.StructureEntry) entry).getStructureID())) return;
        if (!(entry instanceof WorldStructureGenerationData.StructureEntry) && !settings.structure.isEmpty()) return;
        Scope scope = current.get();
        RCGenerationDiagnosticFile output = file;
        RCGenerationDiagnosticFile.Evidence evidence = output == null ? null : output.find(entry.getUuid());
        boolean initial = false;
        if (evidence == null && event.equals("ENTRY_ADD") && selected(scope) && scope.initial
                && entry instanceof WorldStructureGenerationData.StructureEntry
                && Objects.equals(scope.structure, ((WorldStructureGenerationData.StructureEntry) entry).getStructureID()))
        {
            evidence = scope.evidence;
            initial = true;
            if (output != null) output.bind(evidence, entry.getUuid());
        }
        emit(evidence, initial, event, "dim=" + dimension(data)
                + " " + entry(entry) + " trace=" + traceID() + (file != null && evidence == null ? " INITIAL_CONTEXT_UNAVAILABLE" : "")
                + " caller=" + callers());
    }

    public void dataRead(WorldStructureGenerationData data, NBTTagCompound nbt, boolean before)
    {
        if (!enabled()) return;
        if (!matches(data)) return;
        emit(before ? "DATA_LOAD_BEGIN" : "DATA_LOAD_END", "dim=" + dimension(data)
                + " entries=" + data.entryMap.size() + " checked=" + data.checkedChunks.size()
                + " checkedFinal=" + data.checkedChunksFinal.size() + " entriesTag=" + nbt.hasKey("entries")
                + " checkedTag=" + nbt.hasKey("checkedChunks") + " finalTag=" + nbt.hasKey("checkedChunksFinal")
                + " caller=" + callers());
        for (WorldStructureGenerationData.Entry entry : data.entryMap.values())
        {
            if (!enabled()) break;
            entryEvent(before ? "ENTRY_RESET" : "ENTRY_LOADED", data, entry);
        }
    }

    public void checked(WorldStructureGenerationData data, int x, int z, boolean finalPass, boolean added)
    {
        if (enabled() && settings.contains(x, z) && matches(data))
        {
            RCGenerationDiagnosticFile output = file;
            if (output != null && !recording()) { output.count(finalPass ? "checkedFinal" : "checked"); return; }
            emit("CHUNK_CHECK", "dim=" + dimension(data) + " chunk=" + x + "," + z + " final=" + finalPass
                    + " newlyChecked=" + added + " caller=" + callers());
        }
    }

    private static String entry(WorldStructureGenerationData.Entry entry)
    {
        String result = "entry=" + entry.getUuid() + " bounds=" + entry.getBoundingBox();
        if (entry instanceof WorldStructureGenerationData.StructureEntry)
        {
            WorldStructureGenerationData.StructureEntry s = (WorldStructureGenerationData.StructureEntry) entry;
            result += " structure=" + s.structureID + " generation=" + s.generationInfoID + " firstTime=" + s.firstTime
                    + " preventCompletion=" + s.preventComplementation + " instanceData=" + (s.instanceData != null) + " seed=" + s.seed;
        }
        return result;
    }

    @SubscribeEvent(priority = EventPriority.HIGHEST)
    public void worldLoad(WorldEvent.Load event)
    {
        World world = event.getWorld();
        if (!enabled() || world.isRemote || !settings.dimensionMatches(world.provider.getDimension())) return;
        metadata("WORLD", "dim=" + world.provider.getDimension() + " seed=" + world.getSeed()
                + " provider=" + world.provider.getClass().getName() + " worldType=" + world.getWorldInfo().getTerrainType().getName()
                + " mapFeatures=" + world.getWorldInfo().isMapFeaturesEnabled()
                + " generatorOptions=" + clean(world.getWorldInfo().getGeneratorOptions()));
    }

    private void metadata(String event, String detail)
    {
        RCGenerationDiagnosticFile output = file;
        if (output != null) output.metadata(event + " " + detail);
        else emit(event, detail);
    }

    private void captureSettings()
    {
        metadata("SESSION", "java=" + System.getProperty("java.version") + " modVersion=" + RecurrentComplex.VERSION);
        // All mod IDs/versions, but only relevant binaries are hashed. Never include mod source paths.
        for (ModContainer mod : Loader.instance().getActiveModList())
        {
            String id = mod.getModId();
            String description = id + " version=" + mod.getVersion();
            if (Arrays.asList("reccomplex", "rltweaker", "openterraingenerator", "ivtoolkit", "forge").contains(id)
                    || id.toLowerCase(Locale.ROOT).contains("distant") || id.toLowerCase(Locale.ROOT).contains("pregen"))
                try { description += " sha256=" + sha256(mod.getSource()); }
                catch (Exception e) { description += " hashUnavailable=" + e.getClass().getSimpleName(); }
            metadata("MOD", description);
        }
        fields("RC.effective", RCConfig.class, null);
        // These categories contain generation options; deliberately exclude general command/account expressions.
        if (RecurrentComplex.config != null)
            for (String category : Arrays.asList("balancing", "decoration"))
                for (Map.Entry<String, net.minecraftforge.common.config.Property> option : RecurrentComplex.config.getCategory(category).entrySet())
                {
                    net.minecraftforge.common.config.Property value = option.getValue();
                    metadata("RC.config", category + "." + option.getKey() + "="
                            + (value.isList() ? Arrays.toString(value.getStringList()) : value.getString()));
                }
        try
        {
            Class<?> config = Class.forName("com.charles445.rltweaker.config.ModConfig", false, getClass().getClassLoader());
            Object server = config.getField("server").get(null);
            Object recurrent = server.getClass().getField("recurrentcomplex").get(server);
            fields("RLTweaker.RC.effective", recurrent.getClass(), recurrent);
        }
        catch (Exception e) { metadata("SETTINGS", "RLTweaker.RC unavailable=" + e.getClass().getSimpleName()); }
        metadata("COVERAGE", "Mod list identifies DH/pregeneration mods if present. Their full configs and OTG preset files are not collected."
                + " Placement evidence records evaluated values, not copies of structure definitions. No live-pack validation implied.");
    }

    private void fields(String label, Class<?> type, Object instance)
    {
        for (Field field : type.getFields())
        {
            if (instance == null && !Modifier.isStatic(field.getModifiers())) continue;
            if (!(field.getType().isPrimitive() || field.getType() == String.class)) continue;
            try { metadata("SETTINGS", label + "." + field.getName() + "=" + field.get(instance)); }
            catch (Exception e) { metadata("SETTINGS", label + "." + field.getName() + " unavailable=" + e.getClass().getSimpleName()); }
        }
    }

    @SubscribeEvent
    public void playerTick(TickEvent.PlayerTickEvent event)
    {
        if (!enabled() || file == null || event.side.isClient() || event.phase != TickEvent.Phase.END) return;
        if (event.player.ticksExisted % 100 == 0) playerLocation(event.player, "periodic");
    }

    @SubscribeEvent
    public void playerLogin(net.minecraftforge.fml.common.gameevent.PlayerEvent.PlayerLoggedInEvent event)
    {
        RCGenerationDiagnosticFile output = file;
        if (!enabled() || output == null) return;
        playerLocation(event.player, "login");
        event.player.sendMessage(new net.minecraft.util.text.TextComponentString(
                "RC diagnostics active. After quitting, send diagnostics/recurrentcomplex/" + output.path.getFileName()));
    }

    @SubscribeEvent
    public void playerLogout(net.minecraftforge.fml.common.gameevent.PlayerEvent.PlayerLoggedOutEvent event)
    {
        if (enabled()) playerLocation(event.player, "logout");
    }

    private synchronized void playerLocation(EntityPlayer player, String phase)
    {
        if (file == null) return;
        Integer id = playerIDs.get(player);
        if (id == null)
        {
            if (playerIDs.size() >= 32) { file.count("untrackedPlayerObservations"); return; }
            id = ++nextPlayerID;
            playerIDs.put(player, id);
        }
        file.player("player-" + id, "phase=" + phase + " dim=" + player.dimension
                + " xyz=" + player.posX + "," + player.posY + "," + player.posZ
                + " yaw=" + player.rotationYaw + " pitch=" + player.rotationPitch);
    }

    public void stopping(MinecraftServer server)
    {
        if (!enabled() || file == null || server == null || server.getPlayerList() == null) return;
        for (EntityPlayer player : server.getPlayerList().getPlayers()) playerLocation(player, "server-stopping");
    }

    private static String sha256(File file) throws Exception
    {
        if (file == null || !file.isFile()) return "unavailable-not-a-file";
        MessageDigest digest = MessageDigest.getInstance("SHA-256");
        try (FileInputStream stream = new FileInputStream(file))
        {
            byte[] buffer = new byte[8192];
            for (int read; (read = stream.read(buffer)) != -1;) digest.update(buffer, 0, read);
        }
        StringBuilder hex = new StringBuilder();
        for (byte b : digest.digest()) hex.append(String.format(Locale.ROOT, "%02x", b & 255));
        return hex.toString();
    }

    @SubscribeEvent(priority = EventPriority.HIGHEST)
    public void populationPre(PopulateChunkEvent.Pre event)
    {
        if (!enabled() || event.getWorld().isRemote || !settings.dimensionMatches(event.getWorld().provider.getDimension())) return;
        Deque<Population> stack = populations.get();
        if (stack.size() >= 32) { emit("POPULATION_DEPTH_LIMIT", "context reset; coverage incomplete"); stack.clear(); }
        stack.push(new Population(event.getWorld(), event.getChunkX(), event.getChunkZ()));
        RCGenerationDiagnosticFile output = file;
        if (output != null) output.count("populationPre");
        else if (settings.contains(event.getChunkX(), event.getChunkZ()))
            emit("POP_PRE", "dim=" + settings.dimension + " population=" + population() + " depth=" + stack.size());
    }

    @SubscribeEvent(priority = EventPriority.LOWEST)
    public void populationPost(PopulateChunkEvent.Post event)
    {
        if (!enabled() || event.getWorld().isRemote || !settings.dimensionMatches(event.getWorld().provider.getDimension())) return;
        RCGenerationDiagnosticFile output = file;
        if (output != null) output.count("populationPost");
        else if (settings.contains(event.getChunkX(), event.getChunkZ()))
            emit("POP_POST", "dim=" + settings.dimension + " chunk=" + event.getChunkX() + "," + event.getChunkZ());
        List<Watch> snapshot;
        synchronized (this) { snapshot = new ArrayList<>(watches.values()); }
        for (Watch watch : snapshot)
        {
            if (watch.world != event.getWorld() || !watch.relevant(event.getChunkX(), event.getChunkZ())) continue;
            boolean expired;
            synchronized (this)
            {
                if (!watches.containsKey(watch.id)) continue;
                expired = ++watch.posts >= MAX_POSTS;
                if (expired) watches.remove(watch.id);
            }
            observe(watch, "population-end=" + event.getChunkX() + "," + event.getChunkZ() + " original=" + watch.population);
            if (expired) emit(watch.evidence, false, "WATCH_EXPIRED", "watch=" + watch.id + " coverage=ended post-limit");
        }
        Deque<Population> stack = populations.get();
        Population top = stack.peek();
        if (top != null && top.world == event.getWorld() && top.x == event.getChunkX() && top.z == event.getChunkZ()) stack.pop();
        else { emit("POPULATION_UNPAIRED", "context reset"); stack.clear(); }
    }

    @SubscribeEvent
    public synchronized void worldUnload(WorldEvent.Unload event)
    {
        if (enabled() && !event.getWorld().isRemote && event.getWorld().playerEntities != null)
            for (EntityPlayer player : event.getWorld().playerEntities) playerLocation(player, "world-unload");
        watches.values().removeIf(w -> {
            if (w.world != event.getWorld()) return false;
            emit(w.evidence, false, "WATCH_UNLOAD", "watch=" + w.id + " coverage=ended");
            return true;
        });
        dataWorlds.values().removeIf(w -> w == event.getWorld());
        populations.get().removeIf(p -> p.world == event.getWorld());
    }

    private boolean selected(Scope scope) { return enabled() && scope != null && scope.selected; }
    private String traceID() { Scope scope = current.get(); return scope == null ? "none" : String.valueOf(scope.id); }
    private String tag(Scope scope) { return "trace=" + scope.id + " dim=" + scope.world.provider.getDimension() + " structure=" + scope.structure; }
    private String population() { Population p = populations.get().peek(); return p == null ? "none" : p.x + "," + p.z; }

    public static final class Scope
    {
        final Scope previous;
        final WorldServer world;
        final String structure;
        final List<BlockPos> points = new ArrayList<>();
        final Set<Long> unavailable = new HashSet<>();
        boolean selected, initial;
        RCGenerationDiagnosticFile.Evidence evidence;
        long id;
        int samples, details;
        Scope(Scope previous, WorldServer world, String structure) { this.previous = previous; this.world = world; this.structure = structure; }
    }

    public static final class Watch
    {
        final long id;
        final WorldServer world;
        final String population;
        final RCGenerationDiagnosticFile.Evidence evidence;
        final boolean initial;
        final Set<BlockPos> points = new LinkedHashSet<>();
        int posts;
        Watch(long id, Scope scope, String population)
        { this.id = id; this.world = scope.world; this.population = population; this.evidence = scope.evidence; this.initial = scope.initial; }
        boolean relevant(int x, int z)
        {
            return points.stream().anyMatch(p -> Math.abs((long) (p.getX() >> 4) - x) <= 1 && Math.abs((long) (p.getZ() >> 4) - z) <= 1);
        }
    }

    private static final class Population
    {
        final World world;
        final int x, z;
        Population(World world, int x, int z) { this.world = world; this.x = x; this.z = z; }
    }

    static final class Settings
    {
        boolean enabled, allArea, allDimensions;
        String area = "", structure = "", error;
        int dimension, minX, minZ, maxX, maxZ, maxRecords = 10000;
        static Settings readFile(File file)
        {
            Properties p = new Properties();
            if (!file.isFile()) return read(p);
            try (InputStream stream = new FileInputStream(file))
            {
                if (file.length() > 16384) throw new IllegalArgumentException("diagnostic config too large");
                p.load(stream);
                return read(p);
            }
            catch (Exception e)
            {
                Settings s = new Settings();
                s.error = "diagnostic config unreadable: " + e.getClass().getSimpleName();
                return s;
            }
        }
        static Settings read(Properties properties)
        {
            Settings s = new Settings();
            if (!Boolean.parseBoolean(properties.getProperty("enabled", properties.getProperty("reccomplex.trace", "false")))) return s;
            try
            {
                s.area = properties.getProperty("reccomplex.trace.area", "");
                s.allArea = s.area.isEmpty();
                if (!s.allArea)
                {
                    String[] parts = s.area.split(",");
                    if (parts.length != 4) throw new IllegalArgumentException("set reccomplex.trace.area=minChunkX,minChunkZ,maxChunkX,maxChunkZ");
                    s.minX = Integer.parseInt(parts[0]); s.minZ = Integer.parseInt(parts[1]);
                    s.maxX = Integer.parseInt(parts[2]); s.maxZ = Integer.parseInt(parts[3]);
                    if (s.maxX < s.minX || s.maxZ < s.minZ || (long) s.maxX - s.minX >= 64 || (long) s.maxZ - s.minZ >= 64
                            || Math.abs((long) s.minX) > 1874999 || Math.abs((long) s.maxX) > 1874999
                            || Math.abs((long) s.minZ) > 1874999 || Math.abs((long) s.maxZ) > 1874999)
                        throw new IllegalArgumentException("area must be within world bounds and at most 64x64 chunks");
                }
                s.allDimensions = !properties.containsKey("reccomplex.trace.dimension");
                s.dimension = Integer.parseInt(properties.getProperty("reccomplex.trace.dimension", "0"));
                s.structure = properties.getProperty("reccomplex.trace.structure", "");
                s.maxRecords = Math.max(1, Math.min(50000, Integer.parseInt(properties.getProperty("reccomplex.trace.maxRecords", "10000"))));
                s.enabled = true;
            }
            catch (RuntimeException e) { s.error = "diagnostics disabled: " + e.getMessage(); }
            return s;
        }
        boolean idMatches(String id) { return structure.isEmpty() || structure.equals(id); }
        boolean dimensionMatches(int id) { return allDimensions || id == dimension; }
        boolean contains(int x, int z) { return allArea || x >= minX && x <= maxX && z >= minZ && z <= maxZ; }
        boolean intersects(StructureBoundingBox box)
        {
            return allArea || box != null && (box.maxX >> 4) >= minX && (box.minX >> 4) <= maxX
                    && (box.maxZ >> 4) >= minZ && (box.minZ >> 4) <= maxZ;
        }
    }
}
