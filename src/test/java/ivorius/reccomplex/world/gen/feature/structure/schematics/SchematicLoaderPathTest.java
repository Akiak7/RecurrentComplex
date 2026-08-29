package ivorius.reccomplex.world.gen.feature.structure.schematics;

import org.junit.Assert;
import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.TemporaryFolder;

import java.io.File;

public class SchematicLoaderPathTest
{
    @Rule
    public TemporaryFolder temporaryFolder = new TemporaryFolder();

    @Test
    public void acceptsFilesInsideDirectory() throws Exception
    {
        File directory = temporaryFolder.newFolder("schematics");

        Assert.assertTrue(SchematicLoader.isFileInDirectory(directory, new File(directory, "ordinary.schematic")));
    }

    @Test
    public void rejectsTraversalAndSiblingPrefixes() throws Exception
    {
        File directory = temporaryFolder.newFolder("schematics");

        Assert.assertFalse(SchematicLoader.isFileInDirectory(directory, new File(directory, "../escape.schematic")));
        Assert.assertFalse(SchematicLoader.isFileInDirectory(directory, new File(directory, "../schematics-other/escape.schematic")));
    }

    @Test
    public void rejectsAbsolutePaths() throws Exception
    {
        File directory = temporaryFolder.newFolder("schematics");
        File outside = new File(temporaryFolder.getRoot(), "outside.schematic").getAbsoluteFile();

        Assert.assertFalse(SchematicLoader.isFileInDirectory(directory, outside));
    }
}
