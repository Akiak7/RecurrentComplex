package ivorius.reccomplex.world.gen.feature;

import ivorius.ivtoolkit.blocks.BlockSurfacePos;
import ivorius.ivtoolkit.math.AxisAlignedTransform2D;
import ivorius.reccomplex.RCConfig;
import ivorius.reccomplex.RecurrentComplex;
import ivorius.reccomplex.nbt.NBTStorable;
import ivorius.reccomplex.world.gen.feature.structure.Environment;
import ivorius.reccomplex.world.gen.feature.structure.Placer;
import ivorius.reccomplex.world.gen.feature.structure.Structure;
import ivorius.reccomplex.world.gen.feature.structure.context.StructureSpawnContext;
import ivorius.reccomplex.world.gen.feature.structure.generic.placement.rays.RayAverageMatcher;
import ivorius.reccomplex.world.gen.feature.structure.generic.transformers.RunTransformer;
import ivorius.reccomplex.world.gen.feature.structure.generic.transformers.TransformerMulti;
import net.minecraft.init.Blocks;
import net.minecraft.init.Bootstrap;
import net.minecraft.nbt.NBTTagCompound;
import net.minecraft.util.math.BlockPos;
import net.minecraft.util.math.ChunkPos;
import net.minecraft.world.WorldProvider;
import net.minecraft.world.World;
import net.minecraft.world.WorldServer;
import net.minecraft.world.chunk.Chunk;
import net.minecraft.world.gen.ChunkProviderServer;
import net.minecraft.world.gen.structure.StructureBoundingBox;
import net.minecraft.world.storage.MapStorage;
import net.minecraftforge.event.terraingen.PopulateChunkEvent;
import net.minecraftforge.event.world.WorldEvent;
import org.junit.*;
import org.junit.rules.TemporaryFolder;
import org.apache.logging.log4j.Logger;
import com.charles445.rltweaker.handler.DiagnosticCallerFixture;

import java.util.*;
import java.util.concurrent.atomic.AtomicInteger;
import java.lang.reflect.Field;
import java.nio.file.*;
import java.nio.charset.StandardCharsets;

import static org.junit.Assert.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

public class RCGenerationDiagnosticsTest
{
    @Rule public TemporaryFolder temp = new TemporaryFolder();
    private RCGenerationDiagnostics previous;
    private RCGenerationDiagnostics diagnostics;
    private List<String> log;
    private WorldServer world;
    private ChunkProviderServer provider;
    private Logger previousLogger;

    @BeforeClass
    public static void bootstrap() { Bootstrap.register(); }

    @Before
    public void setup() throws Exception
    {
        previous = RCGenerationDiagnostics.active;
        previousLogger = RecurrentComplex.logger;
        RecurrentComplex.logger = mock(Logger.class);
        log = new ArrayList<>();
        install(true, 10000);
        world = mock(WorldServer.class);
        Field worldProvider = World.class.getDeclaredField("provider");
        worldProvider.setAccessible(true);
        worldProvider.set(world, mock(WorldProvider.class));
        when(world.provider.getDimension()).thenReturn(0);
        provider = mock(ChunkProviderServer.class);
        when(world.getChunkProvider()).thenReturn(provider);
    }

    @After
    public void restore()
    {
        if (diagnostics.file != null) diagnostics.finishSession();
        RCGenerationDiagnostics.active = previous;
        RecurrentComplex.logger = previousLogger;
    }

    @Test
    public void offByDefaultAndInvalidAreaDisablesDiagnostics()
    {
        assertFalse(RCGenerationDiagnostics.Settings.read(new Properties()).enabled);
        Properties p = new Properties();
        p.setProperty("reccomplex.trace", "true");
        assertTrue(RCGenerationDiagnostics.Settings.read(p).enabled);
        p.setProperty("reccomplex.trace.area", "0,0,64,0");
        assertFalse(RCGenerationDiagnostics.Settings.read(p).enabled);
        p.setProperty("reccomplex.trace.area", "-2,-2,1,1");
        assertTrue(RCGenerationDiagnostics.Settings.read(p).enabled);
    }

