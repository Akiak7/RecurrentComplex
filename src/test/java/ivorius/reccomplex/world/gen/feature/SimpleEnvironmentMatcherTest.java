package ivorius.reccomplex.world.gen.feature;

import ivorius.reccomplex.dimensions.DimensionDictionary;
import net.minecraft.entity.EnumCreatureType;
import net.minecraft.init.Biomes;
import net.minecraft.init.Bootstrap;
import net.minecraft.util.math.BlockPos;
import net.minecraft.world.DimensionType;
import net.minecraft.world.World;
import net.minecraft.world.WorldProvider;
import net.minecraft.world.biome.Biome;
import net.minecraft.world.chunk.Chunk;
import net.minecraft.world.gen.IChunkGenerator;
import net.minecraftforge.common.BiomeDictionary;
import org.junit.Assert;
import org.junit.BeforeClass;
import org.junit.Test;

import java.util.Collections;
import java.util.List;
import java.util.Set;

public class SimpleEnvironmentMatcherTest
{
    @BeforeClass
    public static void bootstrapMinecraft()
    {
        Bootstrap.register();
        BiomeDictionary.addTypes(Biomes.FOREST, BiomeDictionary.Type.FOREST);
        BiomeDictionary.addTypes(Biomes.OCEAN, BiomeDictionary.Type.OCEAN);
        BiomeDictionary.addTypes(Biomes.RIVER, BiomeDictionary.Type.RIVER);
    }

    @Test
    public void biomeIdWhitelistAllowsOnlyListedBiomeIDs()
    {
        SimpleEnvironmentMatcher<Biome> whitelist = SimpleEnvironmentMatcher.biomes(new String[]{"minecraft:plains"}, null);

        Assert.assertTrue(SimpleEnvironmentMatcher.allows(Biomes.PLAINS, whitelist, emptyBiomeMatcher()));
        Assert.assertFalse(SimpleEnvironmentMatcher.allows(Biomes.DESERT, whitelist, emptyBiomeMatcher()));
    }

    @Test
    public void bareBiomeIDMatchesVanillaBiomeID()
    {
        SimpleEnvironmentMatcher<Biome> whitelist = SimpleEnvironmentMatcher.biomes(new String[]{"plains"}, null);

        Assert.assertTrue(SimpleEnvironmentMatcher.allows(Biomes.PLAINS, whitelist, emptyBiomeMatcher()));
    }

    @Test
    public void biomeTypeBlocklistBlocksMatchingDictionaryTypes()
    {
        SimpleEnvironmentMatcher<Biome> blocklist = SimpleEnvironmentMatcher.biomes(new String[]{"type=FOREST"}, null);

        Assert.assertFalse(SimpleEnvironmentMatcher.allows(Biomes.FOREST, emptyBiomeMatcher(), blocklist));
        Assert.assertTrue(SimpleEnvironmentMatcher.allows(Biomes.DESERT, emptyBiomeMatcher(), blocklist));
    }

    @Test
    public void waterBiomeTypeMatchesOceanAndRiverLikeExpressionMatcher()
    {
        SimpleEnvironmentMatcher<Biome> blocklist = SimpleEnvironmentMatcher.biomes(new String[]{"type=WATER"}, null);

        Assert.assertFalse(SimpleEnvironmentMatcher.allows(Biomes.OCEAN, emptyBiomeMatcher(), blocklist));
        Assert.assertFalse(SimpleEnvironmentMatcher.allows(Biomes.RIVER, emptyBiomeMatcher(), blocklist));
        Assert.assertTrue(SimpleEnvironmentMatcher.allows(Biomes.DESERT, emptyBiomeMatcher(), blocklist));
    }

    @Test
    public void biomeBlocklistWinsOverWhitelist()
    {
        SimpleEnvironmentMatcher<Biome> whitelist = SimpleEnvironmentMatcher.biomes(new String[]{"minecraft:ocean"}, null);
        SimpleEnvironmentMatcher<Biome> blocklist = SimpleEnvironmentMatcher.biomes(new String[]{"minecraft:ocean"}, null);

        Assert.assertFalse(SimpleEnvironmentMatcher.allows(Biomes.OCEAN, whitelist, blocklist));
    }

