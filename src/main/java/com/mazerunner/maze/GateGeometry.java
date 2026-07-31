package com.mazerunner.maze;
import net.minecraft.util.math.Direction;
/**
 * Geometría de las 4 puertas grandes del laberinto. Siempre están en los 4 puntos
 * cardinales exactos (norte/sur/este/oeste) del círculo: así, aunque el resto del
 * laberinto es circular, cada puerta se puede tratar como un simple rectángulo recto
 * (la curvatura del círculo es despreciable en un tramo de 8 bloques de ancho).
 * Todas las coordenadas son locales a la rejilla (antes de sumar el origen del mundo).
 */
public final class GateGeometry {
    private GateGeometry() {}
    public record Rect(int x0, int z0, int x1, int z1) {
        public int centerX() { return (x0 + x1) / 2; }
        public int centerZ() { return (z0 + z1) / 2; }
        /** Eje a lo largo del cual se recorre el rectángulo al animarlo (el lado ancho). */
        public boolean wideOnX() { return (x1 - x0) > (z1 - z0); }
    }
    public static final Direction[] DIRECTIONS = {Direction.NORTH, Direction.SOUTH, Direction.EAST, Direction.WEST};
    /** Hueco de la puerta dentro del grosor del caparazón exterior. */
    public static Rect shellRect(int c, Direction dir) {
        int half = MazeConfig.GATE_WIDTH / 2;
        return switch (dir) {
            case NORTH -> new Rect(c - half, c - MazeConfig.SHELL_OUTER,
                    c + half - 1, c - MazeConfig.INNER_RADIUS - 1);
            case SOUTH -> new Rect(c - half, c + MazeConfig.INNER_RADIUS,
                    c + half - 1, c + MazeConfig.SHELL_OUTER - 1);
            case EAST -> new Rect(c + MazeConfig.INNER_RADIUS, c - half,
                    c + MazeConfig.SHELL_OUTER - 1, c + half - 1);
            default -> new Rect(c - MazeConfig.SHELL_OUTER, c - half,
                    c - MazeConfig.INNER_RADIUS - 1, c + half - 1); // WEST
        };
    }
    /** Punto justo en el borde interior (radio {@link MazeConfig#INNER_RADIUS}) para tallar el radio hacia la puerta. */
    public static int[] innerPoint(int c, Direction dir) {
        return switch (dir) {
            case NORTH -> new int[]{c, c - MazeConfig.INNER_RADIUS};
            case SOUTH -> new int[]{c, c + MazeConfig.INNER_RADIUS};
            case EAST -> new int[]{c + MazeConfig.INNER_RADIUS, c};
            default -> new int[]{c - MazeConfig.INNER_RADIUS, c}; // WEST
        };
    }
    /** Pequeño porche exterior, siempre transitable, justo más allá del caparazón. */
    public static Rect porchRect(int c, Direction dir) {
        int half = MazeConfig.GATE_WIDTH / 2;
        int from = MazeConfig.SHELL_OUTER;
        int to = MazeConfig.SHELL_OUTER + MazeConfig.PORCH_LENGTH;
        return switch (dir) {
            case NORTH -> new Rect(c - half, c - to, c + half - 1, c - from - 1);
            case SOUTH -> new Rect(c - half, c + from, c + half - 1, c + to - 1);
            case EAST -> new Rect(c + from, c - half, c + to - 1, c + half - 1);
            default -> new Rect(c - to, c - half, c - from - 1, c + half - 1); // WEST
        };
    }
}
