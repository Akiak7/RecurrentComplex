/*
 *  Copyright (c) 2014, Lukas Tenbrink.
 *  * http://ivorius.net
 */

package ivorius.reccomplex.events.handlers;

import ivorius.reccomplex.RCConfig;
import ivorius.reccomplex.RecurrentComplex;
import ivorius.reccomplex.world.gen.feature.decoration.RCBiomeDecorator;
import ivorius.reccomplex.world.gen.feature.sapling.RCSaplingGenerator;
import ivorius.reccomplex.world.gen.feature.structure.MapGenStructureHook;
import net.minecraft.util.math.BlockPos;
import net.minecraft.util.math.ChunkPos;
import net.minecraft.world.World;
import net.minecraft.world.WorldServer;
import net.minecraft.world.chunk.ChunkPrimer;
import net.minecraft.world.gen.structure.*;
import net.minecraftforge.common.MinecraftForge;
import net.minecraftforge.event.terraingen.DecorateBiomeEvent;
import net.minecraftforge.event.terraingen.InitMapGenEvent;
import net.minecraftforge.event.terraingen.SaplingGrowTreeEvent;
import net.minecraftforge.fml.common.eventhandler.Event;
import net.minecraftforge.fml.common.eventhandler.SubscribeEvent;

import javax.annotation.Nullable;
import java.lang.reflect.Method;
import java.util.Random;
import java.util.concurrent.atomic.AtomicLong;

/**
 * Created by lukas on 14.09.16.
 */
public class RCTerrainGenEventHandler
{
    private static final ThreadLocal<Integer> decorationDepth = ThreadLocal.withInitial(() -> 0);
    private static final long RECURSIVE_DECORATION_LOG_INTERVAL_MS = 10000L;
    private static final AtomicLong lastRecursiveDecorationLog = new AtomicLong(0L);

    private static volatile boolean amountMethodsInitialized;
    private static Method hasAmountDataMethod;
    private static Method getModifiedAmountMethod;
    private static Method setModifiedAmountMethod;

    private static void ensureAmountMethods(Class<?> eventClass)
    {
        if (amountMethodsInitialized)
        {
            return;
        }

        synchronized (RCTerrainGenEventHandler.class)
        {
            if (amountMethodsInitialized)
            {
                return;
            }

            try
            {
                getModifiedAmountMethod = eventClass.getDeclaredMethod("getModifiedAmount");
                setModifiedAmountMethod = eventClass.getDeclaredMethod("setModifiedAmount", int.class);
                hasAmountDataMethod = eventClass.getDeclaredMethod("hasAmountData");
            }
            catch (Exception ignored)
            {
                hasAmountDataMethod = null;
                getModifiedAmountMethod = null;
                setModifiedAmountMethod = null;
            }
            finally
            {
                amountMethodsInitialized = true;
            }
        }
    }

    private static boolean hasAmountData(DecorateBiomeEvent.Decorate event)
    {
        ensureAmountMethods(event.getClass());

        if (hasAmountDataMethod != null)
        {
            try
            {
                return (boolean) hasAmountDataMethod.invoke(event);
            }
            catch (Exception ignored)
            {
            }
        }

        return false;
    }

    private static int getModifiedAmount(DecorateBiomeEvent.Decorate event)
    {
        ensureAmountMethods(event.getClass());

        if (getModifiedAmountMethod != null)
        {
            try
            {
                return (int) getModifiedAmountMethod.invoke(event);
            }
            catch (Exception ignored)
            {
            }
        }

        return -1;
    }

    private static void setModifiedAmount(DecorateBiomeEvent.Decorate event, int amount)
    {
        ensureAmountMethods(event.getClass());

        if (setModifiedAmountMethod != null)
        {
            try
            {
                setModifiedAmountMethod.invoke(event, amount);
            }
            catch (Exception ignored)
            {
            }
        }
    }

    public void register()
    {
        MinecraftForge.TERRAIN_GEN_BUS.register(this);
    }

    @SubscribeEvent
    public void onSaplingGrow(SaplingGrowTreeEvent event)
    {
        if (event.getWorld() instanceof WorldServer)
        {
            if (RCSaplingGenerator.maybeGrowSapling((WorldServer) event.getWorld(), event.getPos(), event.getRand()))
            {
                event.setResult(Event.Result.DENY);
            }
        }
    }

