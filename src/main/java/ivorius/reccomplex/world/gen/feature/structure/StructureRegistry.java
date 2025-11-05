/*
 *  Copyright (c) 2014, Lukas Tenbrink.
 *  * http://lukas.axxim.net
 */

package ivorius.reccomplex.world.gen.feature.structure;

import ivorius.reccomplex.RCConfig;
import ivorius.reccomplex.files.SimpleLeveledRegistry;
import ivorius.reccomplex.json.SerializableStringTypeRegistry;
import ivorius.reccomplex.world.gen.feature.structure.generic.generation.GenerationType;
import ivorius.reccomplex.world.gen.feature.structure.generic.transformers.Transformer;
import org.apache.commons.lang3.tuple.Pair;

import java.util.*;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;

/**
 * Created by lukas on 24.05.14.
 */
public class StructureRegistry extends SimpleLeveledRegistry<Structure<?>>
{
    public static final StructureRegistry INSTANCE = new StructureRegistry();

    public static SerializableStringTypeRegistry<Transformer> TRANSFORMERS = new SerializableStringTypeRegistry<>("transformer", "type", Transformer.class);
    public static SerializableStringTypeRegistry<GenerationType> GENERATION_TYPES = new SerializableStringTypeRegistry<>("generationInfo", "type", GenerationType.class);

    private final ConcurrentMap<Class<? extends GenerationType>, CacheEntry> cachedGeneration = new ConcurrentHashMap<>();
    private final java.util.concurrent.atomic.AtomicLong cacheVersion = new java.util.concurrent.atomic.AtomicLong();

    public StructureRegistry()
    {
        super("structure");
    }

    @Override
    public Structure register(String id, String domain, Structure structure, boolean active, ILevel level)
    {
        if (active && !(RCConfig.shouldStructureGenerate(id, domain) && structure.areDependenciesResolved()))
            active = false;

        return super.register(id, domain, structure, active, level);
    }

    public <T extends GenerationType> Collection<Pair<Structure<?>, T>> getGenerationTypes(Class<T> clazz)
    {
        CacheEntry entry = cachedGeneration.computeIfAbsent(clazz, key -> new CacheEntry());

        while (true)
        {
            long version = cacheVersion.get();
            List<Pair<Structure<?>, GenerationType>> pairs = entry.pairs;

            if (pairs != null && entry.version == version)
            {
                //noinspection unchecked
                return (Collection<Pair<Structure<?>, T>>) (Collection<?>) pairs;
            }

            synchronized (entry.lock)
            {
                version = cacheVersion.get();
                pairs = entry.pairs;

                if (pairs != null && entry.version == version)
                {
                    continue;
                }

                pairs = buildGenerationPairs(clazz);

                if (cacheVersion.get() == version)
                {
                    entry.pairs = pairs;
                    entry.version = version;
                    //noinspection unchecked
                    return (Collection<Pair<Structure<?>, T>>) (Collection<?>) pairs;
                }

                entry.pairs = null;
                entry.version = -1;
            }
        }
    }

    private <T extends GenerationType> List<Pair<Structure<?>, GenerationType>> buildGenerationPairs(Class<T> clazz)
    {
        List<Pair<Structure<?>, GenerationType>> pairs = new ArrayList<>();

        for (Structure<?> structure : allActive())
        {
            for (T generationType : structure.generationTypes(clazz))
            {
                pairs.add(Pair.of(structure, generationType));
            }
        }

        return Collections.unmodifiableList(pairs);
    }

    @Override
    protected void invalidateCaches()
    {
        super.invalidateCaches();
        cacheVersion.incrementAndGet();
        cachedGeneration.values().forEach(entry ->
        {
            synchronized (entry.lock)
            {
                entry.pairs = null;
                entry.version = -1;
            }
        });
    }

    private static class StructureData
    {
        public boolean disabled;
        public String domain;

        public StructureData(boolean disabled, String domain)
        {
            this.disabled = disabled;
            this.domain = domain;
        }
    }

    private static class CacheEntry
    {
        final Object lock = new Object();
        volatile List<Pair<Structure<?>, GenerationType>> pairs;
        volatile long version = -1;
    }
}
