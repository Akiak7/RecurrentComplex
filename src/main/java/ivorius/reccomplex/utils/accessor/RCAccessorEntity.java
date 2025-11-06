/*
 *  Copyright (c) 2014, Lukas Tenbrink.
 *  * http://ivorius.net
 */

package ivorius.reccomplex.utils.accessor;

import ivorius.reccomplex.utils.ReflectionCompat;
import net.minecraft.entity.Entity;

import java.lang.reflect.Field;
import java.util.UUID;

/**
 * Created by lukas on 31.01.15.
 */
public class RCAccessorEntity
{
    private static Field uniqueID;

    private static void initializeUniqueID()
    {
        if (uniqueID == null)
            uniqueID = ReflectionCompat.findField(Entity.class,
                    field -> UUID.class.isAssignableFrom(field.getType()),
                    "entityUniqueID", "field_96093_i");
    }

    public static void setEntityUniqueID(Entity entity, UUID uuid)
    {
        initializeUniqueID();

        try
        {
            uniqueID.set(entity, uuid);
        }
        catch (IllegalAccessException e)
        {
            e.printStackTrace();
        }
    }

    public static UUID getEntityUniqueID(Entity entity)
    {
        initializeUniqueID();

        try
        {
            return (UUID) uniqueID.get(entity);
        }
        catch (IllegalAccessException e)
        {
            e.printStackTrace();
        }

        return null;
    }
}
