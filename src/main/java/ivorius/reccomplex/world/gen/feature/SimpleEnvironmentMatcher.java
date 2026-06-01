/*
 *  Copyright (c) 2014, Lukas Tenbrink.
 *  * http://ivorius.net
 */

package ivorius.reccomplex.world.gen.feature;

import com.google.common.primitives.Ints;
import ivorius.reccomplex.dimensions.DimensionDictionary;
import ivorius.reccomplex.utils.accessor.RCAccessorBiomeDictionary;
import net.minecraft.util.ResourceLocation;
import net.minecraft.world.WorldProvider;
import net.minecraft.world.biome.Biome;
import net.minecraftforge.common.BiomeDictionary;

import javax.annotation.Nullable;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.function.Consumer;
import java.util.function.Predicate;

public class SimpleEnvironmentMatcher<T>
{
    private static final String TYPE_PREFIX = "type=";
    private static final String ID_PREFIX = "id=";

    private final List<Predicate<T>> entries;

    private SimpleEnvironmentMatcher(List<Predicate<T>> entries)
    {
        this.entries = entries;
    }

    public boolean isEmpty()
    {
        return entries.isEmpty();
    }

    public boolean matches(T value)
    {
        return entries.stream().anyMatch(entry -> entry.test(value));
    }

    public static <T> boolean allows(T value, SimpleEnvironmentMatcher<T> whitelist, SimpleEnvironmentMatcher<T> blocklist)
    {
        return !blocklist.matches(value) && (whitelist.isEmpty() || whitelist.matches(value));
    }

    public static SimpleEnvironmentMatcher<Biome> biomes(String[] entries, @Nullable Consumer<String> warning)
    {
        List<Predicate<Biome>> matchers = new ArrayList<>();
        for (String entry : entries)
        {
            String trimmed = entry.trim();
            if (!trimmed.isEmpty())
                matchers.add(biomeMatcher(trimmed, warning));
        }

        return new SimpleEnvironmentMatcher<>(matchers);
    }

    public static SimpleEnvironmentMatcher<WorldProvider> dimensions(String[] entries, @Nullable Consumer<String> warning)
    {
        List<Predicate<WorldProvider>> matchers = new ArrayList<>();
        for (String entry : entries)
        {
            String trimmed = entry.trim();
            if (!trimmed.isEmpty())
                matchers.add(dimensionMatcher(trimmed, warning));
        }

        return new SimpleEnvironmentMatcher<>(matchers);
    }

    private static Predicate<Biome> biomeMatcher(String entry, @Nullable Consumer<String> warning)
    {
        if (entry.startsWith(TYPE_PREFIX))
        {
            String typeName = entry.substring(TYPE_PREFIX.length()).trim();
            BiomeDictionary.Type type = RCAccessorBiomeDictionary.getTypeWeak(typeName);
            if (type == null)
                return unknownEntry("Unknown biome type '" + typeName + "' in simple biome list entry '" + entry + "'", warning);

            return biome -> matchesBiomeType(biome, type);
        }

        ResourceLocation location;
        try
        {
            location = new ResourceLocation(entry.indexOf(':') >= 0 ? entry : "minecraft:" + entry);
        }
        catch (RuntimeException e)
        {
            return unknownEntry("Invalid biome ID in simple biome list entry '" + entry + "'", warning);
        }

        ResourceLocation matchLocation = location;
        return biome -> matchLocation.equals(Biome.REGISTRY.getNameForObject(biome));
    }

    private static boolean matchesBiomeType(Biome biome, BiomeDictionary.Type type)
    {
        return BiomeDictionary.hasType(biome, type)
                || (type == BiomeDictionary.Type.WATER
                && (BiomeDictionary.hasType(biome, BiomeDictionary.Type.OCEAN)
                || BiomeDictionary.hasType(biome, BiomeDictionary.Type.RIVER)));
    }

    private static Predicate<WorldProvider> dimensionMatcher(String entry, @Nullable Consumer<String> warning)
    {
        if (entry.startsWith(ID_PREFIX))
        {
            String id = entry.substring(ID_PREFIX.length()).trim();
            Integer dimensionID = Ints.tryParse(id);
            if (dimensionID != null)
                return provider -> provider.getDimension() == dimensionID;

            return unknownEntry("Invalid dimension ID in simple dimension list entry '" + entry + "'", warning);
        }

        Integer dimensionID = Ints.tryParse(entry);
        if (dimensionID != null)
            return provider -> provider.getDimension() == dimensionID;

        String type = entry.startsWith(TYPE_PREFIX) ? entry.substring(TYPE_PREFIX.length()).trim() : entry;
        if (type.isEmpty())
            return unknownEntry("Empty dimension type in simple dimension list entry '" + entry + "'", warning);

        String upperType = type.toUpperCase(Locale.ROOT);
        return provider -> DimensionDictionary.dimensionMatchesType(provider, type)
                || DimensionDictionary.dimensionMatchesType(provider, upperType);
    }

    private static <T> Predicate<T> unknownEntry(String message, @Nullable Consumer<String> warning)
    {
        if (warning != null)
            warning.accept(message);

        return value -> false;
    }
}
