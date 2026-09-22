package ivorius.reccomplex.world.gen.feature;

import ivorius.reccomplex.RCConfig;
import ivorius.reccomplex.RecurrentComplex;
import ivorius.reccomplex.world.gen.feature.structure.context.StructureSpawnContext;
import net.minecraft.nbt.NBTTagCompound;
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

import java.io.File;
import java.io.FileInputStream;
import java.lang.reflect.Field;
import java.lang.reflect.Modifier;
import java.security.MessageDigest;
import java.util.*;
import java.util.function.Consumer;
import java.util.function.Supplier;

/** Temporary, opt-in worldgen observations. Never provides chunks or consults a worldgen RNG. */
public final class RCGenerationDiagnostics
{
    static RCGenerationDiagnostics active = new RCGenerationDiagnostics(Settings.read(System.getProperties()),
            line -> RecurrentComplex.logger.info(line));

    public static RCGenerationDiagnostics get() { return active; }

    static final int MAX_WATCHES = 64, MAX_POINTS = 5, MAX_POSTS = 16, MAX_SAMPLES = 16;
    final Settings settings;
    private final Consumer<String> sink;
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

    public boolean enabled() { return settings.enabled && !stopped; }
    public boolean recording() { return enabled() && selected(current.get()); }

    public void register()
    {
        if (settings.error != null) emit("CONFIG_ERROR", settings.error);
        if (enabled()) MinecraftForge.EVENT_BUS.register(this);
    }

    // This is a diagnostic lock only; no worldgen or saved-data locks are acquired here.
    synchronized void emit(String event, String detail)
    {
        if (stopped) return;
        try
        {
            if (records++ >= settings.maxRecords)
            {
                sink.accept("[RC-DIAG] TRUNCATED record limit reached; further observations disabled until restart");
                stopped = true;
                return;
            }
            sink.accept("[RC-DIAG] " + event + " thread=" + clean(Thread.currentThread().getName()) + " " + clean(detail));
        }
        catch (RuntimeException ignored) { stopped = true; } // Logging must not abort generation.
    }

