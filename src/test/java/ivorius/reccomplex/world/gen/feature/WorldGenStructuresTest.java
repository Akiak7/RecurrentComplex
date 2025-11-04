package ivorius.reccomplex.world.gen.feature;

import net.minecraft.util.math.ChunkPos;
import org.junit.After;
import org.junit.Before;
import org.junit.Test;

import java.lang.reflect.Field;
import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.ConcurrentMap;
import java.util.stream.IntStream;
import java.util.stream.Stream;

import static org.junit.Assert.assertTrue;

public class WorldGenStructuresTest
{
    private Method lockChunks;
    private Method unlockChunks;
    private Field chunkLocksField;

    @Before
    public void setUp() throws NoSuchMethodException, NoSuchFieldException
    {
        lockChunks = WorldGenStructures.class.getDeclaredMethod("lockChunks", Stream.class);
        lockChunks.setAccessible(true);
        unlockChunks = WorldGenStructures.class.getDeclaredMethod("unlockChunks", List.class);
        unlockChunks.setAccessible(true);

        chunkLocksField = WorldGenStructures.class.getDeclaredField("CHUNK_LOCKS");
        chunkLocksField.setAccessible(true);
    }

    @After
    public void tearDown() throws IllegalAccessException
    {
        getChunkLocks().clear();
    }

    @Test
    public void locksAreReleasedAndCleanedUp() throws InvocationTargetException, IllegalAccessException
    {
        List<ChunkPos> positions = new ArrayList<>();
        IntStream.range(0, 512).forEach(i -> positions.add(new ChunkPos(i, -i)));

        @SuppressWarnings("unchecked")
        List<Object> handles = (List<Object>) lockChunks.invoke(null, positions.stream());

        try
        {
            unlockChunks.invoke(null, handles);
            assertTrue("Expected CHUNK_LOCKS to be empty after unlocking", getChunkLocks().isEmpty());
        }
        finally
        {
            getChunkLocks().clear();
        }
    }

    @SuppressWarnings("unchecked")
    private ConcurrentMap<Long, ?> getChunkLocks() throws IllegalAccessException
    {
        return (ConcurrentMap<Long, ?>) chunkLocksField.get(null);
    }
}
