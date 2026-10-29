package ivorius.reccomplex.world.gen.feature.structure.generic.maze;

import ivorius.ivtoolkit.blocks.BlockSurfacePos;
import ivorius.reccomplex.RCConfig;
import ivorius.reccomplex.world.gen.feature.StructureGenerator;
import ivorius.reccomplex.world.gen.feature.structure.generic.GenericStructure;
import ivorius.reccomplex.world.gen.feature.structure.StructureRegistry;
import ivorius.reccomplex.world.gen.feature.structure.generic.generation.NaturalGeneration;
import net.minecraft.init.Blocks;
import net.minecraft.server.MinecraftServer;
import net.minecraft.util.math.BlockPos;
import net.minecraft.util.math.ChunkPos;
import net.minecraft.world.WorldServer;
import net.minecraft.world.gen.structure.StructureBoundingBox;
import net.minecraftforge.common.MinecraftForge;
import net.minecraftforge.fml.common.Mod;
import net.minecraftforge.fml.common.FMLCommonHandler;
import net.minecraftforge.fml.common.event.FMLServerStartedEvent;
import net.minecraftforge.fml.common.event.FMLServerAboutToStartEvent;
import net.minecraftforge.fml.common.event.FMLServerStoppedEvent;
import net.minecraftforge.fml.common.eventhandler.EventPriority;
import net.minecraftforge.fml.common.eventhandler.SubscribeEvent;
import net.minecraftforge.fml.common.gameevent.TickEvent;

import java.io.PrintWriter;
import java.nio.file.*;
import java.util.*;

/** Optional test mod, loaded only by tools/explore-maze-layouts.gradle.
 * Scripted encounter frequency, not a sample of vanilla/pack spawn frequency. */
@Mod(modid="mazeexploration", name="Maze exploration experiment", version="1", dependencies="required-after:reccomplex")
public class MazeExplorationMod
{
    private MinecraftServer server;
    private PrintWriter encounters, ticks;
    private int phase = -1, sample, tick;
    private long tickStart, nextEncounter;
    private long readyAt;
    private final int count = Integer.getInteger("maze.encounters", 6);
    private final String[] phases = "all".equals(System.getProperty("maze.scenario", "all"))
            ? new String[]{"baseline-burst", "queue-burst", "queue-spaced"}
            : new String[]{System.getProperty("maze.scenario")};

    @Mod.EventHandler public void aboutToStart(FMLServerAboutToStartEvent event) throws Exception
    {
        if (Boolean.getBoolean("maze.exploration"))
            Files.write(Paths.get("server-jvm-id.txt"), Collections.singletonList(java.lang.management.ManagementFactory.getRuntimeMXBean().getName()));
    }

    @Mod.EventHandler public void started(FMLServerStartedEvent event) throws Exception
    {
        if (!Boolean.getBoolean("maze.exploration")) return;
        server = FMLCommonHandler.instance().getMinecraftServerInstance();
        WorldServer world = server.getWorld(0);
        world.getGameRules().setOrCreateGameRule("doMobSpawning", "false");
        world.getGameRules().setOrCreateGameRule("doDaylightCycle", "false");
        world.getGameRules().setOrCreateGameRule("randomTickSpeed", "0");
        encounters = new PrintWriter(Files.newBufferedWriter(Paths.get("encounters.csv")));
        ticks = new PrintWriter(Files.newBufferedWriter(Paths.get("ticks.csv")));
        encounters.println("phase,sample,seed,totalMs,prepareMs,initialPlacementMs,exploreMs,queueHits,queueMisses,queueFailures,queueProduced,queueKeys,queueReady,chunkVisits,sandstoneBlocks,status,startAfterReadyMs");
        ticks.println("tick,phase,sample,elapsedMs");
        MinecraftForge.EVENT_BUS.register(this);
        readyAt = System.nanoTime();
        long[] warmup = MazeLayoutWarmup.startupStatistics();
        Files.write(Paths.get("startup-warmup.csv"), Arrays.asList("elapsedMs,configurations,readyLayouts", warmup[0]+","+warmup[1]+","+warmup[2]));
        System.out.println("MAZE-EXP WARMUP " + Arrays.toString(warmup));
        System.out.println("MAZE-EXP CONFIG queue=" + RCConfig.mazeLayoutQueue + " depth=" + RCConfig.mazeLayoutQueueSize
                + " capacity=" + RCConfig.mazeLayoutQueueMaxConfigurations + " multiplier=" + RCConfig.mazeLayoutBackgroundTimeoutMultiplier
                + " startupWait=" + RCConfig.mazeLayoutWarmupMillis);
        System.out.println("MAZE-EXP START seed=" + world.getSeed() + " samplesPerPhase=" + count + " driver=partial-natural-placement-plus-chunk-exploration");
        advance();
    }

