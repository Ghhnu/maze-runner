package com.mazerunner.command;

import com.mazerunner.maze.MazeBuilder;
import com.mazerunner.maze.MazeInstance;
import com.mazerunner.maze.MazeLiveManager;
import com.mojang.brigadier.context.CommandContext;
import net.fabricmc.fabric.api.command.v2.CommandRegistrationCallback;
import net.minecraft.command.argument.BlockPosArgumentType;
import net.minecraft.server.command.CommandManager;
import net.minecraft.server.command.ServerCommandSource;
import net.minecraft.server.world.ServerWorld;
import net.minecraft.text.Text;
import net.minecraft.util.math.BlockPos;

/**
 * Registra {@code /maze <x> <y> <z>} (construye/reconstruye el laberinto circular centrado
 * en esas coordenadas) y {@code /maze open} / {@code /maze close} (abren/cierran las 4
 * puertas grandes del laberinto activo en el mundo actual).
 */
public final class MazeCommand {

    private MazeCommand() {}

    public static void register() {
        CommandRegistrationCallback.EVENT.register((dispatcher, registryAccess, environment) ->
                dispatcher.register(CommandManager.literal("maze")
                        .requires(source -> source.hasPermissionLevel(2))
                        .then(CommandManager.argument("pos", BlockPosArgumentType.blockPos())
                                .executes(MazeCommand::generate))
                        .then(CommandManager.literal("open")
                                .executes(MazeCommand::open))
                        .then(CommandManager.literal("close")
                                .executes(MazeCommand::close))));
    }

    private static int generate(CommandContext<ServerCommandSource> ctx) {
        ServerCommandSource source = ctx.getSource();
        BlockPos center = BlockPosArgumentType.getBlockPos(ctx, "pos");

        if (!(source.getWorld() instanceof ServerWorld world)) {
            source.sendError(Text.literal("No se pudo obtener el mundo del servidor."));
            return 0;
        }

        MazeBuilder.generate(world, center, source);
        return 1;
    }

    private static int open(CommandContext<ServerCommandSource> ctx) {
        ServerCommandSource source = ctx.getSource();
        if (!(source.getWorld() instanceof ServerWorld world)) {
            source.sendError(Text.literal("No se pudo obtener el mundo del servidor."));
            return 0;
        }

        MazeInstance instance = MazeLiveManager.get(world);
        if (instance == null) {
            source.sendError(Text.literal("No hay ningún laberinto activo en este mundo. Usa /maze <x> <y> <z> primero."));
            return 0;
        }

        instance.openGates();
        return 1;
    }

    private static int close(CommandContext<ServerCommandSource> ctx) {
        ServerCommandSource source = ctx.getSource();
        if (!(source.getWorld() instanceof ServerWorld world)) {
            source.sendError(Text.literal("No se pudo obtener el mundo del servidor."));
            return 0;
        }

        MazeInstance instance = MazeLiveManager.get(world);
        if (instance == null) {
            source.sendError(Text.literal("No hay ningún laberinto activo en este mundo. Usa /maze <x> <y> <z> primero."));
            return 0;
        }

        instance.closeGates();
        return 1;
    }
}