    @SubscribeEvent
    public void onDecoration(DecorateBiomeEvent.Decorate event)
    {
        if (!(event.getWorld() instanceof WorldServer))
        {
            return;
        }

        int depth = decorationDepth.get();
        if (depth > 0)
        {
            long now = System.currentTimeMillis();
            long last = lastRecursiveDecorationLog.get();
            if (now - last >= RECURSIVE_DECORATION_LOG_INTERVAL_MS && lastRecursiveDecorationLog.compareAndSet(last, now))
            {
                ChunkPos chunkPos = new ChunkPos(event.getPos());
                RecurrentComplex.logger.debug("Skipping recursive biome decoration call at depth {} for world {}, type {}, chunk {}",
                        depth, event.getWorld().provider.getDimension(), event.getType(), chunkPos);
            }
            return;
        }

        decorationDepth.set(depth + 1);
        try
        {
            RCBiomeDecorator.DecorationType type = RCBiomeDecorator.DecorationType.getDecorationType(event);

            if (type != null)
            {
                int amount;
                if (hasAmountData(event) && (amount = getModifiedAmount(event)) >= 0)
                    setModifiedAmount(event, RCBiomeDecorator.decorate((WorldServer) event.getWorld(), event.getRand(), event.getPos(), type, amount));
                else
                {
                    Event.Result result = RCBiomeDecorator.decorate((WorldServer) event.getWorld(), event.getRand(), event.getPos(), type);
                    if (result != null)
                        event.setResult(result);
                }
            }
        }
        finally
        {
            decorationDepth.set(depth);
        }
    }

