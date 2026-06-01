/*
 *  Copyright (c) 2014, Lukas Tenbrink.
 *  * http://lukas.axxim.net
 */

package ivorius.reccomplex.world.gen.feature.structure.generic;

import com.google.common.primitives.Ints;
import ivorius.reccomplex.utils.expression.BiomeExpression;
import ivorius.reccomplex.utils.expression.DimensionExpression;

import javax.annotation.Nonnull;
import javax.annotation.Nullable;

public class SimpleMatcherExpression
{
    public enum Target
    {
        BIOME,
        DIMENSION
    }

    public enum Mode
    {
        ANY,
        ID,
        TYPE,
        ADVANCED
    }

    public final Target target;
    public final Mode mode;
    public final String value;
    public final String expression;

    private SimpleMatcherExpression(Target target, Mode mode, String value, String expression)
    {
        this.target = target;
        this.mode = mode;
        this.value = value;
        this.expression = expression;
    }

    public static SimpleMatcherExpression parse(Target target, @Nullable String expression)
    {
        String trimmed = expression != null ? expression.trim() : "";
        if (trimmed.isEmpty())
            return new SimpleMatcherExpression(target, Mode.ANY, "", "");

        String idPrefix = target == Target.BIOME ? BiomeExpression.BIOME_ID_PREFIX : DimensionExpression.DIMENSION_ID_PREFIX;
        String typePrefix = target == Target.BIOME ? BiomeExpression.BIOME_TYPE_PREFIX : DimensionExpression.DIMENSION_TYPE_PREFIX;

        if (trimmed.startsWith(idPrefix))
        {
            String value = trimmed.substring(idPrefix.length()).trim();
            if (isSimpleValue(value))
                return new SimpleMatcherExpression(target, Mode.ID, value, trimmed);
        }

        if (trimmed.startsWith(typePrefix))
        {
            String value = trimmed.substring(typePrefix.length()).trim();
            if (isSimpleValue(value))
                return new SimpleMatcherExpression(target, Mode.TYPE, value, trimmed);
        }

        if (trimmed.startsWith("$"))
        {
            String value = trimmed.substring(1).trim();
            if (isSimpleValue(value))
                return new SimpleMatcherExpression(target, Mode.TYPE, value, trimmed);
        }

        if (target == Target.BIOME && isSimpleResourceID(trimmed))
            return new SimpleMatcherExpression(target, Mode.ID, trimmed, trimmed);

        if (target == Target.DIMENSION && Ints.tryParse(trimmed) != null)
            return new SimpleMatcherExpression(target, Mode.ID, trimmed, trimmed);

        return new SimpleMatcherExpression(target, Mode.ADVANCED, trimmed, trimmed);
    }

    @Nonnull
    public static String toExpression(Target target, Mode mode, @Nullable String value)
    {
        String trimmed = value != null ? value.trim() : "";

        switch (mode)
        {
            case ANY:
                return "";
            case ID:
                return (target == Target.BIOME ? BiomeExpression.BIOME_ID_PREFIX : DimensionExpression.DIMENSION_ID_PREFIX) + trimmed;
            case TYPE:
                return (target == Target.BIOME ? BiomeExpression.BIOME_TYPE_PREFIX : DimensionExpression.DIMENSION_TYPE_PREFIX) + trimmed;
            case ADVANCED:
                return trimmed;
            default:
                throw new IllegalArgumentException("Unknown matcher mode " + mode);
        }
    }

    @Nonnull
    public static String defaultValue(Target target, Mode mode)
    {
        switch (mode)
        {
            case ID:
                return target == Target.BIOME ? "minecraft:plains" : "0";
            case TYPE:
                return target == Target.BIOME ? "FOREST" : "EARTH";
            default:
                return "";
        }
    }

    private static boolean isSimpleResourceID(String value)
    {
        return isSimpleValue(value) && value.matches("[a-z0-9_.-]+(:[a-z0-9_./-]+)?");
    }

    private static boolean isSimpleValue(String value)
    {
        return !value.isEmpty() && !value.matches(".*[\\s&|!()].*");
    }
}