    @Test
    public void numericDimensionWhitelistAllowsOnlyListedDimensionIDs()
    {
        SimpleEnvironmentMatcher<WorldProvider> whitelist = SimpleEnvironmentMatcher.dimensions(new String[]{"0", "id=-1"}, null);

        Assert.assertTrue(SimpleEnvironmentMatcher.allows(provider(0, DimensionDictionary.EARTH), whitelist, emptyDimensionMatcher()));
        Assert.assertTrue(SimpleEnvironmentMatcher.allows(provider(-1, DimensionDictionary.HELL), whitelist, emptyDimensionMatcher()));
        Assert.assertFalse(SimpleEnvironmentMatcher.allows(provider(1, DimensionDictionary.ENDER), whitelist, emptyDimensionMatcher()));
    }

    @Test
    public void dimensionTypeBlocklistBlocksMatchingDictionaryTypes()
    {
        SimpleEnvironmentMatcher<WorldProvider> blocklist = SimpleEnvironmentMatcher.dimensions(new String[]{"type=EARTH"}, null);

        Assert.assertFalse(SimpleEnvironmentMatcher.allows(provider(0, DimensionDictionary.EARTH), emptyDimensionMatcher(), blocklist));
        Assert.assertTrue(SimpleEnvironmentMatcher.allows(provider(-1, DimensionDictionary.HELL), emptyDimensionMatcher(), blocklist));
    }

    @Test
    public void bareDimensionEntryMatchesDimensionType()
    {
        SimpleEnvironmentMatcher<WorldProvider> whitelist = SimpleEnvironmentMatcher.dimensions(new String[]{"earth"}, null);

        Assert.assertTrue(SimpleEnvironmentMatcher.allows(provider(0, DimensionDictionary.EARTH), whitelist, emptyDimensionMatcher()));
        Assert.assertFalse(SimpleEnvironmentMatcher.allows(provider(-1, DimensionDictionary.HELL), whitelist, emptyDimensionMatcher()));
    }

    private static SimpleEnvironmentMatcher<Biome> emptyBiomeMatcher()
    {
        return SimpleEnvironmentMatcher.biomes(new String[0], null);
    }

    private static SimpleEnvironmentMatcher<WorldProvider> emptyDimensionMatcher()
    {
        return SimpleEnvironmentMatcher.dimensions(new String[0], null);
    }

    private static WorldProvider provider(int dimension, String type)
    {
        return new TestWorldProvider(dimension, Collections.singleton(type));
    }

    private static class TestWorldProvider extends WorldProvider implements DimensionDictionary.Handler
    {
        private final Set<String> dimensionTypes;

        private TestWorldProvider(int dimension, Set<String> dimensionTypes)
        {
            this.dimensionTypes = dimensionTypes;
            this.setDimension(dimension);
        }

        @Override
        public DimensionType getDimensionType()
        {
            return DimensionType.OVERWORLD;
        }

        @Override
        public IChunkGenerator createChunkGenerator()
        {
            return new IChunkGenerator()
            {
                @Override
                public Chunk generateChunk(int x, int z)
                {
                    return null;
                }

                @Override
                public void populate(int x, int z)
                {
                }

                @Override
                public boolean generateStructures(Chunk chunk, int x, int z)
                {
                    return false;
                }

                @Override
                public List<Biome.SpawnListEntry> getPossibleCreatures(EnumCreatureType creatureType, BlockPos pos)
                {
                    return Collections.emptyList();
                }

                @Override
                public BlockPos getNearestStructurePos(World world, String structureName, BlockPos position, boolean findUnexplored)
                {
                    return null;
                }

                @Override
                public void recreateStructures(Chunk chunk, int x, int z)
                {
                }

                @Override
                public boolean isInsideStructure(World world, String structureName, BlockPos pos)
                {
                    return false;
                }
            };
        }

        @Override
        public Set<String> getDimensionTypes()
        {
            return dimensionTypes;
        }
    }
}