    @Test
    public void disabledHooksDoNotReadTerrainOrEmitRecords()
    {
        install(false, 100);
        assertNull(diagnostics.beforeWrite(null, null, "initial"));
        diagnostics.completionSkip(world, null, 0, 0, "test");
        diagnostics.dataRead(null, null, true);
        diagnostics.populationPre(new PopulateChunkEvent.Pre(null, world, mock(Random.class), 0, 0, false));
        assertTrue(log.isEmpty());
        verifyZeroInteractions(world, provider);
    }

    @Test
    public void dimensionsAreaAndStructureFiltersSuppressExcludedScopesAndRestoreParent()
    {
        diagnostics.settings.structure = "tower";
        RCGenerationDiagnostics.Scope parent = begin("tower", bounds());
        assertTrue(diagnostics.recording());
        RCGenerationDiagnostics.Scope child = begin("other", bounds());
        diagnostics.sample("average", new BlockPos(1, 60, 1), "hit", true);
        assertFalse(diagnostics.recording());
        diagnostics.end(child);
        assertTrue(diagnostics.recording());
        diagnostics.end(parent);
        assertFalse(diagnostics.recording());
        RCGenerationDiagnostics.Scope outside = begin("tower", new StructureBoundingBox(160, 0, 160, 170, 80, 170));
        assertFalse(diagnostics.recording());
        diagnostics.end(outside);
        when(world.provider.getDimension()).thenReturn(-1);
        RCGenerationDiagnostics.Scope otherDimension = begin("tower", bounds());
        assertFalse(diagnostics.recording());
        diagnostics.end(otherDimension);
        assertEquals(1L, count(" BEGIN "));
        assertEquals(0L, count(" SAMPLE "));
    }

    @Test
    public void unavailableChunkIsUnknownAndNeverProvided()
    {
        RCGenerationDiagnostics.Scope scope = begin("tower", bounds());
        diagnostics.sample("average", new BlockPos(2, 60, 3), "hit", true);
        RCGenerationDiagnostics.Watch watch = diagnostics.beforeWrite(spawn(), 12L, "completion");
        diagnostics.afterWrite(watch, true);
        assertEquals(2L, count("UNAVAILABLE"));
        verify(provider, times(2)).getLoadedChunk(0, 0);
        verifyNoMoreInteractions(provider);
        verify(world, times(2)).getChunkProvider();
        verifyNoMoreInteractions(world);
        diagnostics.end(scope);
    }

