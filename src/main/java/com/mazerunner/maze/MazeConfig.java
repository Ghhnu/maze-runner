package com.mazerunner.maze;
/**
 * Todas las medidas del laberinto en un solo sitio. Todo está pensado en bloques.
 * <p>
 * - Pasillos de {@link #FLOOR_W} bloques de ancho, paredes de {@link #WALL_W} de grosor.
 * - Plaza central cuadrada de {@link #HUB_SIZE}x{@link #HUB_SIZE}, encerrada por su propia
 *   muralla gigante ({@link #HUB_WALL_THICKNESS} de grosor, {@link #HUB_WALL_HEIGHT} de alto)
 *   con una única puerta grande: esa es la puerta que abre/cierra {@code /maze open}/{@code close}.
 * - Laberinto circular: todo lo que hay más allá de {@link #INNER_RADIUS} bloques desde el
 *   centro y hasta {@link #SHELL_OUTER} es el caparazón exterior sólido, salvo el túnel de
 *   salida del sector 7.
 * - Altura de muros del laberinto: {@link #WALL_HEIGHT} bloques, techo de barreras justo encima.
 */
public final class MazeConfig {
    private MazeConfig() {}
    /** Ancho de los pasillos, en bloques. */
    public static final int FLOOR_W = 8;
    /** Grosor de las paredes del laberinto, en bloques. */
    public static final int WALL_W = 5;
    /** Bloques que ocupa una celda de la rejilla base + su siguiente muro. */
    public static final int STEP = FLOOR_W + WALL_W;
    /** Celdas por lado de la rejilla cuadrada sobre la que se recorta el círculo.
     *  65 celdas (frente a las 29 originales) dan un laberinto ~5 veces más grande en área. */
    public static final int CELLS = 65;
    /** Lado de la plaza central cuadrada (zona de árboles, lago, estructuras). */
    public static final int HUB_SIZE = 240;
    /** Altura de los muros / del laberinto en bloques. */
    public static final int WALL_HEIGHT = 80;
    /** Grosor de la muralla gigante que separa la plaza central del laberinto. */
    public static final int HUB_WALL_THICKNESS = 14;
    /** Altura de la muralla gigante de la plaza (más alta que los muros normales del laberinto). */
    public static final int HUB_WALL_HEIGHT = 100;
    /** Ancho de la única puerta grande de la plaza (la que abre/cierra {@code /maze open|close}). */
    public static final int HUB_GATE_WIDTH = 12;
    /** Número de sectores que hay que alcanzar dentro del laberinto, en orden (1 -> 2 -> ... -> 7). */
    public static final int SECTOR_COUNT = 7;
    /** Lado de la sala cuadrada abierta que marca cada sector. */
    public static final int SECTOR_ROOM_SIZE = 14;
    /** Cuánto se alarga el túnel de salida del sector 7 más allá del caparazón, siempre abierto. */
    public static final int EXIT_PORCH_LENGTH = 10;
    /** Lado total de la rejilla cuadrada base, en bloques. */
    public static final int GRID_SIZE = CELLS * STEP + WALL_W;
    /** Centro (en índice de bloque local) de la rejilla base. */
    public static final int GRID_CENTER = GRID_SIZE / 2;
    /** Radio hasta el que llega el laberinto interior (plaza + pasillos), sin contar el caparazón. */
    public static final int INNER_RADIUS = GRID_CENTER - WALL_W - 6;
    /** Radio hasta la cara exterior del caparazón (grosor {@link #WALL_W}). */
    public static final int SHELL_OUTER = INNER_RADIUS + WALL_W;
    /** El laberinto cambia de zona cada 20s (a 20 ticks/segundo). */
    public static final int SHIFT_INTERVAL_TICKS = 20 * 20;
    /** Las arañas gigantes están presentes 120s por ciclo. */
    public static final int SPIDER_PRESENT_TICKS = 120 * 20;
    /** Y luego desaparecen otros 60s antes de volver a aparecer. */
    public static final int SPIDER_ABSENT_TICKS = 60 * 20;
    /** Ritmo de la animación de la puerta: una columna nueva cada X ticks. */
    public static final int GATE_ANIM_TICK_STEP = 3;
    /** Cuántos bloques de piedra se colocan por tick al construir/tocar el laberinto. */
    public static final int BLOCKS_PER_TICK = 20000;
    /** Cada cuántos ticks se repasa el interior del laberinto en busca de monstruos naturales. */
    public static final int MONSTER_SWEEP_INTERVAL_TICKS = 20;
    /** Etiqueta con la que se marcan las arañas gigantes propias, para no confundirlas con las
     *  naturales a la hora de limpiar el laberinto de monstruos. */
    public static final String SPIDER_TAG = "mazerunner_spider";
}
