package ivorius.reccomplex.world.gen.feature.structure.generic;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.google.gson.JsonObject;
import net.minecraft.init.Bootstrap;
import org.junit.Assert;
import org.junit.BeforeClass;
import org.junit.Test;

public class SimpleMatcherExpressionTest
{
    private static final Gson BIOME_GSON = new GsonBuilder()
            .registerTypeAdapter(WeightedBiomeMatcher.class, new WeightedBiomeMatcher.Serializer())
            .create();

    private static final Gson DIMENSION_GSON = new GsonBuilder()
            .registerTypeAdapter(WeightedDimensionMatcher.class, new WeightedDimensionMatcher.Serializer())
            .create();

    @BeforeClass
    public static void bootstrapMinecraft()
    {
        Bootstrap.register();
    }

    @Test
    public void parsesBiomeSimpleExpressions()
    {
        assertSimple(SimpleMatcherExpression.Target.BIOME, "", SimpleMatcherExpression.Mode.ANY, "");
        assertSimple(SimpleMatcherExpression.Target.BIOME, "id=minecraft:plains", SimpleMatcherExpression.Mode.ID, "minecraft:plains");
        assertSimple(SimpleMatcherExpression.Target.BIOME, "minecraft:plains", SimpleMatcherExpression.Mode.ID, "minecraft:plains");
        assertSimple(SimpleMatcherExpression.Target.BIOME, "type=FOREST", SimpleMatcherExpression.Mode.TYPE, "FOREST");
        assertSimple(SimpleMatcherExpression.Target.BIOME, "$FOREST", SimpleMatcherExpression.Mode.TYPE, "FOREST");
    }

    @Test
    public void parsesDimensionSimpleExpressions()
    {
        assertSimple(SimpleMatcherExpression.Target.DIMENSION, "", SimpleMatcherExpression.Mode.ANY, "");
        assertSimple(SimpleMatcherExpression.Target.DIMENSION, "id=0", SimpleMatcherExpression.Mode.ID, "0");
        assertSimple(SimpleMatcherExpression.Target.DIMENSION, "0", SimpleMatcherExpression.Mode.ID, "0");
        assertSimple(SimpleMatcherExpression.Target.DIMENSION, "type=EARTH", SimpleMatcherExpression.Mode.TYPE, "EARTH");
        assertSimple(SimpleMatcherExpression.Target.DIMENSION, "$EARTH", SimpleMatcherExpression.Mode.TYPE, "EARTH");
    }

    @Test
    public void detectsComplexExpressionsAsAdvanced()
    {
        assertSimple(SimpleMatcherExpression.Target.BIOME, "$FOREST & !$COLD", SimpleMatcherExpression.Mode.ADVANCED, "$FOREST & !$COLD");
        assertSimple(SimpleMatcherExpression.Target.DIMENSION, "$EARTH | $UNCATEGORIZED", SimpleMatcherExpression.Mode.ADVANCED, "$EARTH | $UNCATEGORIZED");
    }

    @Test
    public void writesSimpleExpressions()
    {
        Assert.assertEquals("", SimpleMatcherExpression.toExpression(SimpleMatcherExpression.Target.BIOME, SimpleMatcherExpression.Mode.ANY, ""));
        Assert.assertEquals("id=minecraft:plains", SimpleMatcherExpression.toExpression(SimpleMatcherExpression.Target.BIOME, SimpleMatcherExpression.Mode.ID, "minecraft:plains"));
        Assert.assertEquals("type=FOREST", SimpleMatcherExpression.toExpression(SimpleMatcherExpression.Target.BIOME, SimpleMatcherExpression.Mode.TYPE, "FOREST"));
        Assert.assertEquals("id=0", SimpleMatcherExpression.toExpression(SimpleMatcherExpression.Target.DIMENSION, SimpleMatcherExpression.Mode.ID, "0"));
        Assert.assertEquals("type=EARTH", SimpleMatcherExpression.toExpression(SimpleMatcherExpression.Target.DIMENSION, SimpleMatcherExpression.Mode.TYPE, "EARTH"));
    }

    @Test
    public void biomeSerializationStillUsesExistingExpressionField()
    {
        String expression = SimpleMatcherExpression.toExpression(SimpleMatcherExpression.Target.BIOME, SimpleMatcherExpression.Mode.TYPE, "FOREST");
        WeightedBiomeMatcher matcher = new WeightedBiomeMatcher(expression, 0.5);

        JsonObject json = BIOME_GSON.toJsonTree(matcher, WeightedBiomeMatcher.class).getAsJsonObject();

        Assert.assertEquals(expression, json.get("biomes").getAsString());
        Assert.assertFalse(json.has("mode"));
        Assert.assertFalse(json.has("value"));

        WeightedBiomeMatcher decoded = BIOME_GSON.fromJson(json, WeightedBiomeMatcher.class);
        Assert.assertEquals(expression, decoded.getBiomeExpression().getExpression());
        Assert.assertEquals(Double.valueOf(0.5), decoded.getGenerationWeight());
    }

    @Test
    public void dimensionSerializationStillUsesExistingExpressionField()
    {
        String expression = SimpleMatcherExpression.toExpression(SimpleMatcherExpression.Target.DIMENSION, SimpleMatcherExpression.Mode.ID, "0");
        WeightedDimensionMatcher matcher = new WeightedDimensionMatcher(expression, 0.25);

        JsonObject json = DIMENSION_GSON.toJsonTree(matcher, WeightedDimensionMatcher.class).getAsJsonObject();

        Assert.assertEquals(expression, json.get("dimensions").getAsString());
        Assert.assertFalse(json.has("mode"));
        Assert.assertFalse(json.has("value"));

        WeightedDimensionMatcher decoded = DIMENSION_GSON.fromJson(json, WeightedDimensionMatcher.class);
        Assert.assertEquals(expression, decoded.getDimensionExpression().getExpression());
        Assert.assertEquals(Double.valueOf(0.25), decoded.getGenerationWeight());
    }

    private static void assertSimple(SimpleMatcherExpression.Target target, String expression, SimpleMatcherExpression.Mode mode, String value)
    {
        SimpleMatcherExpression parsed = SimpleMatcherExpression.parse(target, expression);
        Assert.assertEquals(target, parsed.target);
        Assert.assertEquals(mode, parsed.mode);
        Assert.assertEquals(value, parsed.value);
        Assert.assertEquals(expression.trim(), parsed.expression);
    }
}
