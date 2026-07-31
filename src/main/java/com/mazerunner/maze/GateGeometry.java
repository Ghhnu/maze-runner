package com.mazerunner.maze;
import net.minecraft.util.math.Direction;
/**
 * Geometría de la única puerta grande del laberinto: la que hay en la muralla que rodea la
 * plaza central. Vive siempre en uno de los 4 puntos cardinales de esa muralla cuadrada (el
 * lado se elige al generar el laberinto), así que se puede tratar como un simple rectángulo
 * recto. Todas las coordenadas son locales a la rejilla (antes de sumar el origen del mundo).
 */
public final class GateGeometry {
    private GateGeometry() {}
    public record Rect(int x0, int z0, int x1, int z1) {
        public int centerX() { return (x0 + x1) / 2; }
        public int centerZ() { return (z0 + z1) / 2; }
        /** Eje a lo largo del cual se recorre el rectángulo al animarlo (el lado ancho). */
        public boolean wideOnX() { return (x1 - x0) > (z1 - z0); }
    }

    /** Hueco de la puerta dentro del grosor de la muralla de la plaza. */
    public static Rect hubGateRect(int c, Direction dir) {
        int half = MazeConfig.HUB_GATE_WIDTH / 2;
        int hubHalf = MazeConfig.HUB_SIZE / 2;
        int innerFace = hubHalf;
        int outerFace = hubHalf + MazeConfig.HUB_WALL_THICKNESS;
        return switch (dir) {
            case NORTH -> new Rect(c - half, c - outerFace, c + half - 1, c - innerFace - 1);
            case SOUTH -> new Rect(c - half, c + innerFace, c + half - 1, c + outerFace - 1);
            case EAST -> new Rect(c + innerFace, c - half, c + outerFace - 1, c + half - 1);
            default -> new Rect(c - outerFace, c - half, c - innerFace - 1, c + half - 1); // WEST
        };
    }

    /** Punto justo en la cara exterior de la muralla de la plaza, por donde sale el conector
     *  hacia la primera celda del laberinto propiamente dicho. */
    public static int[] hubOuterPoint(int c, Direction dir) {
        int outerFace = MazeConfig.HUB_SIZE / 2 + MazeConfig.HUB_WALL_THICKNESS;
        return switch (dir) {
            case NORTH -> new int[]{c, c - outerFace};
            case SOUTH -> new int[]{c, c + outerFace};
            case EAST -> new int[]{c + outerFace, c};
            default -> new int[]{c - outerFace, c}; // WEST
        };
    }

    /** Punto justo en la cara interior (la que da a la plaza) de la muralla, para la animación. */
    public static int[] hubInnerPoint(int c, Direction dir) {
        int innerFace = MazeConfig.HUB_SIZE / 2;
        return switch (dir) {
            case NORTH -> new int[]{c, c - innerFace};
            case SOUTH -> new int[]{c, c + innerFace};
            case EAST -> new int[]{c + innerFace, c};
            default -> new int[]{c - innerFace, c}; // WEST
        };
    }
}
