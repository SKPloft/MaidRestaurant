package com.mastermarisa.maid_restaurant.event;

import com.github.tartaricacid.touhoulittlemaid.entity.passive.EntityMaid;
import com.mastermarisa.maid_restaurant.MaidRestaurant;
import com.mastermarisa.maid_restaurant.maid.TaskCook;
import com.mastermarisa.maid_restaurant.utils.CookMaidDiagnostics;
import com.mojang.brigadier.CommandDispatcher;
import com.mojang.brigadier.exceptions.CommandSyntaxException;
import net.minecraft.commands.CommandSourceStack;
import net.minecraft.commands.Commands;
import net.minecraft.commands.arguments.EntityArgument;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.entity.Entity;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.event.RegisterCommandsEvent;

import java.util.List;

@EventBusSubscriber(modid = MaidRestaurant.MOD_ID)
public class DiagnosticCommand {
    @SubscribeEvent
    public static void onRegisterCommands(RegisterCommandsEvent event) {
        CommandDispatcher<CommandSourceStack> dispatcher = event.getDispatcher();
        dispatcher.register(Commands.literal(MaidRestaurant.MOD_ID)
                .requires(source -> source.hasPermission(2))
                .then(Commands.literal("inspect")
                        .then(Commands.argument("maid", EntityArgument.entity())
                                .executes(context -> inspect(context.getSource(), EntityArgument.getEntity(context, "maid"))))));
    }

    private static int inspect(CommandSourceStack source, Entity entity) throws CommandSyntaxException {
        if (!(entity instanceof EntityMaid maid)) {
            source.sendFailure(Component.literal("Target entity is not a maid."));
            return 0;
        }
        if (!(maid.getTask() instanceof TaskCook)) {
            source.sendFailure(Component.literal("Target maid is not using the cook task."));
            return 0;
        }
        if (!(maid.level() instanceof ServerLevel level)) {
            source.sendFailure(Component.literal("Cook maid diagnostics must run on a server level."));
            return 0;
        }

        List<String> report = CookMaidDiagnostics.buildReport(level, maid);
        for (String line : report) {
            source.sendSuccess(() -> Component.literal(line), false);
            MaidRestaurant.LOGGER.info("[CookMaidDiagnostics] {}", line);
        }
        return report.size();
    }
}