    @Test
    public void sameSupportWindowIsObservedBeforeAfterAndAtOwnAndNeighbourPopulationEnd()
    {
        Chunk chunk = mock(Chunk.class);
        when(provider.getLoadedChunk(0, 0)).thenReturn(chunk);
        when(chunk.getBlockState(any(BlockPos.class))).thenReturn(Blocks.STONE.getDefaultState());
        Random eventRandom = mock(Random.class);
        diagnostics.populationPre(new PopulateChunkEvent.Pre(null, world, eventRandom, 0, 0, false));
        RCGenerationDiagnostics.Scope scope = begin("tower", bounds());
        BlockPos.MutableBlockPos sample = new BlockPos.MutableBlockPos(2, 60, 3);
        diagnostics.sample("average", sample, "hit", true);
        sample.setPos(1000, 100, 1000); // Ray loops reuse mutable positions; the watch must own a copy.
        RCGenerationDiagnostics.Watch watch = diagnostics.beforeWrite(spawn(), 12L, "initial");
        when(chunk.getBlockState(any(BlockPos.class))).thenReturn(Blocks.DIRT.getDefaultState());
        diagnostics.afterWrite(watch, true);
        when(chunk.getBlockState(any(BlockPos.class))).thenReturn(Blocks.AIR.getDefaultState());
        diagnostics.populationPost(new PopulateChunkEvent.Post(null, world, eventRandom, 0, 0, false));
        diagnostics.populationPre(new PopulateChunkEvent.Pre(null, world, eventRandom, -1, 0, false));
        diagnostics.populationPost(new PopulateChunkEvent.Post(null, world, eventRandom, -1, 0, false));
        assertEquals(Collections.singleton(new BlockPos(2, 60, 3)), watch.points);
        assertTrue(log.stream().anyMatch(l -> l.contains("phase=before-write") && l.contains("minecraft:stone")));
        assertTrue(log.stream().anyMatch(l -> l.contains("phase=after-write") && l.contains("minecraft:dirt")));
        assertTrue(log.stream().anyMatch(l -> l.contains("phase=population-end=0,0") && l.contains("minecraft:air")));
        assertTrue(log.stream().anyMatch(l -> l.contains("phase=population-end=-1,0") && l.contains("original=0,0")));
        verify(provider, times(4)).getLoadedChunk(0, 0);
        verifyNoMoreInteractions(provider);
        for (int y = 56; y <= 64; y++) verify(chunk, times(4)).getBlockState(new BlockPos(2, y, 3));
        verify(chunk, times(4)).isTerrainPopulated();
        verifyNoMoreInteractions(chunk);
        verifyZeroInteractions(eventRandom);
        diagnostics.end(scope);
    }

    @Test
    public void boundedWatchesExpireAndUnloadWithExplicitCoverageMarkers()
    {
        RCGenerationDiagnostics.Scope scope = begin("tower", bounds());
        diagnostics.sample("average", new BlockPos(2, 60, 3), "hit", true);
        for (int i = 0; i <= RCGenerationDiagnostics.MAX_WATCHES; i++) diagnostics.beforeWrite(spawn(), 1L, "initial");
        assertEquals(1L, count("WATCH_EVICTED"));
        for (int i = 0; i < RCGenerationDiagnostics.MAX_POSTS; i++)
        {
            diagnostics.populationPre(new PopulateChunkEvent.Pre(null, world, null, 0, 0, false));
            diagnostics.populationPost(new PopulateChunkEvent.Post(null, world, null, 0, 0, false));
        }
        assertEquals(RCGenerationDiagnostics.MAX_WATCHES, count("WATCH_EXPIRED"));
        diagnostics.beforeWrite(spawn(), 1L, "initial");
        diagnostics.worldUnload(new WorldEvent.Unload(world));
        assertEquals(1L, count("WATCH_UNLOAD"));
        diagnostics.end(scope);
    }

    @Test
    public void recordCapStopsFurtherTerrainReadsAndEmitsOneMarker()
    {
        install(true, 2);
        RCGenerationDiagnostics.Scope scope = begin("tower", bounds());
        diagnostics.detail("second record");
        diagnostics.detail("over limit");
        assertNull(diagnostics.beforeWrite(spawn(), 1L, "initial"));
        diagnostics.detail("ignored");
        assertEquals(1L, count("TRUNCATED"));
        verifyZeroInteractions(provider);
        diagnostics.end(scope);
    }

    @Test
    public void loggingFailureDoesNotEscapeOrContinueReadingTerrain()
    {
        diagnostics = new RCGenerationDiagnostics(diagnostics.settings, line -> { throw new IllegalStateException("sink failed"); });
        RCGenerationDiagnostics.Scope scope = diagnostics.begin(world, "tower", "natural", "FIRST", bounds(), null, null, null);
        assertFalse(diagnostics.enabled());
        assertNull(diagnostics.beforeWrite(spawn(), 1L, "initial"));
        verifyZeroInteractions(provider);
        diagnostics.end(scope);
    }

