package ivorius.reccomplex.files.saving;

import org.junit.Assert;
import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.TemporaryFolder;

import java.nio.file.Path;

public class FileSaverPathTest
{
    @Rule
    public TemporaryFolder temporaryFolder = new TemporaryFolder();

    @Test
    public void savesOrdinaryNamesInsideDirectory() throws Exception
    {
        Path directory = temporaryFolder.newFolder("structures").toPath();
        CapturingAdapter adapter = new CapturingAdapter();
        FileSaver saver = saver(adapter);

        saver.save(directory, "test", "ordinary");

        Assert.assertEquals(directory.resolve("ordinary.rcst"), adapter.savedPath);
    }

    @Test
    public void rejectsParentTraversal() throws Exception
    {
        Path directory = temporaryFolder.newFolder("structures").toPath();
        FileSaver saver = saver(new CapturingAdapter());

        Assert.assertThrows(IllegalArgumentException.class, () -> saver.save(directory, "test", "../escape"));
    }

    @Test
    public void rejectsAbsolutePaths() throws Exception
    {
        Path directory = temporaryFolder.newFolder("structures").toPath();
        String outside = temporaryFolder.getRoot().toPath().resolve("outside").toAbsolutePath().toString();
        FileSaver saver = saver(new CapturingAdapter());

        Assert.assertThrows(IllegalArgumentException.class, () -> saver.save(directory, "test", outside));
    }

    @Test
    public void rejectsSiblingPrefixPaths() throws Exception
    {
        Path directory = temporaryFolder.newFolder("structures").toPath();
        FileSaver saver = saver(new CapturingAdapter());

        Assert.assertThrows(IllegalArgumentException.class, () -> saver.save(directory, "test", "../structures-other/escape"));
    }

    private static FileSaver saver(CapturingAdapter adapter)
    {
        FileSaver saver = new FileSaver();
        saver.register(adapter);
        return saver;
    }

    private static class CapturingAdapter extends FileSaverAdapter<Object>
    {
        Path savedPath;

        CapturingAdapter()
        {
            super("test", "rcst", null);
        }

        @Override
        public void saveFile(Path path, String id)
        {
            savedPath = path;
        }

        @Override
        public void saveFile(Path path, Object value)
        {
            savedPath = path;
        }
    }
}
