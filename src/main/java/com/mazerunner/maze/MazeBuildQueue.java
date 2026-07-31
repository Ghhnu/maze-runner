package com.mazerunner.maze;

import net.fabricmc.fabric.api.event.lifecycle.v1.ServerTickEvents;
import net.minecraft.block.BlockState;
import net.minecraft.server.world.ServerWorld;
import net.minecraft.util.math.BlockPos;

import java.util.ArrayDeque;
import java.util.Deque;
import java.util.List;

/**
 * Coloca bloques repartidos en varios ticks del servidor para no congelarlo. Se usa tanto
 * para la construcción inicial del laberinto (varios millones de bloques) como para los
 * retoques periódicos (cambio de sector cada 120s, empujar paredes, etc.).
 * <p>
 * Los trabajos se agrupan en {@link Batch}: cuando se coloca el último bloque de un lote se
 * ejecuta su callback (mensaje de progreso, registrar el laberinto en {@link MazeLiveManager},
 * etc.). Varios lotes conviven en la misma cola y se procesan en orden de llegada (FIFO), lo
 * que además garantiza que si dos trabajos tocan el mismo bloque, el que se encoló después
 * "gana" (útil para pintar números/símbolos encima de una pared ya encolada).
 */
public final class MazeBuildQueue {

    @FunctionalInterface
    public interface PostPlace {
        void apply(ServerWorld world, BlockPos pos);
    }

    public static final class Batch {
        final int total;
        int placed = 0;
        final Runnable onComplete;

        public Batch(int total, Runnable onComplete) {
            this.total = total;
            this.onComplete = onComplete;
        }
    }

    public record BlockJob(ServerWorld world, BlockPos pos, BlockState state, PostPlace postPlace, Batch batch) {
        public BlockJob(ServerWorld world, BlockPos pos, BlockState state) {
            this(world, pos, state, null, null);
        }

        public BlockJob(ServerWorld world, BlockPos pos, BlockState state, PostPlace postPlace) {
            this(world, pos, state, postPlace, null);
        }

        BlockJob withBatch(Batch batch) {
            return new BlockJob(world, pos, state, postPlace, batch);
        }
    }

    private static final Deque<BlockJob> queue = new ArrayDeque<>();
    private static boolean registered = false;

    private MazeBuildQueue() {}

    public static void submit(List<BlockJob> jobs) {
        submit(jobs, null);
    }

    public static void submit(List<BlockJob> jobs, Runnable onComplete) {
        ensureRegistered();
        if (jobs.isEmpty()) {
            if (onComplete != null) onComplete.run();
            return;
        }
        Batch batch = new Batch(jobs.size(), onComplete);
        for (BlockJob job : jobs) {
            queue.add(job.withBatch(batch));
        }
    }

    public static int pending() {
        return queue.size();
    }

    private static void ensureRegistered() {
        if (registered) return;
        registered = true;
        ServerTickEvents.END_SERVER_TICK.register(server -> tick());
    }

    private static void tick() {
        if (queue.isEmpty()) return;

        int placedThisTick = 0;
        while (!queue.isEmpty() && placedThisTick < MazeConfig.BLOCKS_PER_TICK) {
            BlockJob job = queue.poll();
            job.world().setBlockState(job.pos(), job.state(), 3);
            if (job.postPlace() != null) {
                job.postPlace().apply(job.world(), job.pos());
            }
            placedThisTick++;

            Batch batch = job.batch();
            if (batch != null) {
                batch.placed++;
                if (batch.placed >= batch.total && batch.onComplete != null) {
                    batch.onComplete.run();
                }
            }
        }
    }
}