    @Test
    public void existingRaySamplesKeepTheSameResultPredicateCallsAndRandomState()
    {
        Set<BlockPos> footprint = new LinkedHashSet<>();
        for (int x = 0; x < 12; x++) footprint.add(new BlockPos(x, 0, 0));
        Random control = new Random(573), traced = new Random(573);
        AtomicInteger controlCalls = new AtomicInteger(), tracedCalls = new AtomicInteger();
        install(false, 10000);
        int expected = RayAverageMatcher.getAverageGroundLevel(false, 75, footprint,
                p -> { controlCalls.incrementAndGet(); return p.getY() == 60 + p.getX() % 3; }, 256, .5, control, 7);
        install(true, 10000);
        RCGenerationDiagnostics.Scope scope = begin("tower", bounds());
        int actual = RayAverageMatcher.getAverageGroundLevel(false, 75, footprint,
                p -> { tracedCalls.incrementAndGet(); return p.getY() == 60 + p.getX() % 3; }, 256, .5, traced, 7);
        assertEquals(expected, actual);
        assertEquals(controlCalls.get(), tracedCalls.get());
        assertEquals(control.nextLong(), traced.nextLong());
        assertTrue(count(" SAMPLE ") > 0);
        diagnostics.end(scope);
    }

    @Test
    public void commonGeneratorLogsCompletionAndDoesNotRepeatPlacement() throws Exception
    {
        Structure<NBTStorable> structure = mock(Structure.class);
        NBTStorable instance = mock(NBTStorable.class);
        Placer placer = mock(Placer.class);
        when(placer.place(any(), any())).thenReturn(60);
        when(structure.size()).thenReturn(new int[]{4, 4, 4});
        TransformerMulti transformer = mock(TransformerMulti.class);
        StructureGenerator<NBTStorable> generator = new StructureGenerator<>(structure).world(world).structureID("tower")
                .generationInfo("natural").environment(new Environment(world, null, null, null))
                .seed(123L).transform(AxisAlignedTransform2D.R0).instanceData(instance)
                .randomPosition(new BlockSurfacePos(2, 3), placer)
                .maturity(StructureSpawnContext.GenerateMaturity.COMPLEMENT)
                .transformer(new RunTransformer(transformer, null));
        assertTrue(((StructureGenerator.GenerationResult) DiagnosticCallerFixture.generate(generator)).succeeded());
        verify(placer, times(1)).place(any(), any());
        verify(structure, times(1)).generate(any(), same(instance), same(transformer));
        assertTrue(log.stream().anyMatch(l -> l.contains(" PASS ") && l.contains("stage=completion") && l.contains("chosenY=60")));
        assertTrue(log.stream().anyMatch(l -> l.contains(" BEGIN ") && l.contains("StructureGenerator.generate")));
        assertTrue(log.stream().anyMatch(l -> l.contains(" BEGIN ") && l.contains("com.charles445.rltweaker.handler.DiagnosticCallerFixture.generate")));
        assertTrue(log.stream().anyMatch(l -> l.contains(" RESULT ") && l.contains("success=true")));
    }

    @Test
    public void savedDataLogsAddRemovalAndLoadResetWithCallerAndUuid() throws Exception
    {
        WorldStructureGenerationData data = new WorldStructureGenerationData();
        diagnostics.data(world, () -> data);
        WorldStructureGenerationData.StructureEntry entry = WorldStructureGenerationData.StructureEntry.complete(
                "tower", "natural", bounds(), AxisAlignedTransform2D.R0, false);
        data.addEntry(entry);
        DiagnosticCallerFixture.cleanup(data, entry.getUuid());
        assertTrue(log.stream().anyMatch(l -> l.contains(" ENTRY_REMOVE ") && l.contains(entry.getUuid().toString())
                && l.contains("WorldStructureGenerationData.removeEntry") && l.contains("DiagnosticCallerFixture.cleanup")));
        data.addEntry(entry);
        data.readFromNBT(new NBTTagCompound());
        assertTrue(log.stream().anyMatch(l -> l.contains(" ENTRY_RESET ") && l.contains(entry.getUuid().toString())
                && l.contains("WorldStructureGenerationData.readFromNBT")));
        assertTrue(log.stream().anyMatch(l -> l.contains("DATA_LOAD_BEGIN") && l.contains("entriesTag=false")));
    }

