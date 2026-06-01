/*
 *  Copyright (c) 2014, Lukas Tenbrink.
 *  * http://lukas.axxim.net
 */

package ivorius.reccomplex.gui.editstructure;

import ivorius.ivtoolkit.tools.IvTranslations;
import ivorius.reccomplex.gui.RCGuiTables;
import ivorius.reccomplex.gui.table.TableCells;
import ivorius.reccomplex.gui.table.TableDelegate;
import ivorius.reccomplex.gui.table.datasource.TableDataSourceSegmented;
import ivorius.reccomplex.world.gen.feature.structure.generic.SimpleMatcherExpression;
import ivorius.reccomplex.world.gen.feature.structure.generic.WeightedBiomeMatcher;
import net.minecraftforge.fml.relauncher.Side;
import net.minecraftforge.fml.relauncher.SideOnly;

import javax.annotation.Nonnull;
import java.util.Arrays;

/**
 * Created by lukas on 05.06.14.
 */

@SideOnly(Side.CLIENT)
public class TableDataSourceBiomeGen extends TableDataSourceSegmented
{
    private WeightedBiomeMatcher generationInfo;

    private TableDelegate tableDelegate;

    public TableDataSourceBiomeGen(WeightedBiomeMatcher generationInfo, TableDelegate tableDelegate)
    {
        this.generationInfo = generationInfo;
        this.tableDelegate = tableDelegate;

        addSegment(0, new TableDataSourceSimpleMatcherExpression(SimpleMatcherExpression.Target.BIOME, generationInfo.getBiomeExpression(), tableDelegate));

        addSegment(1, () -> {
            return RCGuiTables.defaultWeightElement(val -> generationInfo.setGenerationWeight(TableCells.toDouble(val)), generationInfo.getGenerationWeight(),
                    IvTranslations.get("reccomplex.gui.random.weight"), Arrays.asList("First matching biome entry decides the weight.", "Use 0 to deny/block this match; default or nonzero values allow it."));
        });
    }

    @Nonnull
    @Override
    public String title()
    {
        return "Biome";
    }
}
