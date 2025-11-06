/*
 *  Copyright (c) 2014, Lukas Tenbrink.
 *  * http://ivorius.net
 */

package ivorius.reccomplex.utils.accessor;

import com.google.common.collect.Iterables;
import com.google.common.collect.Lists;
import ivorius.reccomplex.utils.ReflectionCompat;
import net.minecraftforge.common.BiomeDictionary;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Map;

/**
 * Created by lukas on 12.12.16.
 */
public class RCAccessorBiomeDictionary
{
    public static BiomeDictionary.Type getTypeWeak(String var)
    {
        return getMap().get(var.toUpperCase());
    }

    public static Map<String, BiomeDictionary.Type> getMap()
    {
        return ReflectionCompat.get(TYPE_BY_NAME, null);
    }

    public static void addSubtypes(BiomeDictionary.Type type, BiomeDictionary.Type... subtypes)
    {
        addSubtypes(type, Arrays.asList(subtypes));
    }

    public static void addSubtypes(BiomeDictionary.Type type, List<BiomeDictionary.Type> subtypes)
    {
        setSubtypes(type, Lists.newArrayList(Iterables.concat(getSubtypes(type), subtypes)));
    }

    public static List<BiomeDictionary.Type> getSubtypes(BiomeDictionary.Type type)
    {
        List<BiomeDictionary.Type> subtypes = ReflectionCompat.get(TYPE_SUBTYPES, type);
        return subtypes != null ? subtypes : new ArrayList<>();
    }

    public static void setSubtypes(BiomeDictionary.Type type, List<BiomeDictionary.Type> types)
    {
        ReflectionCompat.set(TYPE_SUBTYPES, type, types);
    }

    private static final java.lang.reflect.Field TYPE_BY_NAME = ReflectionCompat.findField(BiomeDictionary.Type.class,
            field -> Map.class.isAssignableFrom(field.getType()),
            "byName");

    private static final java.lang.reflect.Field TYPE_SUBTYPES = ReflectionCompat.findField(BiomeDictionary.Type.class,
            field -> List.class.isAssignableFrom(field.getType()),
            "subTypes");
}
