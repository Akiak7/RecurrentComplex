/*
 *  Copyright (c) 2014, Lukas Tenbrink.
 *  * http://ivorius.net
 */

package ivorius.reccomplex.world.gen.feature.selector;

import net.minecraft.util.ResourceLocation;
import net.minecraft.world.WorldProvider;
import net.minecraft.world.biome.Biome;
import org.apache.commons.lang3.tuple.ImmutablePair;
import org.apache.commons.lang3.tuple.Pair;

import java.util.Map;
import java.util.function.BiFunction;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;

/**
 * Created by lukas on 23.09.16.
 */
public class CachedStructureSelectors<S extends StructureSelector>
{
    private final ConcurrentMap<Pair<Integer, ResourceLocation>, S> structureSelectors = new ConcurrentHashMap<>();

    private BiFunction<Biome, WorldProvider, S> selectorSupplier;

    private static final ResourceLocation NULL_BIOME_KEY = new ResourceLocation("reccomplex", "null_biome");

    public CachedStructureSelectors(BiFunction<Biome, WorldProvider, S> selectorSupplier)
    {
        this.selectorSupplier = selectorSupplier;
    }

    public S get(Biome biome, WorldProvider provider)
    {
        Pair<Integer, ResourceLocation> pair = new ImmutablePair<>(provider.getDimension(), resolveBiomeKey(biome));
        return structureSelectors.compute(pair, (key, existing) ->
        {
            if (existing != null && existing.isValid(biome, provider))
            {
                return existing;
            }

            return selectorSupplier.apply(biome, provider);
        });
    }

    private static ResourceLocation resolveBiomeKey(Biome biome)
    {
        if (biome == null)
        {
            return NULL_BIOME_KEY;
        }

        ResourceLocation key = Biome.REGISTRY.getNameForObject(biome);
        if (key != null)
        {
            return key;
        }

        return new ResourceLocation("reccomplex", "synthetic/" + System.identityHashCode(biome));
    }

    public void clear()
    {
        structureSelectors.clear();
    }
}