    @SubscribeEvent
    public void onInitMapGen(InitMapGenEvent event)
    {
        if (RCConfig.decorationHacks)
        {
            InitMapGenEvent.EventType type = event.getType();

            // All need to inherit from the base type
            MapGenStructureHook hook;

            switch (type)
            {
                case OCEAN_MONUMENT:

                    hook = new MapGenStructureHook((MapGenStructure) event.getNewGen(), RCBiomeDecorator.DecorationType.OCEAN_MONUMENT);
                    event.setNewGen(new StructureOceanMonument()
                    {
                        @Override
                        public String getStructureName()
                        {
                            return hook.getStructureName();
                        }

                        @Override
                        public boolean generateStructure(World worldIn, Random randomIn, ChunkPos chunkCoord)
                        {
                            return hook.generateStructure(worldIn, randomIn, chunkCoord);
                        }

                        @Override
                        public boolean isInsideStructure(BlockPos pos)
                        {
                            return hook.isInsideStructure(pos);
                        }

                        @Override
                        public boolean isPositionInStructure(World worldIn, BlockPos pos)
                        {
                            return hook.isPositionInStructure(worldIn, pos);
                        }

                        @Override
                        @Nullable
                        public BlockPos getNearestStructurePos(World worldIn, BlockPos pos, boolean findUnexplored)
                        {
                            return hook.getNearestStructurePos(worldIn, pos, findUnexplored);
                        }

                        @Override
                        public void generate(World worldIn, int x, int z, ChunkPrimer primer)
                        {
                            hook.generate(worldIn, x, z, primer);
                        }
                    });
                    break;
                case SCATTERED_FEATURE:
                    hook = new MapGenStructureHook((MapGenStructure) event.getNewGen(), RCBiomeDecorator.DecorationType.SCATTERED_FEATURE);
                    event.setNewGen(new MapGenScatteredFeature()
                    {
                        @Override
                        public String getStructureName()
                        {
                            return hook.getStructureName();
                        }

                        @Override
                        public boolean generateStructure(World worldIn, Random randomIn, ChunkPos chunkCoord)
                        {
                            return hook.generateStructure(worldIn, randomIn, chunkCoord);
                        }

                        @Override
                        public boolean isInsideStructure(BlockPos pos)
                        {
                            return hook.isInsideStructure(pos);
                        }

                        @Override
                        public boolean isPositionInStructure(World worldIn, BlockPos pos)
                        {
                            return hook.isPositionInStructure(worldIn, pos);
                        }

                        @Override
                        @Nullable
                        public BlockPos getNearestStructurePos(World worldIn, BlockPos pos, boolean findUnexplored)
                        {
                            return hook.getNearestStructurePos(worldIn, pos, findUnexplored);
                        }

                        @Override
                        public void generate(World worldIn, int x, int z, ChunkPrimer primer)
                        {
                            hook.generate(worldIn, x, z, primer);
                        }
                    });
                    break;
                case VILLAGE:
                    hook = new MapGenStructureHook((MapGenStructure) event.getNewGen(), RCBiomeDecorator.DecorationType.VILLAGE);
                    event.setNewGen(new MapGenVillage()
                    {
                        @Override
                        public String getStructureName()
                        {
                            return hook.getStructureName();
                        }

                        @Override
                        public boolean generateStructure(World worldIn, Random randomIn, ChunkPos chunkCoord)
                        {
                            return hook.generateStructure(worldIn, randomIn, chunkCoord);
                        }

                        @Override
                        public boolean isInsideStructure(BlockPos pos)
                        {
                            return hook.isInsideStructure(pos);
                        }

                        @Override
                        public boolean isPositionInStructure(World worldIn, BlockPos pos)
                        {
                            return hook.isPositionInStructure(worldIn, pos);
                        }

                        @Override
                        @Nullable
                        public BlockPos getNearestStructurePos(World worldIn, BlockPos pos, boolean findUnexplored)
                        {
                            return hook.getNearestStructurePos(worldIn, pos, findUnexplored);
                        }

                        @Override
                        public void generate(World worldIn, int x, int z, ChunkPrimer primer)
                        {
                            hook.generate(worldIn, x, z, primer);
                        }
                    });
                    break;
                case NETHER_BRIDGE:
                    hook = new MapGenStructureHook((MapGenStructure) event.getNewGen(), RCBiomeDecorator.DecorationType.NETHER_BRIDGE);
                    event.setNewGen(new MapGenNetherBridge()
                    {
                        @Override
                        public String getStructureName()
                        {
                            return hook.getStructureName();
                        }

                        @Override
                        public boolean generateStructure(World worldIn, Random randomIn, ChunkPos chunkCoord)
                        {
                            return hook.generateStructure(worldIn, randomIn, chunkCoord);
                        }

                        @Override
                        public boolean isInsideStructure(BlockPos pos)
                        {
                            return hook.isInsideStructure(pos);
                        }

                        @Override
                        public boolean isPositionInStructure(World worldIn, BlockPos pos)
                        {
                            return hook.isPositionInStructure(worldIn, pos);
                        }

                        @Override
                        @Nullable
                        public BlockPos getNearestStructurePos(World worldIn, BlockPos pos, boolean findUnexplored)
                        {
                            return hook.getNearestStructurePos(worldIn, pos, findUnexplored);
                        }

                        @Override
                        public void generate(World worldIn, int x, int z, ChunkPrimer primer)
                        {
                            hook.generate(worldIn, x, z, primer);
                        }
                    });
                    break;
                case STRONGHOLD:
                    hook = new MapGenStructureHook((MapGenStructure) event.getNewGen(), RCBiomeDecorator.DecorationType.STRONGHOLD);
                    event.setNewGen(new MapGenStronghold()
                    {
                        @Override
                        public String getStructureName()
                        {
                            return hook.getStructureName();
                        }

                        @Override
                        public boolean generateStructure(World worldIn, Random randomIn, ChunkPos chunkCoord)
                        {
                            return hook.generateStructure(worldIn, randomIn, chunkCoord);
                        }

                        @Override
                        public boolean isInsideStructure(BlockPos pos)
                        {
                            return hook.isInsideStructure(pos);
                        }

                        @Override
                        public boolean isPositionInStructure(World worldIn, BlockPos pos)
                        {
                            return hook.isPositionInStructure(worldIn, pos);
                        }

                        @Override
                        @Nullable
                        public BlockPos getNearestStructurePos(World worldIn, BlockPos pos, boolean findUnexplored)
                        {
                            return hook.getNearestStructurePos(worldIn, pos, findUnexplored);
                        }

                        @Override
                        public void generate(World worldIn, int x, int z, ChunkPrimer primer)
                        {
                            hook.generate(worldIn, x, z, primer);
                        }
                    });
                    break;
                case MINESHAFT:
                    hook = new MapGenStructureHook((MapGenStructure) event.getNewGen(), RCBiomeDecorator.DecorationType.MINESHAFT);
                    event.setNewGen(new MapGenMineshaft()
                    {
                        @Override
                        public String getStructureName()
                        {
                            return hook.getStructureName();
                        }

                        @Override
                        public boolean generateStructure(World worldIn, Random randomIn, ChunkPos chunkCoord)
                        {
                            return hook.generateStructure(worldIn, randomIn, chunkCoord);
                        }

                        @Override
                        public boolean isInsideStructure(BlockPos pos)
                        {
                            return hook.isInsideStructure(pos);
                        }

                        @Override
                        public boolean isPositionInStructure(World worldIn, BlockPos pos)
                        {
                            return hook.isPositionInStructure(worldIn, pos);
                        }

                        @Override
                        @Nullable
                        public BlockPos getNearestStructurePos(World worldIn, BlockPos pos, boolean findUnexplored)
                        {
                            return hook.getNearestStructurePos(worldIn, pos, findUnexplored);
                        }

                        @Override
                        public void generate(World worldIn, int x, int z, ChunkPrimer primer)
                        {
                            hook.generate(worldIn, x, z, primer);
                        }
                    });
                    break;
            }
        }
    }
}
