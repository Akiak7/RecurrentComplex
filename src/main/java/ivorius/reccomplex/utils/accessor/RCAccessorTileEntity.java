/*
 *  Copyright (c) 2014, Lukas Tenbrink.
 *  * http://ivorius.net
 */

package ivorius.reccomplex.utils.accessor;

import net.minecraft.tileentity.TileEntity;
import net.minecraft.util.ResourceLocation;
import net.minecraft.util.registry.RegistryNamespaced;
import net.minecraftforge.fml.relauncher.ReflectionHelper;

import javax.annotation.Nullable;
import java.lang.reflect.Field;

public class RCAccessorTileEntity
{
    private static boolean initialized;
    private static RegistryNamespaced<ResourceLocation, Class<? extends TileEntity>> registry;

    @Nullable
    public static synchronized Boolean isRegistered(ResourceLocation id)
    {
        if (!initialized)
        {
            initialized = true;

            try
            {
                Field field = ReflectionHelper.findField(TileEntity.class,
                        new String[]{"REGISTRY", "field_190562_f"});
                //noinspection unchecked
                registry = (RegistryNamespaced<ResourceLocation, Class<? extends TileEntity>>) field.get(null);
            }
            catch (Exception ignored)
            {
                registry = null;
            }
        }

        try
        {
            return registry != null ? registry.containsKey(id) : null;
        }
        catch (Exception ignored)
        {
            return null;
        }
    }
}