    private void advance()
    {
        phase++;
        sample = 0;
        if (phase == phases.length)
        {
            System.out.println("MAZE-EXP COMPLETE");
            encounters.close(); ticks.close();
            server.initiateShutdown();
            return;
        }
        boolean immediateWarmup = phase == 0 && phases[phase].startsWith("queue-warmup");
        boolean backgroundWarmup = phase == 0 && phases[phase].startsWith("queue-background");
        if (!immediateWarmup && !backgroundWarmup) FreshMazeLayouts.clear();
        RCConfig.mazeLayoutQueue = !phases[phase].startsWith("baseline");
        RCConfig.mazeLayoutQueueSize = 2;
        nextEncounter = System.nanoTime() + java.util.concurrent.TimeUnit.SECONDS.toNanos(backgroundWarmup ? 30 : immediateWarmup ? 0 : 2);
        System.out.println("MAZE-EXP PHASE " + phases[phase]);
    }

    @SubscribeEvent(priority=EventPriority.HIGHEST) public void startTick(TickEvent.ServerTickEvent event)
    {
        if (event.phase == TickEvent.Phase.START) tickStart = System.nanoTime();
    }

    @SubscribeEvent(priority=EventPriority.LOWEST) public void endTick(TickEvent.ServerTickEvent event)
    {
        if (event.phase != TickEvent.Phase.END || phase >= phases.length) return;
        String currentPhase = phases[phase];
        int currentSample = sample;
        try
        {
            boolean advanceAfterTick = false;
            if (System.nanoTime() >= nextEncounter)
            {
                encounter();
                sample++;
                nextEncounter = System.nanoTime() + java.util.concurrent.TimeUnit.SECONDS.toNanos(phases[phase].endsWith("spaced") ? 15 : 0);
                if (sample == count) advanceAfterTick = true;
            }
            ticks.printf(Locale.ROOT, "%d,%s,%d,%.3f%n", tick++, currentPhase, currentSample, (System.nanoTime()-tickStart)/1e6);
            ticks.flush();
            if (advanceAfterTick) advance();
        }
        catch (Throwable error)
        {
            System.out.println("MAZE-EXP FAILED " + error);
            error.printStackTrace();
            encounters.close(); ticks.close();
            phase = phases.length;
            server.initiateShutdown();
        }
    }