    @Test
    public void initialAndDirectPreCompletionPassesAreObservedAndSavedEntryIsCorrelated() throws Exception
    {
        WorldStructureGenerationData data = new WorldStructureGenerationData();
        data.checkChunk(new ChunkPos(1, 0));
        MapStorage storage = mock(MapStorage.class);
        when(world.getPerWorldStorage()).thenReturn(storage);
        when(storage.getOrLoadData(eq(WorldStructureGenerationData.class), anyString())).thenReturn(data);
        Structure<NBTStorable> structure = mock(Structure.class);
        NBTStorable instance = mock(NBTStorable.class);
        when(instance.writeToNBT()).thenReturn(new NBTTagCompound());
        TransformerMulti transformer = mock(TransformerMulti.class);
        Field universal = RCConfig.class.getDeclaredField("universalTransformer");
        universal.setAccessible(true);
        Object previousTransformer = universal.get(null);
        universal.set(null, transformer);
        try
        {
            StructureGenerator<NBTStorable> generator = new StructureGenerator<>(structure).world(world).structureID("tower")
                    .environment(new Environment(world, null, null, null)).seed(123L).transform(AxisAlignedTransform2D.R0)
                    .instanceData(instance).boundingBox(new StructureBoundingBox(8, 60, 8, 39, 67, 23))
                    .partially(true, new ChunkPos(0, 0));
            assertTrue(generator.generate().succeeded());
            verify(structure, times(2)).generate(any(), same(instance), same(transformer));
            assertTrue(log.stream().anyMatch(l -> l.contains(" PASS ") && l.contains("stage=initial")));
            assertTrue(log.stream().anyMatch(l -> l.contains(" PASS ") && l.contains("stage=pre-completion")));
            assertTrue(log.stream().anyMatch(l -> l.contains("ENTRY_ADD") && l.contains("trace=1") && l.contains("instanceData=true")));
        }
        finally { universal.set(null, previousTransformer); }
    }

    @Test
    public void generationFailureAndCompletionSkipReasonsAreExplicit()
    {
        Structure<NBTStorable> structure = mock(Structure.class);
        NBTStorable instance = mock(NBTStorable.class);
        doThrow(new IllegalStateException("fixture-write-failure")).when(structure).generate(any(), any(), any());
        StructureGenerator<NBTStorable> generator = new StructureGenerator<>(structure).world(world).structureID("tower")
                .environment(new Environment(world, null, null, null)).seed(123L).transform(AxisAlignedTransform2D.R0)
                .instanceData(instance).boundingBox(bounds()).maturity(StructureSpawnContext.GenerateMaturity.COMPLEMENT)
                .transformer(new RunTransformer(mock(TransformerMulti.class), null));
        assertFalse(generator.generate().succeeded());
        assertTrue(log.stream().anyMatch(l -> l.contains("RESULT") && l.contains("IllegalStateException")));
        assertTrue(log.stream().anyMatch(l -> l.contains("WRITE_RESULT") && l.contains("success=false")));
        WorldStructureGenerationData.StructureEntry entry = WorldStructureGenerationData.StructureEntry.complete(
                "tower", "natural", bounds(), AxisAlignedTransform2D.R0, false);
        for (String reason : Arrays.asList("preventComplementation", "missing-structure", "missing-instance-data"))
            diagnostics.completionSkip(world, entry, 0, 0, reason);
        assertEquals(3, count("COMPLETION_SKIP"));
    }

