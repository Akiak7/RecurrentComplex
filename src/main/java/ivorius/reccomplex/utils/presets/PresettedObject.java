/*
 *  Copyright (c) 2014, Lukas Tenbrink.
 *  * http://ivorius.net
 */

package ivorius.reccomplex.utils.presets;

import com.google.common.collect.Lists;
import ivorius.reccomplex.RecurrentComplex;

import javax.annotation.Nonnull;
import javax.annotation.Nullable;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.WeakHashMap;

/**
 * Created by lukas on 19.09.16.
 */
public class PresettedObject<T>
{
    @Nonnull
    protected PresetRegistry<T> presetRegistry;
    @Nullable
    protected String preset;

    protected T t;

    private static final Map<PresetRegistry<?>, Set<String>> MISSING_PRESETS = new WeakHashMap<>();

    private boolean missingPresetResolved;

    public PresettedObject(@Nonnull PresetRegistry<T> presetRegistry, @Nullable String preset)
    {
        this.presetRegistry = presetRegistry;
        setPreset(preset);
    }

    @Nonnull
    public PresetRegistry<T> getPresetRegistry()
    {
        return presetRegistry;
    }

    public void setPresetRegistry(@Nonnull PresetRegistry<T> presetRegistry)
    {
        this.presetRegistry = presetRegistry;
        missingPresetResolved = false;
    }

    @Nullable
    public String getPreset()
    {
        return preset;
    }

    @Nonnull
    public Optional<String> presetTitle()
    {
        return Optional.ofNullable(getPreset())
                .map(id -> presetRegistry.title(id)
                        .orElse(String.format("Missing Preset (%s)", id))
                );
    }

    @Nonnull
    public Optional<List<String>> presetDescription()
    {
        return Optional.ofNullable(getPreset())
                .map(id -> presetRegistry.description(id)
                        .orElse(Lists.newArrayList(String.format("Missing Preset (%s)", id)))
                );
    }

    public boolean setPreset(@Nullable String preset)
    {
        // Do not check if preset exists yet - it may just not have been loaded yet!
        // Check when the data is actually needed.
        this.preset = preset;
        t = null;
        missingPresetResolved = false;

        return true;
    }

    public void setToCustom()
    {
        loadFromPreset(true);
        preset = null;
        missingPresetResolved = false;
    }

    public boolean isCustom()
    {
        return preset == null;
    }

    public void setToDefault()
    {
        setPreset(defaultPreset());
    }

    public T getContents()
    {
        if (!isCustom())
            loadFromPreset(false);
        return t;
    }

    public void setContents(T ts)
    {
        preset = null;
        t = ts;
        missingPresetResolved = false;
    }

    protected boolean loadFromPreset(boolean copy)
    {
        if (preset == null)
            return true;

        if (missingPresetResolved)
        {
            if (presetRegistry.has(preset))
                missingPresetResolved = false;
            else
            {
                t = fallbackContents(copy);
                return true;
            }
        }

        if ((t = presetContents(copy)) != null)
            return true;

        if (markMissingPreset(presetRegistry, preset))
            RecurrentComplex.logger.warn(String.format("Failed to find preset (%s): %s", presetRegistry.getRegistry().description, preset));

        t = fallbackContents(copy);
        missingPresetResolved = true;

        return false;
    }

    protected String defaultPreset()
    {
        String defaultPreset = presetRegistry.defaultID();
        if (!presetRegistry.has(defaultPreset))
            throw new IllegalStateException(String.format("Default preset named '%s' not found!", defaultPreset));
        return defaultPreset;
    }

    private T presetContents(boolean copy)
    {
        return (copy ? presetRegistry.preset(this.preset) : presetRegistry.originalPreset(this.preset)).orElse(null);
    }

    private T fallbackContents(boolean copy)
    {
        //noinspection OptionalGetWithoutIsPresent
        return (copy ? presetRegistry.preset(defaultPreset()) : presetRegistry.originalPreset(defaultPreset())).get();
    }

    private static boolean markMissingPreset(PresetRegistry<?> registry, String preset)
    {
        synchronized (MISSING_PRESETS)
        {
            return MISSING_PRESETS.computeIfAbsent(registry, key -> new HashSet<>()).add(preset);
        }
    }
}