    private void encounter()
    {
        WorldServer world = server.getWorld(0);
        GenericStructure structure = (GenericStructure) StructureRegistry.INSTANCE.get("BigPyramid");
        if (structure == null) throw new IllegalStateException("BigPyramid did not load");
        NaturalGeneration natural = structure.generationTypes(NaturalGeneration.class).get(0);
        int x = 2048 + sample * 160, z = 2048 + phase * 2048;
        long seed = 7102700L + sample;
        long[] before = FreshMazeLayouts.statistics();
        if (sample == 0) System.out.println("MAZE-EXP BEFORE_FIRST_ENCOUNTER stats=" + Arrays.toString(before)
                + " layoutWorkers=" + layoutWorkers().size());
        if (!RCConfig.mazeLayoutQueue && (layoutWorkers().size() != 0 || !Arrays.equals(new long[7], before)))
            throw new IllegalStateException("Disabled queue created background state");
        long start = System.nanoTime();
        encounterStartAfterReadyMs = (start - readyAt) / 1e6;
        world.getChunkFromChunkCoords(x >> 4, z >> 4);
        StructureGenerator<GenericStructure.InstanceData> generator = new StructureGenerator<>(structure)
                .world(world).structureID("BigPyramid").generationInfo(natural).seed(seed).fromCenter(true)
                .randomPosition(BlockSurfacePos.from(new BlockPos(x, 0, z)), natural.placer)
                .partially(true, new ChunkPos(x >> 4, z >> 4));
        long prepareStart = System.nanoTime();
        Optional<GenericStructure.InstanceData> prepared = generator.instanceData();
        long preparedAt = System.nanoTime();
        if (!prepared.isPresent())
        {
            record("preparation-failed", seed, (preparedAt-start)/1e6, (preparedAt-prepareStart)/1e6, 0, 0, before, 0, 0);
            return;
        }
        generator.instanceData(prepared.get()); // Prepare once, as in the normal generation path.
        StructureGenerator.GenerationResult result = generator.generate();
        long placedAt = System.nanoTime();
        if (!result.succeeded())
        {
            record("placement-failed", seed, (placedAt-start)/1e6, (preparedAt-prepareStart)/1e6, (placedAt-preparedAt)/1e6, 0, before, 0, 0);
            return;
        }
        StructureBoundingBox box = generator.boundingBox().get();
        int loaded = 0;
        // Visit the structure footprint and a population halo, allowing normal RC completion events.
        for (int cx = (box.minX >> 4) - 2; cx <= (box.maxX >> 4) + 2; cx++)
            for (int cz = (box.minZ >> 4) - 2; cz <= (box.maxZ >> 4) + 2; cz++)
            { world.getChunkFromChunkCoords(cx, cz); loaded++; }
        long completedAt = System.nanoTime();
        int sandstone = 0;
        for (BlockPos pos : BlockPos.getAllInBox(new BlockPos(box.minX, box.minY, box.minZ), new BlockPos(box.maxX, box.maxY, box.maxZ)))
            if (world.getBlockState(pos).getBlock() == Blocks.SANDSTONE) sandstone++;
        if (sandstone < 100) throw new IllegalStateException("Pyramid block placement not observed: " + sandstone);
        record("success", seed, (completedAt-start)/1e6, (preparedAt-prepareStart)/1e6,
                (placedAt-preparedAt)/1e6, (completedAt-placedAt)/1e6, before, loaded, sandstone);
    }

    private void record(String status, long seed, double total, double prepare, double placement,
                        double explore, long[] before, int loaded, int sandstone)
    {
        long[] after = FreshMazeLayouts.statistics();
        encounters.printf(Locale.ROOT, "%s,%d,%d,%.3f,%.3f,%.3f,%.3f,%d,%d,%d,%d,%d,%d,%d,%d,%s,%.3f%n", phases[phase], sample, seed,
                total, prepare, placement, explore, after[0]-before[0], after[1]-before[1],
                after[2]-before[2], after[3]-before[3], after[4], after[5], loaded, sandstone, status, encounterStartAfterReadyMs);
        encounters.flush();
        System.out.printf(Locale.ROOT, "MAZE-EXP RESULT phase=%s sample=%d totalMs=%.3f prepareMs=%.3f hit=%d miss=%d sandstone=%d status=%s%n",
                phases[phase], sample, total, prepare, after[0]-before[0], after[1]-before[1], sandstone, status);
    }
    private double encounterStartAfterReadyMs;

    private List<Thread> layoutWorkers()
    {
        List<Thread> workers = new ArrayList<>();
        for (Thread thread : Thread.getAllStackTraces().keySet())
            if (thread.isAlive() && thread.getName().equals("RecurrentComplex-maze-layouts")) workers.add(thread);
        return workers;
    }

    @Mod.EventHandler public void stopped(FMLServerStoppedEvent event) throws InterruptedException
    {
        if (!Boolean.getBoolean("maze.exploration")) return;
        for (Thread worker : layoutWorkers()) worker.join(5000);
        int active = layoutWorkers().size();
        System.out.println("MAZE-EXP STOPPED layoutWorkers=" + active);
        if (active != 0) throw new IllegalStateException("Layout worker did not stop");
    }
}