    @Test
    public void fileCaptureCorrelatesInitialPlacementCompletionCleanupAndLaterSupport() throws Exception
    {
        diagnostics.file = new RCGenerationDiagnosticFile(temp.getRoot().toPath());
        WorldStructureGenerationData data = new WorldStructureGenerationData();
        diagnostics.data(world, () -> data);
        WorldStructureGenerationData.StructureEntry entry = WorldStructureGenerationData.StructureEntry.complete(
                "tower", "natural", bounds(), AxisAlignedTransform2D.R0, false);
        // SUGGEST is the native natural-generation stage, despite its name; it must retain initial evidence.
        RCGenerationDiagnostics.Scope first = diagnostics.begin(world, "tower", "natural", "SUGGEST",
                bounds(), null, null, null);
        diagnostics.planned(spawn(), 123L);
        diagnostics.sample("ground", new BlockPos(2, 60, 3), "hit", true);
        RCGenerationDiagnostics.Watch firstWatch = diagnostics.beforeWrite(spawn(), 123L, "initial");
        diagnostics.afterWrite(firstWatch, true);
        data.addEntry(entry);
        diagnostics.end(first);
        RCGenerationDiagnostics.Scope completion = diagnostics.begin(world, "tower", "natural", "COMPLEMENT",
                bounds(), bounds(), null, entry.getUuid());
        assertSame(first.evidence, completion.evidence);
        diagnostics.beforeWrite(spawn(), 123L, "completion");
        diagnostics.end(completion);
        RCGenerationDiagnostics.Scope unrelated = begin("other", bounds());
        DiagnosticCallerFixture.cleanup(data, entry.getUuid());
        diagnostics.end(unrelated);
        diagnostics.populationPre(new PopulateChunkEvent.Pre(null, world, mock(Random.class), 0, 0, false));
        diagnostics.populationPost(new PopulateChunkEvent.Post(null, world, mock(Random.class), 0, 0, false));
        diagnostics.file.save();
        Map<String, String> zip = RCGenerationDiagnosticFileTest.read(diagnostics.file.path);
        String tower = zip.get("structures/context-1.txt");
        assertTrue(tower.contains("chosenY=61"));
        assertTrue(tower.contains("INITIAL_CONTEXT captured-in-this-session"));
        assertTrue(tower.contains("RESERVED_INITIAL_SUMMARY\n") && tower.contains("stage=SUGGEST"));
        assertTrue(tower.contains("stage=initial"));
        assertTrue(tower.contains("stage=completion"));
        assertTrue(tower.contains("ENTRY_ADD"));
        assertTrue(tower.contains("DiagnosticCallerFixture.cleanup"));
        assertTrue(tower.contains("phase=population-end=0,0"));
        assertFalse(zip.get("structures/context-2.txt").contains("ENTRY_REMOVE"));
        assertTrue(log.isEmpty()); // File mode does not flood latest.log.
        verify(provider, atLeastOnce()).getLoadedChunk(anyInt(), anyInt());
        verifyNoMoreInteractions(provider);
    }

    @Test
    public void evictedContextStopsTerrainReadsAndAnonymousPlayerLocationNeedsNoTerrain() throws Exception
    {
        diagnostics.file = new RCGenerationDiagnosticFile(temp.getRoot().toPath(), 1, 4000, 4000);
        RCGenerationDiagnostics.Scope first = begin("tower", bounds());
        RCGenerationDiagnostics.Watch watch = diagnostics.beforeWrite(spawn(), 3L, "initial");
        diagnostics.end(first);
        diagnostics.end(begin("other", bounds()));
        clearInvocations(provider, world);
        diagnostics.observe(watch, "after-eviction");
        verifyZeroInteractions(provider, world);
        net.minecraft.entity.player.EntityPlayer player = mock(net.minecraft.entity.player.EntityPlayer.class);
        player.dimension = 0; player.posX = 123; player.posY = 70; player.posZ = -321;
        diagnostics.playerLogout(new net.minecraftforge.fml.common.gameevent.PlayerEvent.PlayerLoggedOutEvent(player));
        diagnostics.file.save();
        String report = RCGenerationDiagnosticFileTest.read(diagnostics.file.path).get("session.txt");
        assertTrue(report.contains("player-1"));
        assertTrue(report.contains("xyz=123.0,70.0,-321.0"));
        verifyZeroInteractions(player, provider, world); // No name, profile, UUID or block lookup.
    }