    private static String clean(Object value)
    {
        String text = String.valueOf(value).replace('\n', ' ').replace('\r', ' ');
        return text.length() > 3500 ? text.substring(0, 3500) + " [text truncated]" : text;
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
                    || name.startsWith("com.pg85.otg.")))
            {
                result.add(name + "." + frame.getMethodName());
                if (++count == 14) break;
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
        scope.selected = enabled() && world != null && settings.dimension == world.provider.getDimension()
                && settings.idMatches(id) && (area != null ? settings.intersects(area)
                : anchor != null && settings.contains(anchor.getX() >> 4, anchor.getZ() >> 4));
        if (scope.selected)
        {
            synchronized (this) { scope.id = ++nextID; }
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
        String outcome = error != null ? "threw " + error.getClass().getName() + ":" + error.getMessage()
                : result instanceof StructureGenerator.GenerationResult.Failure
                ? "failure=" + ((StructureGenerator.GenerationResult.Failure) result).description
                : "success=" + (result != null && result.succeeded());
        if (result instanceof StructureGenerator.GenerationResult.Failure.Exception)
            outcome += " exception=" + ((StructureGenerator.GenerationResult.Failure.Exception) result).exception;
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
                watches.remove(oldest);
                emit("WATCH_EVICTED", "watch=" + oldest + " coverage=lost");
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
        emit("WRITE_RESULT", "watch=" + watch.id + " success=" + succeeded);
        observe(watch, "after-write");
    }

    void observe(Watch watch, String phase)
    {
        if (!enabled()) return;
        for (BlockPos point : watch.points)
        {
            if (!enabled()) return;
            try
            {
                // getLoadedChunk is the ONLY chunk lookup made by diagnostics. Never use world.getBlockState/height.
                Chunk chunk = watch.world.getChunkProvider().getLoadedChunk(point.getX() >> 4, point.getZ() >> 4);
                if (chunk == null)
                {
                    emit("SUPPORT", "watch=" + watch.id + " phase=" + phase + " pos=" + point + " UNAVAILABLE");
                    continue;
                }
                StringJoiner states = new StringJoiner(",");
                for (int y = Math.max(0, point.getY() - 4); y <= Math.min(255, point.getY() + 4); y++)
                    states.add(y + ":" + chunk.getBlockState(new BlockPos(point.getX(), y, point.getZ())));
                emit("SUPPORT", "watch=" + watch.id + " phase=" + phase + " pos=" + point
                        + " terrainPopulated=" + chunk.isTerrainPopulated() + " window=" + states);
            }
            catch (RuntimeException e) { emit("OBSERVATION_ERROR", "watch=" + watch.id + " phase=" + phase + " " + e); }
        }
    }

    public void completionSkip(WorldServer world, WorldStructureGenerationData.StructureEntry entry, int x, int z, String reason)
    {
        if (enabled() && world.provider.getDimension() == settings.dimension && settings.contains(x, z)
                && settings.idMatches(entry.getStructureID()))
            emit("COMPLETION_SKIP", "dim=" + settings.dimension + " chunk=" + x + "," + z + " " + entry(entry)
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
        return enabled() && world != null && !world.isRemote && world.provider.getDimension() == settings.dimension;
    }

    public void entryEvent(String event, WorldStructureGenerationData data, WorldStructureGenerationData.Entry entry)
    {
        if (!enabled()) return;
        if (!matches(data) || !settings.intersects(entry.getBoundingBox())) return;
        if (entry instanceof WorldStructureGenerationData.StructureEntry
                && !settings.idMatches(((WorldStructureGenerationData.StructureEntry) entry).getStructureID())) return;
        if (!(entry instanceof WorldStructureGenerationData.StructureEntry) && !settings.structure.isEmpty()) return;
        emit(event, "dim=" + settings.dimension + " " + entry(entry) + " trace=" + traceID()
                + " caller=" + callers());
    }

    public void dataRead(WorldStructureGenerationData data, NBTTagCompound nbt, boolean before)
    {
        if (!enabled()) return;
        if (!matches(data)) return;
        emit(before ? "DATA_LOAD_BEGIN" : "DATA_LOAD_END", "dim=" + settings.dimension
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
            emit("CHUNK_CHECK", "dim=" + settings.dimension + " chunk=" + x + "," + z + " final=" + finalPass
                    + " newlyChecked=" + added + " caller=" + callers());
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
        if (!enabled() || world.isRemote || world.provider.getDimension() != settings.dimension) return;
        emit("SESSION", "format=1 dim=" + settings.dimension + " area=" + settings.area + " structure=" + settings.structure
                + " maxRecords=" + settings.maxRecords + " maxWatches=" + MAX_WATCHES + " maxPostsPerWatch=" + MAX_POSTS
                + " java=" + System.getProperty("java.version"));
        for (String id : Arrays.asList("reccomplex", "rltweaker", "openterraingenerator", "ivtoolkit", "forge"))
        {
            try
            {
                ModContainer mod = Loader.instance().getIndexedModList().get(id);
                emit("MOD", id + (mod == null ? " absent" : " version=" + mod.getVersion() + " source=" + mod.getSource()
                        + " sha256=" + sha256(mod.getSource())));
            }
            catch (Exception e) { emit("MOD", id + " unavailable=" + e); }
        }
        fields("RC", RCConfig.class, null);
        try
        {
            Class<?> config = Class.forName("com.charles445.rltweaker.config.ModConfig", false, getClass().getClassLoader());
            Object server = config.getField("server").get(null);
            Object recurrent = server.getClass().getField("recurrentcomplex").get(server);
            fields("RLTweaker.RC", recurrent.getClass(), recurrent);
        }
        catch (Exception e) { emit("SETTINGS", "RLTweaker.RC unavailable=" + e); }
    }

    private void fields(String label, Class<?> type, Object instance)
    {
        for (Field field : type.getFields())
        {
            if (instance == null && !Modifier.isStatic(field.getModifiers())) continue;
            if (!(field.getType().isPrimitive() || field.getType() == String.class)) continue;
            try { emit("SETTINGS", label + "." + field.getName() + "=" + field.get(instance)); }
            catch (Exception e) { emit("SETTINGS", label + "." + field.getName() + " unavailable=" + e); }
        }
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
        if (!enabled() || event.getWorld().isRemote || event.getWorld().provider.getDimension() != settings.dimension) return;
        Deque<Population> stack = populations.get();
        if (stack.size() >= 32) { emit("POPULATION_DEPTH_LIMIT", "context reset; coverage incomplete"); stack.clear(); }
        stack.push(new Population(event.getWorld(), event.getChunkX(), event.getChunkZ()));
        if (settings.contains(event.getChunkX(), event.getChunkZ()))
            emit("POP_PRE", "dim=" + settings.dimension + " population=" + population() + " depth=" + stack.size());
    }

    @SubscribeEvent(priority = EventPriority.LOWEST)
    public void populationPost(PopulateChunkEvent.Post event)
    {
        if (!enabled() || event.getWorld().isRemote || event.getWorld().provider.getDimension() != settings.dimension) return;
        if (settings.contains(event.getChunkX(), event.getChunkZ()))
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
            if (expired) emit("WATCH_EXPIRED", "watch=" + watch.id + " coverage=ended post-limit");
        }
        Deque<Population> stack = populations.get();
        Population top = stack.peek();
        if (top != null && top.world == event.getWorld() && top.x == event.getChunkX() && top.z == event.getChunkZ()) stack.pop();
        else { emit("POPULATION_UNPAIRED", "context reset"); stack.clear(); }
    }

    @SubscribeEvent
    public synchronized void worldUnload(WorldEvent.Unload event)
    {
        watches.values().removeIf(w -> {
            if (w.world != event.getWorld()) return false;
            emit("WATCH_UNLOAD", "watch=" + w.id + " coverage=ended");
            return true;
        });
        dataWorlds.values().removeIf(w -> w == event.getWorld());
        populations.get().removeIf(p -> p.world == event.getWorld());
    }

    private boolean selected(Scope scope) { return enabled() && scope != null && scope.selected; }
    private String traceID() { Scope scope = current.get(); return scope == null ? "none" : String.valueOf(scope.id); }
    private String tag(Scope scope) { return "trace=" + scope.id + " dim=" + settings.dimension + " structure=" + scope.structure; }
    private String population() { Population p = populations.get().peek(); return p == null ? "none" : p.x + "," + p.z; }

    public static final class Scope
    {
        final Scope previous;
        final WorldServer world;
        final String structure;
        final List<BlockPos> points = new ArrayList<>();
        final Set<Long> unavailable = new HashSet<>();
        boolean selected;
        long id;
        int samples, details;
        Scope(Scope previous, WorldServer world, String structure) { this.previous = previous; this.world = world; this.structure = structure; }
    }

    public static final class Watch
    {
        final long id;
        final WorldServer world;
        final String population;
        final Set<BlockPos> points = new LinkedHashSet<>();
        int posts;
        Watch(long id, Scope scope, String population) { this.id = id; this.world = scope.world; this.population = population; }
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
        boolean enabled;
        String area = "", structure = "", error;
        int dimension, minX, minZ, maxX, maxZ, maxRecords = 10000;
        static Settings read(Properties properties)
        {
            Settings s = new Settings();
            if (!Boolean.parseBoolean(properties.getProperty("reccomplex.trace", "false"))) return s;
            try
            {
                s.area = properties.getProperty("reccomplex.trace.area", "");
                String[] parts = s.area.split(",");
                if (parts.length != 4) throw new IllegalArgumentException("set reccomplex.trace.area=minChunkX,minChunkZ,maxChunkX,maxChunkZ");
                s.minX = Integer.parseInt(parts[0]); s.minZ = Integer.parseInt(parts[1]);
                s.maxX = Integer.parseInt(parts[2]); s.maxZ = Integer.parseInt(parts[3]);
                if (s.maxX < s.minX || s.maxZ < s.minZ || (long) s.maxX - s.minX >= 64 || (long) s.maxZ - s.minZ >= 64
                        || Math.abs((long) s.minX) > 1874999 || Math.abs((long) s.maxX) > 1874999
                        || Math.abs((long) s.minZ) > 1874999 || Math.abs((long) s.maxZ) > 1874999)
                    throw new IllegalArgumentException("area must be within world bounds and at most 64x64 chunks");
                s.dimension = Integer.parseInt(properties.getProperty("reccomplex.trace.dimension", "0"));
                s.structure = properties.getProperty("reccomplex.trace.structure", "");
                s.maxRecords = Math.max(1, Math.min(50000, Integer.parseInt(properties.getProperty("reccomplex.trace.maxRecords", "10000"))));
                s.enabled = true;
            }
            catch (RuntimeException e) { s.error = "diagnostics disabled: " + e.getMessage(); }
            return s;
        }
        boolean idMatches(String id) { return structure.isEmpty() || structure.equals(id); }
        boolean contains(int x, int z) { return x >= minX && x <= maxX && z >= minZ && z <= maxZ; }
        boolean intersects(StructureBoundingBox box)
        {
            return box != null && (box.maxX >> 4) >= minX && (box.minX >> 4) <= maxX
                    && (box.maxZ >> 4) >= minZ && (box.minZ >> 4) <= maxZ;
        }
    }
}
