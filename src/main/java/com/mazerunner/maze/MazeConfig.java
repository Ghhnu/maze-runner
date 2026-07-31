package com.mazerunner.maze;
/**
 * Todas las medidas del laberinto en un solo sitio. Todo está pensado en bloques.
 * <p>
 * - Pasillos de {@link #FLOOR_W} bloques de ancho, paredes de {@link #WALL_W} de grosor.
 * - Plaza central cuadrada de {@link #HUB_SIZE}x{@link #HUB_SIZE}.
 * - Laberinto circular: todo lo que hay más allá de {@link #INNER_RADIUS} bloques desde el
 *   centro y hasta {@link #SHELL_OUTER} es el caparazón exterior sólido (con las 4 puertas).
 * - Altura de muros: {@link #WALL_HEIGHT} bloques, techo de barreras justo encima.
 */
public final class MazeConfig {
    private MazeConfig() {}
    /** Ancho de los pasillos, en bloques. */
    public static final int FLOOR_W = 8;
    /** Grosor de las paredes del laberinto, en bloques. */
    public static final int WALL_W = 5;
    /** Bloques que ocupa una celda de la rejilla base + su siguiente muro. */
    public static final int STEP = FLOOR_W + WALL_W;
    /** Celdas por lado de la rejilla cuadrada sobre la que se recorta el círculo. */
    public static final int CELLS = 29;
    /** Lado de la plaza central cuadrada (zona de árboles, lago, estructuras). */
    public static final int HUB_SIZE = 200;
    /** Altura de los muros / del laberinto en bloques. */
    public static final int WALL_HEIGHT = 80;
    /** Número de sectores que hay que alcanzar dentro del laberinto. */
    public static final int SECTOR_COUNT = 7;
    /** Lado de la sala cuadrada abierta que marca cada sector. */
    public static final int SECTOR_ROOM_SIZE = 14;
    /** Ancho de cada una de las 4 puertas grandes. */
    public static final int GATE_WIDTH = 8;
    /** Cuánto sobresale el pequeño porche exterior más allá del caparazón, siempre transitable. */
    public static final int PORCH_LENGTH = 6;
    /** Lado total de la rejilla cuadrada base, en bloques. */
    public static final int GRID_SIZE = CELLS * STEP + WALL_W;
    /** Centro (en índice de bloque local) de la rejilla base. */
    public static final int GRID_CENTER = GRID_SIZE / 2;
    /** Radio hasta el que llega el laberinto interior (plaza + pasillos), sin contar el caparazón. */
    public static final int INNER_RADIUS = GRID_CENTER - WALL_W - 6;
    /** Radio hasta la cara exterior del caparazón (grosor {@link #WALL_W}). */
    public static final int SHELL_OUTER = INNER_RADIUS + WALL_W;
    /** El laberinto cambia de sector cada 120s (a 20 ticks/segundo). */
    public static final int SHIFT_INTERVAL_TICKS = 120 * 20;
    /** Duración de cada fase del ciclo de arañas (aparecen 120s, desaparecen 120s). */
    public static final int MOB_PHASE_TICKS = 120 * 20;
    /** Ritmo de la animación de las puertas: una columna nueva cada X ticks. */
    public static final int GATE_ANIM_TICK_STEP = 3;
    /** Cuántos bloques de piedra se colocan por tick al construir/tocar el laberinto. */
    public static final int BLOCKS_PER_TICK = 12000;
}
