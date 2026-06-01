/*
 *  Copyright (c) 2014, Lukas Tenbrink.
 *  * http://lukas.axxim.net
 */

package ivorius.reccomplex.gui.editstructure;

import ivorius.ivtoolkit.tools.IvTranslations;
import ivorius.reccomplex.gui.TableDataSourceExpression;
import ivorius.reccomplex.gui.table.TableDelegate;
import ivorius.reccomplex.gui.table.cell.TableCell;
import ivorius.reccomplex.gui.table.cell.TableCellEnum;
import ivorius.reccomplex.gui.table.cell.TableCellString;
import ivorius.reccomplex.gui.table.cell.TableCellTitle;
import ivorius.reccomplex.gui.table.cell.TitledCell;
import ivorius.reccomplex.gui.table.datasource.TableDataSourceSegmented;
import ivorius.reccomplex.utils.RCStrings;
import ivorius.reccomplex.utils.algebra.FunctionExpressionCache;
import ivorius.reccomplex.world.gen.feature.structure.generic.SimpleMatcherExpression;
import net.minecraftforge.fml.relauncher.Side;
import net.minecraftforge.fml.relauncher.SideOnly;

import javax.annotation.Nonnull;
import java.util.Collections;
import java.util.List;

@SideOnly(Side.CLIENT)
public class TableDataSourceSimpleMatcherExpression extends TableDataSourceSegmented
{
    private final SimpleMatcherExpression.Target target;
    private final FunctionExpressionCache<Boolean, ?, Object> expression;
    private final TableDelegate tableDelegate;

    private TableCellString expressionCell;
    private TableCellTitle parsedCell;

    public TableDataSourceSimpleMatcherExpression(SimpleMatcherExpression.Target target, FunctionExpressionCache<Boolean, ?, Object> expression, TableDelegate tableDelegate)
    {
        this.target = target;
        this.expression = expression;
        this.tableDelegate = tableDelegate;

        addSegment(0, this::modeCell);
        addSegment(1, this::valueCell);
        addSegment(2, this::advancedExpressionCell);
        addSegment(3, this::parsedExpressionCell);
    }

    private TableCell modeCell()
    {
        SimpleMatcherExpression simple = current();
        TableCellEnum<SimpleMatcherExpression.Mode> cell = new TableCellEnum<>("simpleMatcherMode", simple.mode,
                new TableCellEnum.Option<>(SimpleMatcherExpression.Mode.ANY, "Any", Collections.singletonList("Match any " + targetName() + ".")),
                new TableCellEnum.Option<>(SimpleMatcherExpression.Mode.ID, targetTitle() + " ID", Collections.singletonList("Match one " + targetName() + " by ID.")),
                new TableCellEnum.Option<>(SimpleMatcherExpression.Mode.TYPE, targetTitle() + " Type", Collections.singletonList("Match a dictionary type.")),
                new TableCellEnum.Option<>(SimpleMatcherExpression.Mode.ADVANCED, "Advanced", Collections.singletonList("Edit the raw expression for complex logic.")));
        cell.addListener(mode ->
        {
            if (mode != SimpleMatcherExpression.Mode.ADVANCED)
                expression.setExpression(SimpleMatcherExpression.toExpression(target, mode, valueForMode(simple, mode)));

            tableDelegate.reloadData();
        });
        return new TitledCell("Mode", cell).withTitleTooltip(Collections.singletonList("Simple modes cover common rules. Use Advanced for expression logic."));
    }

    private TableCell valueCell()
    {
        SimpleMatcherExpression simple = current();
        if (simple.mode == SimpleMatcherExpression.Mode.ID || simple.mode == SimpleMatcherExpression.Mode.TYPE)
        {
            TableCellString cell = new TableCellString("simpleMatcherValue", simple.value);
            cell.setTooltip(Collections.singletonList(valueTooltip(simple.mode)));
            cell.addListener(value ->
            {
                expression.setExpression(SimpleMatcherExpression.toExpression(target, simple.mode, value));
                refreshExpressionViews();
            });
            return new TitledCell("Value", cell);
        }

        TableCellTitle cell = new TableCellTitle(null, simple.mode == SimpleMatcherExpression.Mode.ANY ? "All" : "-");
        return new TitledCell("Value", cell).withTitleTooltip(Collections.singletonList("Set mode to an ID or Type to use a simple value."));
    }

    private TableCell advancedExpressionCell()
    {
        SimpleMatcherExpression simple = current();
        expressionCell = new TableCellString("advancedExpression", expression.getExpression());
        expressionCell.setTooltip(expressionTooltip());
        expressionCell.setEnabled(simple.mode == SimpleMatcherExpression.Mode.ADVANCED);
        expressionCell.setShowsValidityState(true);
        expressionCell.setValidityState(TableDataSourceExpression.getValidityState(expression, null));
        expressionCell.addListener(value ->
        {
            expression.setExpression(value);
            expressionCell.setValidityState(TableDataSourceExpression.getValidityState(expression, null));
            refreshParsedExpression();
        });
        expressionCell.setChangeListener(this::refreshParsedExpression);

        return new TitledCell("Advanced Expression", expressionCell)
                .withTitleTooltip(Collections.singletonList("Enabled in Advanced mode. Simple modes write this expression for compatibility."));
    }

    private TableCell parsedExpressionCell()
    {
        parsedCell = new TableCellTitle("parsedExpression", parsedString());
        parsedCell.setPositioning(TableCellTitle.Positioning.TOP);
        return new TitledCell("Parsed", parsedCell);
    }

    private SimpleMatcherExpression current()
    {
        return SimpleMatcherExpression.parse(target, expression.getExpression());
    }

    private String valueForMode(SimpleMatcherExpression simple, SimpleMatcherExpression.Mode mode)
    {
        return simple.mode == mode ? simple.value : SimpleMatcherExpression.defaultValue(target, mode);
    }

    private void refreshExpressionViews()
    {
        if (expressionCell != null)
        {
            expressionCell.setPropertyValue(expression.getExpression());
            expressionCell.setValidityState(TableDataSourceExpression.getValidityState(expression, null));
        }

        refreshParsedExpression();
    }

    private void refreshParsedExpression()
    {
        if (parsedCell != null)
            parsedCell.setDisplayString(parsedString());
    }

    private String parsedString()
    {
        return RCStrings.abbreviateFormatted(TableDataSourceExpression.parsedString(expression, null), 0, 71);
    }

    private List<String> expressionTooltip()
    {
        return IvTranslations.formatLines(target == SimpleMatcherExpression.Target.BIOME
                ? "reccomplex.expression.biome.tooltip"
                : "reccomplex.expression.dimension.tooltip");
    }

    private String valueTooltip(SimpleMatcherExpression.Mode mode)
    {
        if (target == SimpleMatcherExpression.Target.BIOME)
            return mode == SimpleMatcherExpression.Mode.ID ? "Examples: minecraft:plains, biomesoplenty:redwood_forest" : "Examples: FOREST, OCEAN, WATER";

        return mode == SimpleMatcherExpression.Mode.ID ? "Examples: 0, -1, 1" : "Examples: EARTH, HELL, ENDER";
    }

    private String targetTitle()
    {
        return target == SimpleMatcherExpression.Target.BIOME ? "Biome" : "Dimension";
    }

    private String targetName()
    {
        return targetTitle().toLowerCase();
    }

    @Nonnull
    @Override
    public String title()
    {
        return targetTitle();
    }
}
