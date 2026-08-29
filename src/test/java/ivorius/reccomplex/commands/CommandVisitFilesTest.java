package ivorius.reccomplex.commands;

import org.junit.Assert;
import org.junit.Test;

public class CommandVisitFilesTest
{
    @Test
    public void requiresOperatorPermission()
    {
        Assert.assertEquals(2, new CommandVisitFiles().getRequiredPermissionLevel());
    }
}
