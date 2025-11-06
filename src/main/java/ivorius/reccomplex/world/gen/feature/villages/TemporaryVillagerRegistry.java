/*
 *  Copyright (c) 2014, Lukas Tenbrink.
 *  * http://lukas.axxim.net
 */

package ivorius.reccomplex.world.gen.feature.villages;

import com.google.common.collect.Sets;
import net.minecraftforge.fml.common.registry.VillagerRegistry;
import ivorius.reccomplex.utils.ReflectionCompat;

import java.lang.reflect.Field;
import java.lang.reflect.ParameterizedType;
import java.lang.reflect.Type;
import java.util.HashSet;
import java.util.Map;
import java.util.Set;

/**
 * Created by lukas on 27.03.15.
 */
public class TemporaryVillagerRegistry
{
    protected static Field handlerField;
    protected static TemporaryVillagerRegistry INSTANCE = new TemporaryVillagerRegistry();

    protected Set<VillagerRegistry.IVillageCreationHandler> registeredHandlers = new HashSet<>();

    public static TemporaryVillagerRegistry instance()
    {
        return INSTANCE;
    }

    protected static Map<Class<?>, VillagerRegistry.IVillageCreationHandler> getMap()
    {
        if (handlerField == null)
            handlerField = ReflectionCompat.findField(VillagerRegistry.class,
                    TemporaryVillagerRegistry::isHandlerMapField,
                    "villageCreationHandlers");

        try
        {
            //noinspection unchecked
            return (Map<Class<?>, VillagerRegistry.IVillageCreationHandler>) handlerField.get(VillagerRegistry.instance());
        }
        catch (IllegalAccessException e)
        {
            e.printStackTrace();
        }

        return null;
    }

    public void register(VillagerRegistry.IVillageCreationHandler handler)
    {
        addToRegistry(handler);
        registeredHandlers.add(handler);
    }

    private void addToRegistry(VillagerRegistry.IVillageCreationHandler handler)
    {
        VillagerRegistry.instance().registerVillageCreationHandler(handler);
    }

    public void unregister(VillagerRegistry.IVillageCreationHandler handler)
    {
        removeFromRegistry(handler);
        registeredHandlers.remove(handler);
    }

    private void removeFromRegistry(VillagerRegistry.IVillageCreationHandler handler)
    {
        Map<Class<?>, VillagerRegistry.IVillageCreationHandler> map = getMap();
        if (map != null)
            map.remove(handler.getComponentClass());
    }

    public void setHandlers(Set<VillagerRegistry.IVillageCreationHandler> handlers)
    {
        Sets.difference(registeredHandlers, handlers).forEach(this::removeFromRegistry);

        Sets.difference(handlers, registeredHandlers).forEach(this::addToRegistry);

        registeredHandlers.clear();
        registeredHandlers.addAll(handlers);
    }

    private static boolean isHandlerMapField(Field field)
    {
        if (!Map.class.isAssignableFrom(field.getType()))
            return false;

        Type genericType = field.getGenericType();
        if (genericType instanceof ParameterizedType)
        {
            ParameterizedType parameterizedType = (ParameterizedType) genericType;
            Type[] arguments = parameterizedType.getActualTypeArguments();
            if (arguments.length == 2 && isClassType(arguments[0]) && isVillageHandlerType(arguments[1]))
                return true;
        }

        field.setAccessible(true);
        try
        {
            Object candidate = field.get(VillagerRegistry.instance());
            if (!(candidate instanceof Map))
                return false;

            Map<?, ?> map = (Map<?, ?>) candidate;
            if (map.isEmpty())
                return false;

            Object key = map.keySet().iterator().next();
            Object handler = map.values().iterator().next();
            return key instanceof Class && handler instanceof VillagerRegistry.IVillageCreationHandler;
        }
        catch (IllegalAccessException ignored)
        {
        }

        return false;
    }

    private static boolean isClassType(Type type)
    {
        if (type instanceof Class)
            return Class.class.isAssignableFrom((Class<?>) type);

        if (type instanceof ParameterizedType)
            return isClassType(((ParameterizedType) type).getRawType());

        return false;
    }

    private static boolean isVillageHandlerType(Type type)
    {
        if (type instanceof Class)
            return VillagerRegistry.IVillageCreationHandler.class.isAssignableFrom((Class<?>) type);

        if (type instanceof ParameterizedType)
            return isVillageHandlerType(((ParameterizedType) type).getRawType());

        return false;
    }
}
