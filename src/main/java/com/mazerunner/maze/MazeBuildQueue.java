package com.mazerunner.maze;

import net.fabricmc.fabric.api.event.lifecycle.v1.ServerTickEvents;
import net.minecraft.block.BlockState;
import net.minecraft.server.world.ServerWorld;
import net.minecraft.util.math.BlockPos;

import java.util.ArrayDeque;
import java.util.Collections;
import java.util.Deque;
import java.util.Iterator;
import java.util.List;
import java.util.NoSuchElementException;

/**
 * Coloca bloques repartidos en varios ticks del servidor para no congelarlo. Se usa tanto
 * para la construcción inicial del laberinto (varios millones de bloques) como para los
 * retoques periódicos (cambio de sector cada 120s, empujar paredes, etc.).
 * <p>
 * Los trabajos se agrupan en {@link Source}: cuando se coloca el último bloque de una fuente
 * se ejecuta su callback (mensaje de progreso, registrar el laberinto en
 * {@link MazeLiveManager}, etc.). Varias fuentes conviven en la misma cola y se procesan en
 * orden de llegada (FIFO) y una detrás de otra, lo que además garantiza que si dos trabajos
 * tocan el mismo bloque, el que se encoló después "gana" (útil para pintar números/símbolos
 * encima de una pared ya encolada).
 * <p>
 * IMPORTANTE: las fuentes son {@link Iterator}s de {@link BlockJob}, no listas ya construidas.
 * Esto es imprescindible para laberintos grandes (decenas de millones de bloques): en vez de
 * tener todos los {@code BlockJob} precalculados en memoria a la vez (lo que revienta el heap),
 * cada bloque se calcula justo antes de colocarlo, tick a tick. {@link #submit(List, Runnable)}
 * sigue existiendo para trabajos pequeños (cientos o miles de bloques) donde no compensa la
 * molestia de escribir un iterador a mano.
 */
public final class MazeBuildQueue {

    @FunctionalInterface
    public interface PostPlace {
        void apply(ServerWorld world, BlockPos pos);
    }

    public record BlockJob(ServerWorld world, BlockPos pos, BlockState state, PostPlace postPlace) {
        public BlockJob(ServerWorld world, BlockPos pos, BlockState state) {
            this(world, pos, state, null);
        }
    }

    private static final class Source {
        final Iterator<BlockJob> it;
        final Runnable onComplete;

        Source(Iterator<BlockJob> it, Runnable onComplete) {
            this.it = it;
            this.onComplete = onComplete;
        }
    }

    private static final Deque<Source> queue = new ArrayDeque<>();
    private static boolean registered = false;

    private MazeBuildQueue() {}

    public static void submit(List<BlockJob> jobs) {
        submit(jobs, null);
    }

    /** Para trabajos pequeños/medianos que ya viven cómodamente en una {@link List}. */
    public static void submit(List<BlockJob> jobs, Runnable onComplete) {
        submit(jobs.iterator(), onComplete);
    }

    /** Para trabajos potencialmente enormes: {@code it} calcula cada {@link BlockJob} bajo
     *  demanda (justo antes de colocarlo) en vez de tenerlos todos precalculados en memoria. */
    public static void submit(Iterator<BlockJob> it, Runnable onComplete) {
        ensureRegistered();
        if (!it.hasNext()) {
            if (onComplete != null) onComplete.run();
            return;
        }
        queue.add(new Source(it, onComplete));
    }

    /** No hay forma barata de saber cuántos bloques quedan exactamente cuando una fuente es
     *  perezosa (contarlos de verdad la agotaría antes de tiempo), pero esto sirve para saber
     *  si queda algo por colocar. */
    public static boolean isEmpty() {
        return queue.isEmpty();
    }

    private static void ensureRegistered() {
        if (registered) return;
        registered = true;
        ServerTickEvents.END_SERVER_TICK.register(server -> tick());
    }

    private static void tick() {
        int placedThisTick = 0;
        while (placedThisTick < MazeConfig.BLOCKS_PER_TICK && !queue.isEmpty()) {
            Source src = queue.peek();
            if (!src.it.hasNext()) {
                queue.poll();
                if (src.onComplete != null) src.onComplete.run();
                continue;
            }
            BlockJob job = src.it.next();
            job.world().setBlockState(job.pos(), job.state(), 3);
            if (job.postPlace() != null) {
                job.postPlace().apply(job.world(), job.pos());
            }
            placedThisTick++;

            if (!src.it.hasNext()) {
                queue.poll();
                if (src.onComplete != null) src.onComplete.run();
            }
        }
    }

    /** Pequeña utilidad para encadenar varios iteradores como si fueran uno solo, preservando
     *  el orden (se agota el primero antes de pasar al segundo, etc.). Útil quando queremos que
     *  un único {@code onComplete} dispare solo cuando TODAS las fases hayan terminado, sin
     *  tener que anidar callbacks a mano. */
    public static Iterator<BlockJob> chain(List<Iterator<BlockJob>> iterators) {
        Iterator<Iterator<BlockJob>> outer = List.copyOf(iterators).iterator();
        return new Iterator<>() {
            Iterator<BlockJob> current = Collections.emptyIterator();

            private void advance() {
                while (!current.hasNext() && outer.hasNext()) {
                    current = outer.next();
                }
            }

            @Override
            public boolean hasNext() {
                advance();
                return current.hasNext();
            }

            @Override
            public BlockJob next() {
                advance();
                if (!current.hasNext()) throw new NoSuchElementException();
                return current.next();
            }
        };
    }
}
