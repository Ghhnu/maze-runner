package com.mazerunner.maze;

import net.minecraft.util.math.Direction;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Deque;
import java.util.List;
import java.util.Random;

/**
 * "Planta" lógica del laberinto: una rejilla cuadrada de {@link MazeConfig#GRID_SIZE} bloques
 * de lado, generada con backtracking aleatorio (igual que un laberinto perfecto normal), a la
 * que luego se le superponen tres cosas para darle forma de laberinto circular Maze Runner:
 * <p>
 * 1. Se vacía por completo la plaza central cuadrada (el "hub").<br>
 * 2. Se tallan 11 "radios" garantizados (uno por cada uno de los 7 sectores + uno por cada
 *    una de las 4 puertas) para que siempre exista un camino desde el centro hasta cada uno,
 *    aunque el backtracking aleatorio no hubiera abierto paso por sí solo.<br>
 * 3. Se marcan 7 salas de sector (cuadrados abiertos) cerca del borde exterior.
 * <p>
 * El caparazón circular exterior (paredes + las 4 puertas) NO vive en esta rejilla: se
 * calcula directamente en {@link MazeBuilder} a partir de la distancia al centro, usando
 * {@link GateGeometry} para las puertas.
 */
public class CircularMazeGrid {

    public record SectorRoom(int x0, int z0, int x1, int z1, int number) {
        public int centerX() { return (x0 + x1) / 2; }
        public int centerZ() { return (z0 + z1) / 2; }
    }

    public final int size = MazeConfig.GRID_SIZE;
    public final int center = MazeConfig.GRID_CENTER;
    public final boolean[][] open = new boolean[size][size];
    public final List<SectorRoom> sectorRooms = new ArrayList<>();

    private static final int[] DX = {1, -1, 0, 0};
    private static final int[] DZ = {0, 0, 1, -1};

    public CircularMazeGrid(long seed) {
        generate(seed);
    }

    private void generate(long seed) {
        Random rnd = new Random(seed);
        int n = MazeConfig.CELLS;
        boolean[][] visited = new boolean[n][n];
        Deque<int[]> stack = new ArrayDeque<>();

        visited[0][0] = true;
        carveCellFloor(0, 0);
        stack.push(new int[]{0, 0});

        while (!stack.isEmpty()) {
            int[] cur = stack.peek();
            int cx = cur[0], cz = cur[1];
            List<Integer> dirs = new ArrayList<>(List.of(0, 1, 2, 3));
            Collections.shuffle(dirs, rnd);

            boolean carved = false;
            for (int d : dirs) {
                int nx = cx + DX[d], nz = cz + DZ[d];
                if (nx < 0 || nz < 0 || nx >= n || nz >= n) continue;
                if (visited[nx][nz]) continue;

                visited[nx][nz] = true;
                carveCellFloor(nx, nz);
                carveConnector(cx, cz, d);
                stack.push(new int[]{nx, nz});
                carved = true;
                break;
            }
            if (!carved) stack.pop();
        }

        // 1. Vaciar la plaza central por completo.
        int hubHalf = MazeConfig.HUB_SIZE / 2;
        forceOpenSquare(center - hubHalf, center - hubHalf, MazeConfig.HUB_SIZE, MazeConfig.HUB_SIZE);

        // 2. Radios garantizados hacia las 4 puertas (siempre en los puntos cardinales).
        for (Direction dir : GateGeometry.DIRECTIONS) {
            int[] p = GateGeometry.innerPoint(center, dir);
            carveSpoke(center, center, p[0], p[1]);
        }

        // 3. Los 7 sectores, repartidos en ángulo con un poco de aleatoriedad, cada uno con
        //    su propio radio garantizado desde el centro.
        int roomRadius = MazeConfig.INNER_RADIUS - 12;
        for (int i = 0; i < MazeConfig.SECTOR_COUNT; i++) {
            double baseAngle = i * (360.0 / MazeConfig.SECTOR_COUNT);
            double jitter = (rnd.nextDouble() * 16) - 8;
            double angle = Math.toRadians(baseAngle + jitter);
            int cx = center + (int) Math.round(Math.cos(angle) * roomRadius);
            int cz = center + (int) Math.round(Math.sin(angle) * roomRadius);

            int half = MazeConfig.SECTOR_ROOM_SIZE / 2;
            int x0 = clampGrid(cx - half), z0 = clampGrid(cz - half);
            int x1 = clampGrid(cx + half), z1 = clampGrid(cz + half);
            sectorRooms.add(new SectorRoom(x0, z0, x1, z1, i + 1));

            forceOpenSquare(x0, z0, x1 - x0, z1 - z0);
            carveSpoke(center, center, (x0 + x1) / 2, (z0 + z1) / 2);
        }
    }

