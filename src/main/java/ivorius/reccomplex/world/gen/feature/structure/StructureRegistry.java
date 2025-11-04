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
import java.util.stream.Collectors;

/**
 * Created by lukas on 24.05.14.
 */
public class StructureRegistry extends SimpleLeveledRegistry<Structure<?>>
{
    public static final StructureRegistry INSTANCE = new StructureRegistry();

    public static SerializableStringTypeRegistry<Transformer> TRANSFORMERS = new SerializableStringTypeRegistry<>("transformer", "type", Transformer.class);
    public static SerializableStringTypeRegistry<GenerationType> GENERATION_TYPES = new SerializableStringTypeRegistry<>("generationInfo", "type", GenerationType.class);

    private final ConcurrentMap<Class<? extends GenerationType>, Collection<Pair<Structure<?>, ? extends GenerationType>>> cachedGeneration = new ConcurrentHashMap<>();

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
        Collection<Pair<Structure<?>, ? extends GenerationType>> pairs = cachedGeneration.computeIfAbsent(clazz, key ->
                Collections.unmodifiableList(allActive().stream()
                        .flatMap(s -> s.generationTypes(clazz).stream()
                                .map(t -> Pair.of(s, t)))
                        .collect(Collectors.toList())));

        //noinspection unchecked
        return (Collection<Pair<Structure<?>, T>>) (Collection<?>) pairs;
    }

    @Override
    protected void invalidateCaches()
    {
        super.invalidateCaches();
        cachedGeneration.clear();
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
}
