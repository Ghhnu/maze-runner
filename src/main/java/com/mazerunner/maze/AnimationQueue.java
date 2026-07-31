package com.mazerunner.maze;

import net.fabricmc.fabric.api.event.lifecycle.v1.ServerTickEvents;
import net.minecraft.block.BlockState;
import net.minecraft.server.world.ServerWorld;
import net.minecraft.util.math.BlockPos;

import java.util.ArrayDeque;
import java.util.Deque;
import java.util.List;

/**
 * A diferencia de {@link MazeBuildQueue} (que coloca miles de bloques por tick para ir lo
 * más rápido posible), esta cola coloca una "columna" entera cada {@link MazeConfig#GATE_ANIM_TICK_STEP}
 * ticks, para conseguir el efecto visual de animación: las puertas se abren o se cierran
 * cubo a cubo, de un lado al otro, en vez de aparecer todas de golpe.
 */
public final class AnimationQueue {

    public record ColumnJob(ServerWorld world, List<BlockPos> positions, BlockState state, Runnable onPlaced) {}

    private static final Deque<ColumnJob> queue = new ArrayDeque<>();
    private static boolean registered = false;
    private static int tickCounter = 0;

    private AnimationQueue() {}

    public static void submit(ColumnJob job) {
        ensureRegistered();
        queue.add(job);
    }

    private static void ensureRegistered() {
        if (registered) return;
        registered = true;
        ServerTickEvents.END_SERVER_TICK.register(server -> tick());
    }

    private static void tick() {
        if (queue.isEmpty()) return;
        tickCounter++;
        if (tickCounter < MazeConfig.GATE_ANIM_TICK_STEP) return;
        tickCounter = 0;

        ColumnJob job = queue.poll();
        for (BlockPos pos : job.positions()) {
            job.world().setBlockState(pos, job.state(), 3);
        }
        if (job.onPlaced() != null) job.onPlaced().run();
    }
}