    private int clampGrid(int v) {
        return Math.max(1, Math.min(size - 2, v));
    }

    public int cellBase(int c) {
        return c * MazeConfig.STEP + MazeConfig.WALL_W;
    }

    private void carveCellFloor(int cx, int cz) {
        int bx = cellBase(cx), bz = cellBase(cz);
        for (int x = bx; x < bx + MazeConfig.FLOOR_W; x++) {
            for (int z = bz; z < bz + MazeConfig.FLOOR_W; z++) {
                open[x][z] = true;
            }
        }
    }

    private void carveConnector(int cx, int cz, int dir) {
        int bx = cellBase(cx), bz = cellBase(cz);
        int x0, x1, z0, z1;
        switch (dir) {
            case 0 -> { x0 = bx + MazeConfig.FLOOR_W; x1 = x0 + MazeConfig.WALL_W - 1; z0 = bz; z1 = bz + MazeConfig.FLOOR_W - 1; }
            case 1 -> { x0 = bx - MazeConfig.WALL_W; x1 = bx - 1; z0 = bz; z1 = bz + MazeConfig.FLOOR_W - 1; }
            case 2 -> { x0 = bx; x1 = bx + MazeConfig.FLOOR_W - 1; z0 = bz + MazeConfig.FLOOR_W; z1 = z0 + MazeConfig.WALL_W - 1; }
            default -> { x0 = bx; x1 = bx + MazeConfig.FLOOR_W - 1; z0 = bz - MazeConfig.WALL_W; z1 = bz - 1; }
        }
        for (int x = Math.max(0, x0); x <= Math.min(size - 1, x1); x++) {
            for (int z = Math.max(0, z0); z <= Math.min(size - 1, z1); z++) {
                open[x][z] = true;
            }
        }
    }

    private void forceOpenSquare(int x0, int z0, int w, int h) {
        for (int x = Math.max(0, x0); x < Math.min(size, x0 + w); x++) {
            for (int z = Math.max(0, z0); z < Math.min(size, z0 + h); z++) {
                open[x][z] = true;
            }
        }
    }

    /** Talla una línea recta de {@link MazeConfig#FLOOR_W} bloques de ancho entre dos puntos. */
    private void carveSpoke(int x0, int z0, int x1, int z1) {
        int half = MazeConfig.FLOOR_W / 2;
        double dist = Math.hypot(x1 - x0, z1 - z0);
        int steps = Math.max(1, (int) Math.ceil(dist));
        for (int s = 0; s <= steps; s++) {
            double t = (double) s / steps;
            int px = (int) Math.round(x0 + (x1 - x0) * t);
            int pz = (int) Math.round(z0 + (z1 - z0) * t);
            for (int dx = -half; dx < MazeConfig.FLOOR_W - half; dx++) {
                for (int dz = -half; dz < MazeConfig.FLOOR_W - half; dz++) {
                    int fx = px + dx, fz = pz + dz;
                    if (fx < 0 || fz < 0 || fx >= size || fz >= size) continue;
                    open[fx][fz] = true;
                }
            }
        }
    }
}
