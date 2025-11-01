package ivorius.reccomplex.utils;

import ivorius.reccomplex.RecurrentComplex;
import net.minecraftforge.fml.relauncher.ReflectionHelper;

import java.lang.reflect.Field;
import java.util.Arrays;
import java.util.function.Predicate;

/**
 * Utility helpers that retain the legacy ReflectionHelper behaviour while
 * providing additional fallbacks for environments where field names changed
 * (for example newer Java versions).
 */
public final class ReflectionCompat
{
    private ReflectionCompat()
    {
    }

    public static Field findField(Class<?> owner, Predicate<Field> fallback, String... names)
    {
        try
        {
            Field field = ReflectionHelper.findField(owner, names);
            field.setAccessible(true);
            return field;
        }
        catch (ReflectionHelper.UnableToFindFieldException e)
        {
            if (fallback != null)
            {
                Field resolved = search(owner, fallback);
                if (resolved != null)
                {
                    resolved.setAccessible(true);
                    RecurrentComplex.logger.warn("Falling back to field {}.{} for names {}", owner.getName(), resolved.getName(), Arrays.toString(names));
                    return resolved;
                }
            }
            throw e;
        }
    }

    private static Field search(Class<?> owner, Predicate<Field> predicate)
    {
        Class<?> current = owner;
        while (current != null)
        {
            for (Field field : current.getDeclaredFields())
            {
                if (predicate.test(field))
                    return field;
            }
            current = current.getSuperclass();
        }
        return null;
    }

    public static <T> T get(Field field, Object target)
    {
        try
        {
            //noinspection unchecked
            return (T) field.get(target);
        }
        catch (IllegalAccessException e)
        {
            throw new IllegalStateException("Unable to access field " + field, e);
        }
    }

    public static void set(Field field, Object target, Object value)
    {
        try
        {
            field.set(target, value);
        }
        catch (IllegalAccessException e)
        {
            throw new IllegalStateException("Unable to set field " + field, e);
        }
    }
}
