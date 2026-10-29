package ivorius.reccomplex.world.gen.feature.structure.generic.maze;

import ivorius.ivtoolkit.math.AxisAlignedTransform2D;
import ivorius.reccomplex.RCConfig;
import ivorius.reccomplex.RecurrentComplex;
import ivorius.reccomplex.world.gen.feature.structure.Environment;
import ivorius.reccomplex.world.gen.feature.structure.Structure;
import ivorius.reccomplex.world.gen.feature.structure.StructureRegistry;
import ivorius.reccomplex.world.gen.feature.structure.VariableDomain;
import ivorius.reccomplex.world.gen.feature.structure.generic.GenericStructure;
import ivorius.reccomplex.world.gen.feature.structure.generic.generation.MazeGeneration;
import ivorius.reccomplex.world.gen.feature.structure.generic.generation.NaturalGeneration;
import ivorius.reccomplex.world.gen.script.WorldScriptMazeGenerator;
import net.minecraft.nbt.*;
import net.minecraft.server.MinecraftServer;
import net.minecraft.world.WorldServer;
import net.minecraft.world.biome.Biome;
import net.minecraftforge.fml.common.registry.ForgeRegistries;

import java.util.*;
import java.util.concurrent.TimeUnit;

/** Main-server-thread discovery and context resolution. Workers receive only detached requests. */
public final class MazeLayoutWarmup
{
    private static MinecraftServer server;
    private static Session session;
    private static volatile boolean dirty;
    private static long lastStartupMillis;
    private static int lastStartupKeys, lastStartupReady;

    private MazeLayoutWarmup() {}

