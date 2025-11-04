package ivorius.reccomplex.world.gen.feature.structure;

import it.unimi.dsi.fastutil.longs.Long2ObjectMap;
import it.unimi.dsi.fastutil.longs.Long2ObjectOpenHashMap;
import it.unimi.dsi.fastutil.longs.LongArrayList;
import it.unimi.dsi.fastutil.longs.LongOpenHashSet;
import it.unimi.dsi.fastutil.longs.LongSet;
import org.junit.Assert;
import org.junit.Test;

public class MapGenStructureHookTest
{
    @Test
    public void onlyProcessesNewStructureKeys()
    {
        Long2ObjectMap<Object> map = new Long2ObjectOpenHashMap<>();
        LongSet before = new LongOpenHashSet();

        for (long i = 0; i < 1000; i++)
        {
            map.put(i, new Object());
            before.add(i);
        }

        long[] newKeys = { 1001L, 5000L, 123456789L };
        for (long key : newKeys)
        {
            map.put(key, new Object());
        }

        LongArrayList processed = new LongArrayList();
        MapGenStructureHook.forEachNewStructureKey(map, before, processed::add);

        Assert.assertEquals("Only new keys should be processed", newKeys.length, processed.size());
        for (long key : newKeys)
        {
            Assert.assertTrue("Processed keys should include new key " + key, processed.contains(key));
        }

        for (long key : before)
        {
            Assert.assertFalse("Processed keys must not contain pre-existing key " + key, processed.contains(key));
        }
    }
}
