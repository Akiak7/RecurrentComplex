package ivorius.reccomplex.files.saving;

import ivorius.reccomplex.files.loading.LeveledRegistry;
import org.junit.Test;

import java.nio.file.Path;
import java.util.Collections;
import java.util.HashSet;
import java.util.Set;

import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

public class FileSaverTest
{
    @Test
    public void adaptersCanBeRegisteredAndUnregisteredByInstance()
    {
        FileSaver saver = new FileSaver();
        TestSaverAdapter adapter = new TestSaverAdapter("adapter", "ext");

        saver.register(adapter);
        assertTrue("adapter should be registered", saver.has(adapter.getId()));

        saver.unregister(adapter);
        assertFalse("adapter should be removed when unregistered by instance", saver.has(adapter.getId()));
    }

    @Test
    public void adaptersCanBeRegisteredAndUnregisteredById()
    {
        FileSaver saver = new FileSaver();
        TestSaverAdapter adapter = new TestSaverAdapter("adapter", "ext");

        saver.register(adapter);
        assertTrue("adapter should be registered", saver.has(adapter.getId()));

        saver.unregister(adapter.getId());
        assertFalse("adapter should be removed when unregistered by id", saver.has(adapter.getId()));
    }

    private static class TestSaverAdapter extends FileSaverAdapter<Object>
    {
        private TestSaverAdapter(String id, String suffix)
        {
            super(id, suffix, new DummyRegistry());
        }

        @Override
        public void saveFile(Path path, Object o)
        {
            // Not required for this test
        }
    }

    private static class DummyRegistry implements LeveledRegistry<Object>
    {
        private final Set<String> ids = new HashSet<>();

        @Override
        public Object register(String id, String domain, Object o, boolean active, ILevel level)
        {
            ids.add(id);
            return o;
        }

        @Override
        public Object unregister(String id, ILevel level)
        {
            ids.remove(id);
            return null;
        }

        @Override
        public Object get(String id)
        {
            return null;
        }

        @Override
        public Status status(String id)
        {
            return null;
        }

        @Override
        public boolean has(String id)
        {
            return ids.contains(id);
        }

        @Override
        public Set<String> ids()
        {
            return Collections.unmodifiableSet(ids);
        }

        @Override
        public void clear(ILevel level)
        {
            ids.clear();
        }
    }
}