    /** Called inside ServerStarted, before FMLCommonHandler allows player logins. */
    public static void start(MinecraftServer current)
    {
        server = current;
        session = null;
        dirty = false;
        lastStartupMillis = 0; lastStartupKeys = 0; lastStartupReady = 0;
        if (!RCConfig.mazeLayoutQueue || current == null) return;
        long start = System.nanoTime();
        long budget = TimeUnit.MILLISECONDS.toNanos(Math.max(0, RCConfig.mazeLayoutWarmupMillis));
        // A zero budget defers even discovery to server ticks.
        if (budget == 0) { dirty = true; return; }
        session = discoverSession(current, start + budget);
        while (System.nanoTime() - start < budget)
        {
            if (session != null && session.hasNext()) session.step();
            else if (FreshMazeLayouts.firstLayoutsReady()) break;
            else
            {
                long remaining = TimeUnit.NANOSECONDS.toMillis(budget - (System.nanoTime() - start));
                try { FreshMazeLayouts.awaitProgress(Math.min(50, remaining)); }
                catch (InterruptedException e) { Thread.currentThread().interrupt(); break; }
            }
        }
        if (session != null && !session.hasNext()) { session.finish(); session = null; }
        lastStartupMillis = TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - start);
        long[] stats = FreshMazeLayouts.statistics();
        lastStartupKeys = (int) stats[4]; lastStartupReady = (int) stats[5];
        if (RecurrentComplex.logger != null)
            RecurrentComplex.logger.info("Maze startup warmup: {} ms, {} configurations, {} ready layouts, {} failures, {} produced (budget {} ms)",
                    lastStartupMillis, lastStartupKeys, lastStartupReady, stats[2], stats[3], RCConfig.mazeLayoutWarmupMillis);
    }

    /** Reloads schedule discovery on the next server tick; no deliberate gameplay wait. */
    public static void invalidate() { dirty = true; }

    public static void tick()
    {
        if (server == null || !RCConfig.mazeLayoutQueue) return;
        if (dirty)
        {
            dirty = false;
            session = discoverSession(server, System.nanoTime() + TimeUnit.MILLISECONDS.toNanos(50));
        }
        if (session == null) return;
        // At most one resolved context per tick. Solving always stays on the worker.
        if (session.hasNext()) session.step();
        if (!session.hasNext()) { session.finish(); session = null; }
    }

    public static void stop()
    {
        server = null; session = null; dirty = false;
    }

    public static long[] startupStatistics()
    {
        return new long[]{lastStartupMillis, lastStartupKeys, lastStartupReady};
    }

    private static Session discoverSession(MinecraftServer current, long deadline)
    {
        List<WorldServer> worlds = new ArrayList<>();
        if (current.worlds != null) for (WorldServer world : current.worlds) if (world != null) worlds.add(world);
        return new Session(discover(StructureRegistry.INSTANCE.allActive(), worlds,
                ForgeRegistries.BIOMES.getValuesCollection(), deadline));
    }

    static List<Candidate> discover(Collection<Structure<?>> structures, Collection<WorldServer> worlds, Collection<Biome> biomes)
    {
        return discover(structures, worlds, biomes, Long.MAX_VALUE).candidates;
    }

    static Discovery discover(Collection<Structure<?>> structures, Collection<WorldServer> worlds, Collection<Biome> biomes, long deadline)
    {
        Discovery discovery = new Discovery();
        List<Candidate> candidates = discovery.candidates;
        int evaluated = 0;
        discovery:
        for (Structure<?> structure : structures)
        {
            if (discovery.inspected >= 2048 || discovery.definitions >= 128 || System.nanoTime() >= deadline)
            { discovery.truncated = true; break; }
            discovery.inspected++;
            if (!(structure instanceof GenericStructure)) continue;
            GenericStructure generic = (GenericStructure) structure;
            List<NaturalGeneration> generations = generic.generationTypes(NaturalGeneration.class);
            if (generations.isEmpty() || generic.worldDataCompound == null) continue;
            List<WorldScriptMazeGenerator> scripts = scripts(generic.worldDataCompound);
            if (scripts.isEmpty()) continue;
            discovery.definitions++;
            for (WorldServer world : worlds)
            {
                Biome spawnBiome = world.getBiome(world.getSpawnPoint());
                List<Biome> orderedBiomes = new ArrayList<>(biomes);
                if (spawnBiome != null && !orderedBiomes.contains(spawnBiome)) orderedBiomes.add(spawnBiome);
                for (NaturalGeneration generation : generations) for (Biome biome : orderedBiomes)
                {
                    if (++evaluated > 16384 || candidates.size() >= 8192 || System.nanoTime() >= deadline)
                    { discovery.truncated = true; break discovery; }
                    double weight;
                    try { weight = generation.getGenerationWeight(world.provider, biome); }
                    catch (RuntimeException e) { continue; }
                    if (!(weight > 0) || !Double.isFinite(weight)) continue;
                    for (WorldScriptMazeGenerator script : scripts)
                    {
                        int rotations = generic.isRotatable() ? 4 : 1;
                        int mirrors = generic.isMirrorable() ? 2 : 1;
                        for (int m = 0; m < mirrors; m++) for (int r = 0; r < rotations; r++)
                        {
                            if (candidates.size() >= 8192) { discovery.truncated = true; break discovery; }
                            candidates.add(new Candidate(generic, generation, script, world, biome,
                                    AxisAlignedTransform2D.from(r, m != 0), biome == spawnBiome, weight, m * 4 + r));
                        }
                    }
                }
            }
        }
        candidates.sort(Comparator.comparing((Candidate c) -> !c.atSpawn)
                .thenComparingInt(c -> c.variant)
                .thenComparing(Comparator.comparingDouble((Candidate c) -> c.weight).reversed())
                .thenComparing(c -> Objects.toString(StructureRegistry.INSTANCE.id(c.parent), ""))
                .thenComparing(c -> Objects.toString(c.biome.getRegistryName(), "")));
        return discovery;
    }

    static final class Discovery
    {
        final List<Candidate> candidates = new ArrayList<>();
        int definitions, inspected;
        boolean truncated;
    }

    /** Scan only tile-entity script data, never the potentially huge block collection. */
    static List<WorldScriptMazeGenerator> scripts(NBTTagCompound worldData)
    {
        List<WorldScriptMazeGenerator> found = new ArrayList<>();
        NBTTagList tiles = worldData.getTagList("tileEntities", 10);
        int[] nodes = {0};
        for (int i = 0; i < tiles.tagCount() && found.size() < 64 && nodes[0] < 10000; i++)
        {
            NBTTagCompound tile = tiles.getCompoundTagAt(i);
            if (tile.hasKey("spawnTriggerable") && !tile.getBoolean("spawnTriggerable")) continue;
            scan(tile, found, 0, nodes);
        }
        return found;
    }

    private static void scan(NBTBase value, List<WorldScriptMazeGenerator> found, int depth, int[] nodes)
    {
        if (depth > 32 || ++nodes[0] > 10000 || found.size() >= 64) return;
        if (value instanceof NBTTagCompound)
        {
            NBTTagCompound tag = (NBTTagCompound) value;
            if (tag.hasKey("mazeID", 8) && tag.hasKey("roomSize", 11))
            {
                try
                {
                    WorldScriptMazeGenerator script = new WorldScriptMazeGenerator();
                    script.readFromNBT(tag.copy());
                    if (MazeLayoutRequest.supports(script)) found.add(script);
                }
                catch (RuntimeException e) { /* Malformed/custom definitions retain the normal fallback. */ }
                return;
            }
            for (String key : tag.getKeySet())
                if (!key.equals("blockCollection") && !key.equals("entities")) scan(tag.getTag(key), found, depth + 1, nodes);
        }
        else if (value instanceof NBTTagList)
        {
            NBTTagList list = (NBTTagList) value;
            if (list.getTagType() == 10 || list.getTagType() == 9)
                for (int i = 0; i < list.tagCount(); i++) scan(list.get(i), found, depth + 1, nodes);
        }
    }

    /** Conservative limits keep speculative context compilation from expanding custom definitions excessively. */
    private static boolean reasonable(WorldScriptMazeGenerator script)
    {
        if (script.mazeComponent.rooms.isEmpty()) return false;
        long volume = 1;
        int[] low = script.mazeComponent.rooms.boundsLower(), high = script.mazeComponent.rooms.boundsHigher();
        if (low == null || high == null) return false;
        for (int i = 0; i < low.length; i++)
        {
            long width = (long) high[i] - low[i] + 1;
            if (width < 1 || width > 4096 || volume > 4096 / width) return false;
            volume *= width;
        }
        long variants = 0;
        for (org.apache.commons.lang3.tuple.Pair<Structure<?>, MazeGeneration> pair :
                (Iterable<org.apache.commons.lang3.tuple.Pair<Structure<?>, MazeGeneration>>) () -> MazeGeneration.structures(StructureRegistry.INSTANCE, script.mazeID).iterator())
        {
            long branches = pair.getLeft().declaredVariables().variables().stream()
                    .filter(v -> v.affectsLogic && v.chance > 0 && v.chance < 1).count();
            if (branches > 6) return false;
            variants += (1L << branches) * 8;
            if (variants > 1024) return false;
        }
        return variants > 0;
    }

    static final class Candidate
    {
        final GenericStructure parent;
        final NaturalGeneration generation;
        final WorldScriptMazeGenerator script;
        final WorldServer world;
        final Biome biome;
        final AxisAlignedTransform2D transform;
        final boolean atSpawn;
        final double weight;
        final int variant;

        Candidate(GenericStructure parent, NaturalGeneration generation, WorldScriptMazeGenerator script, WorldServer world,
                  Biome biome, AxisAlignedTransform2D transform, boolean atSpawn, double weight, int variant)
        {
            this.parent = parent; this.generation = generation; this.script = script; this.world = world;
            this.biome = biome; this.transform = transform; this.atSpawn = atSpawn; this.weight = weight; this.variant = variant;
        }

        MazeLayoutRequest request()
        {
            if (parent.variableDomain.variables().size() > 64 || !reasonable(script)) return null;
            Environment environment = new Environment(world, biome, null, generation);
            // Representative parent-variable assignment, independent of worldgen Random.
            parent.variableDomain.fill(new VariableDomain(), environment, new Random(0x52435741524dL));
            return script.createLayoutRequest(transform, environment);
        }
    }

    static final class Session
    {
        final Discovery discovery;
        final Iterator<Candidate> pending;
        final Set<MazeLayoutRequest> admitted = new HashSet<>();
        final Random seeds = new Random();
        boolean finished;
        int attempts;
        final int capacity = RCConfig.mazeLayoutQueueMaxConfigurations;

        Session(Discovery discovery) { this.discovery = discovery; this.pending = discovery.candidates.iterator(); }
        boolean hasNext()
        {
            return !finished && attempts < 128 && (capacity == 0 || admitted.size() < capacity) && pending.hasNext();
        }

        void step()
        {
            Candidate candidate = pending.next();
            attempts++;
            try
            {
                MazeLayoutRequest request = candidate.request();
                if (request != null && !admitted.contains(request) && FreshMazeLayouts.prime(request, seeds.nextLong()))
                {
                    admitted.add(request);
                    if (RecurrentComplex.logger != null)
                        RecurrentComplex.logger.debug("Primed maze {} from {} in biome {} dimension {}", candidate.script.mazeID,
                                candidate.parent, candidate.biome.getRegistryName(), candidate.world.provider.getDimension());
                }
            }
            catch (RuntimeException e)
            {
                if (RecurrentComplex.logger != null)
                    RecurrentComplex.logger.debug("Skipping speculative maze warmup for {}", candidate.script.mazeID, e);
            }
        }

        void finish()
        {
            if (!finished)
            {
                FreshMazeLayouts.finishPriming();
                finished = true;
                if (RecurrentComplex.logger != null)
                    RecurrentComplex.logger.info("Maze layout discovery: {} supported maze-bearing natural structure definitions found in {} inspected structures; "
                                    + "{} configurations admitted; discovery truncated={}; preparation limited={}. "
                                    + "These are bounded discovery counts, not all possible configurations.",
                            discovery.definitions, discovery.inspected, admitted.size(), discovery.truncated, pending.hasNext());
            }
        }
    }
}
