/*
 *  Copyright (c) 2014, Lukas Tenbrink.
 *  * http://ivorius.net
 */

package ivorius.reccomplex.commands.structure;

import ivorius.ivtoolkit.blocks.BlockSurfacePos;
import ivorius.mcopts.commands.CommandExpecting;
import ivorius.mcopts.commands.parameters.MCP;
import ivorius.mcopts.commands.parameters.NaP;
import ivorius.mcopts.commands.parameters.Parameters;
import ivorius.mcopts.commands.parameters.expect.Expect;
import ivorius.mcopts.commands.parameters.expect.MCE;
import ivorius.reccomplex.commands.parameters.IvP;
import ivorius.reccomplex.commands.parameters.RCP;
import ivorius.reccomplex.commands.parameters.expect.RCE;
import ivorius.reccomplex.world.gen.feature.StructureLocator;
import net.minecraft.command.CommandException;
import net.minecraft.command.ICommandSender;
import net.minecraft.server.MinecraftServer;
import net.minecraft.util.math.BlockPos;
import net.minecraft.util.text.TextComponentString;
import net.minecraft.world.WorldServer;

import java.util.Locale;
import java.util.Optional;

public class CommandLocateStructure extends CommandExpecting
{
    @Override
    public String getName()
    {
        return "locate";
    }

    public int getRequiredPermissionLevel()
    {
        return 2;
    }

    @Override
    public void expect(Expect expect)
    {
        expect
                .then(RCE::structure).required()
                .named("radius", "r").skip().descriptionU("chunk radius")
                .named("dimension", "d").then(MCE::dimension)
                .named("from").then(MCE::x).then(MCE::z).atOnce(2).descriptionU("x", "z")
                .flag("unchecked")
        ;
    }

    @Override
    public void execute(MinecraftServer server, ICommandSender sender, String[] args) throws CommandException
    {
        Parameters parameters = Parameters.of(args, expect()::declare);

        String structureID = parameters.get(0).require();
        parameters.get(0).to(RCP::structure).require();

        int radius = parameters.get("radius").to(NaP::asInt).optional().orElse(StructureLocator.DEFAULT_RADIUS);
        if (radius < 0)
            throw new CommandException("Radius must not be negative");

        WorldServer world = parameters.get("dimension").to(MCP.dimension(server, sender)).require();
        BlockSurfacePos surfacePos = parameters.get("from").to(IvP.surfacePos(sender.getPosition(), false)).optional()
                .orElse(BlockSurfacePos.from(sender.getPosition()));
        BlockPos origin = surfacePos.blockPos(sender.getPosition().getY());

        Optional<StructureLocator.Result> result = StructureLocator.locate(world, structureID, origin, radius, parameters.has("unchecked"));
        if (!result.isPresent())
            throw new CommandException(String.format("No predicted natural/static Recurrent Complex spawn for '%s' within %d chunks", structureID, radius));

        sender.sendMessage(message(result.get()));
    }

    protected TextComponentString message(StructureLocator.Result result)
    {
        return new TextComponentString(String.format(Locale.ROOT,
                "Predicted normal-worldgen %s structure '%s' (%s) near %d %d %d in chunk [%d, %d], dimension %d, %.0f blocks away. Seed: %d",
                result.generationType,
                result.structureID,
                result.generationID,
                result.position.getX(), result.position.getY(), result.position.getZ(),
                result.chunkPos.x, result.chunkPos.z,
                result.dimension,
                result.distance(),
                result.seed));
    }
}