    @Test
    public void lifecycleActivationCreatesSeparateFinalArtifactsWithoutJvmArguments() throws Exception
    {
        Path config = temp.getRoot().toPath().resolve("config");
        Files.createDirectory(config);
        RCGenerationDiagnostics.configure(config.toFile());
        diagnostics = RCGenerationDiagnostics.get();
        diagnostics.startSession();
        assertFalse(Files.exists(temp.getRoot().toPath().resolve("diagnostics")));
        Files.write(config.resolve("reccomplex-diagnostics.properties"), "enabled=true\n".getBytes(StandardCharsets.UTF_8));
        RCGenerationDiagnostics.configure(config.toFile());
        diagnostics = RCGenerationDiagnostics.get();
        diagnostics.startSession();
        assertTrue(diagnostics.enabled());
        Path first = diagnostics.file.path;
        diagnostics.emit("TEST", "first-session");
        diagnostics.finishSession();
        assertFalse(diagnostics.enabled());
        Map<String, String> report = RCGenerationDiagnosticFileTest.read(first);
        assertTrue(report.get("session.txt").contains("sessionClosed=true"));
        assertTrue(report.get("session.txt").contains("BUILD"));
        assertTrue(report.get("session.txt").contains("RC.effective.rayAverageMaxHeightSpread=" + RCConfig.rayAverageMaxHeightSpread));
        assertTrue(report.get("session.txt").contains("RLTweaker.RC.effective.cleanStructureData=true"));
        assertTrue(report.get("session.txt").contains("RLTweaker.RC.effective.generatePartially=false"));
        assertTrue(com.charles445.rltweaker.config.ModConfig.server.recurrentcomplex.cleanStructureData);
        assertFalse(com.charles445.rltweaker.config.ModConfig.server.recurrentcomplex.generatePartially);
        assertTrue(report.get("session.txt").contains("dimension=all area=all"));
        assertTrue(report.get("recent-events.txt").contains("first-session"));
        byte[] original = Files.readAllBytes(first);
        diagnostics.startSession();
        assertNotEquals(first, diagnostics.file.path);
        diagnostics.finishSession();
        assertArrayEquals(original, Files.readAllBytes(first));
    }

    private void install(boolean enabled, int cap)
    {
        Properties p = new Properties();
        p.setProperty("reccomplex.trace", String.valueOf(enabled));
        p.setProperty("reccomplex.trace.area", "-2,-2,2,2");
        p.setProperty("reccomplex.trace.dimension", "0");
        p.setProperty("reccomplex.trace.maxRecords", String.valueOf(cap));
        diagnostics = new RCGenerationDiagnostics(RCGenerationDiagnostics.Settings.read(p), log::add);
        RCGenerationDiagnostics.active = diagnostics;
    }

    private RCGenerationDiagnostics.Scope begin(String structure, StructureBoundingBox bounds)
    {
        return diagnostics.begin(world, structure, "natural", "FIRST", bounds, null, null, null);
    }

    private StructureBoundingBox bounds() { return new StructureBoundingBox(0, 61, 0, 7, 68, 7); }

    private StructureSpawnContext spawn()
    {
        return new StructureSpawnContext(new Environment(world, null, null, null), mock(Random.class), AxisAlignedTransform2D.R0,
                bounds(), null, null, 0, false, StructureSpawnContext.GenerateMaturity.FIRST);
    }

    private long count(String text) { return log.stream().filter(l -> l.contains(text)).count(); }
}
