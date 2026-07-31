package com.mazerunner.maze;

import net.minecraft.util.math.Direction;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.Deque;
import java.util.HashSet;
import java.util.List;
import java.util.Random;
import java.util.Set;

/**
 * "Planta" lógica del laberinto: una rejilla cuadrada de {@link MazeConfig#GRID_SIZE} bloques
 * de lado, generada con backtracking aleatorio (un laberinto perfecto: un único camino posible
 * entre dos puntos cualesquiera), a la que se le superpone la forma circular Maze Runner:
 * <p>
 * 1. La plaza central (el "hub") y su muralla NO forman parte de esta rejilla: esas celdas se
 *    excluyen del backtracking desde el principio, así que el laberinto nace ya "alrededor" de
 *    la plaza en vez de fundirse con ella.<br>
 * 2. El backtracking arranca justo al otro lado de la única puerta de la muralla (su lado se
 *    sortea en cada generación) y se talla un conector recto entre la puerta y esa celda de
 *    arranque.<br>
 * 3. Como es un laberinto perfecto (árbol de expansión), existe un único camino desde la
 *    entrada hasta cualquier celda. Se busca la rama más larga y se reparten los 7 sectores a
 *    lo largo de ella, en orden creciente: para llegar a la sala del sector 7 hay que pasar
 *    físicamente por las salas de los sectores 1, 2, 3... porque son antecesoras suyas en ese
 *    mismo camino. El sector 7 es además el único con salida al exterior (un túnel recto,
 *    siempre abierto).
 * <p>
 * El caparazón circular exterior (paredes) NO vive en esta rejilla: se calcula directamente en
 * {@link MazeBuilder} a partir de la distancia al centro.
 */
public class CircularMazeGrid {

    public record SectorRoom(int x0, int z0, int x1, int z1, int number, Direction signFace) {
        public int centerX() { return (x0 + x1) / 2; }
        public int centerZ() { return (z0 + z1) / 2; }
    }

    public final int size = MazeConfig.GRID_SIZE;
    public final int center = MazeConfig.GRID_CENTER;
    public final boolean[][] open = new boolean[size][size];
    public final List<SectorRoom> sectorRooms = new ArrayList<>();

    /** Lado de la muralla de la plaza en el que está la única puerta grande. */
    public Direction hubGateDirection;
    /** Dirección (vector unitario) desde el centro hacia la sala del sector 7: por ahí sale el
     *  túnel de salida, siempre abierto. */
    public double exitDirX, exitDirZ;
    /** Distancia del centro de la sala del sector 7 al centro del laberinto. */
    public double exitStartRadius;

    private final Set<Long> criticalCells = new HashSet<>();

    private static final Direction[] CARVE_DIRS = {Direction.EAST, Direction.WEST, Direction.SOUTH, Direction.NORTH};

    public CircularMazeGrid(long seed) {
        generate(seed);
    }

    private void generate(long seed) {
        Random rnd = new Random(seed);
        int n = MazeConfig.CELLS;
        boolean[][] visited = new boolean[n][n];
        int[][] parentCx = new int[n][n];
        int[][] parentCz = new int[n][n];
        int[][] depth = new int[n][n];
        for (int[] row : parentCx) java.util.Arrays.fill(row, -1);
        for (int[] row : parentCz) java.util.Arrays.fill(row, -1);

        int hubHalf = MazeConfig.HUB_SIZE / 2;
        int ringOuterHalf = hubHalf + MazeConfig.HUB_WALL_THICKNESS;
        int exclusionHalf = ringOuterHalf + 2;

        // 1. Excluir del backtracking las celdas que caen bajo la plaza y su muralla.
        for (int cx = 0; cx < n; cx++) {
            for (int cz = 0; cz < n; cz++) {
                int fx = cellCenterBlock(cx), fz = cellCenterBlock(cz);
                if (Math.abs(fx - center) <= exclusionHalf && Math.abs(fz - center) <= exclusionHalf) {
                    visited[cx][cz] = true;
                }
            }
        }

        // 2. Sortear el lado de la única puerta grande y la celda de entrada al laberinto,
        //    justo al otro lado de la muralla.
        Direction[] sides = {Direction.NORTH, Direction.SOUTH, Direction.EAST, Direction.WEST};
        hubGateDirection = sides[rnd.nextInt(sides.length)];
        int entryDist = ringOuterHalf + MazeConfig.STEP;
        int entryBlockX = center + hubGateDirection.getOffsetX() * entryDist;
        int entryBlockZ = center + hubGateDirection.getOffsetZ() * entryDist;
        int entryCx = clampCell(cellIndexNear(entryBlockX));
        int entryCz = clampCell(cellIndexNear(entryBlockZ));
        visited[entryCx][entryCz] = false;

        // 3. Backtracking aleatorio normal (un laberinto perfecto), arrancando en la entrada,
        //    guardando padre y profundidad de cada celda para poder reconstruir el camino más
        //    largo después.
        Deque<int[]> stack = new ArrayDeque<>();
        visited[entryCx][entryCz] = true;
        carveCellFloor(entryCx, entryCz);
        stack.push(new int[]{entryCx, entryCz});

        while (!stack.isEmpty()) {
            int[] cur = stack.peek();
            int cx = cur[0], cz = cur[1];
            List<Direction> dirs = new ArrayList<>(List.of(CARVE_DIRS));
            Collections.shuffle(dirs, rnd);

            boolean carved = false;
            for (Direction d : dirs) {
                int nx = cx + d.getOffsetX(), nz = cz + d.getOffsetZ();
                if (nx < 0 || nz < 0 || nx >= n || nz >= n) continue;
                if (visited[nx][nz]) continue;

                visited[nx][nz] = true;
                carveCellFloor(nx, nz);
                carveConnector(cx, cz, d);
                parentCx[nx][nz] = cx;
                parentCz[nx][nz] = cz;
                depth[nx][nz] = depth[cx][cz] + 1;
                stack.push(new int[]{nx, nz});
                carved = true;
                break;
            }
            if (!carved) stack.pop();
        }

        // 4. Conector recto entre la cara exterior de la muralla y la celda de entrada.
        int[] outer = GateGeometry.hubOuterPoint(center, hubGateDirection);
        carveSpoke(outer[0], outer[1], cellCenterBlock(entryCx), cellCenterBlock(entryCz));
        markCriticalCell(entryCx, entryCz);

        // 5. Elegir el final de la rama: entre las celdas alcanzables sin salir del radio donde
        //    cabe una sala de sector, la más profunda (y, entre las empatadas, la más lejana del
        //    centro, para que el túnel de salida del sector 7 sea corto).
        int maxSectorRadius = MazeConfig.INNER_RADIUS - MazeConfig.SECTOR_ROOM_SIZE / 2 - 6;
        int leafCx = entryCx, leafCz = entryCz;
        int bestDepth = -1;
        double bestDist = -1;
        for (int cx = 0; cx < n; cx++) {
            for (int cz = 0; cz < n; cz++) {
                if (!visited[cx][cz]) continue;
                if (parentCx[cx][cz] == -1 && !(cx == entryCx && cz == entryCz)) continue; // nunca conectada
                double dist = blockDistFromCenter(cx, cz);
                if (dist > maxSectorRadius) continue;
                int d = depth[cx][cz];
                if (d > bestDepth || (d == bestDepth && dist > bestDist)) {
                    bestDepth = d;
                    bestDist = dist;
                    leafCx = cx;
                    leafCz = cz;
                }
            }
        }

        // 6. Reconstruir el camino único desde la entrada hasta esa celda, siguiendo los padres.
        List<int[]> chain = new ArrayList<>();
        int wcx = leafCx, wcz = leafCz;
        while (true) {
            chain.add(new int[]{wcx, wcz});
            if (wcx == entryCx && wcz == entryCz) break;
            int pcx = parentCx[wcx][wcz], pcz = parentCz[wcx][wcz];
            if (pcx == -1) break; // no debería pasar, salvaguarda
            wcx = pcx; wcz = pcz;
        }
        Collections.reverse(chain); // ahora va de la entrada (índice 0) al sector 7 (último índice)
        for (int[] c : chain) markCriticalCell(c[0], c[1]);

        // 7. Repartir los 7 sectores a lo largo del camino, en orden creciente; el último es
        //    siempre el propio final de la rama (el sector 7).
        int last = chain.size() - 1;
        int count = MazeConfig.SECTOR_COUNT;
        int[] idx = new int[count];
        for (int i = 0; i < count; i++) {
            idx[i] = Math.round((i + 1) * last / (float) count);
        }
        // Pasada hacia delante: cada índice va al menos 1 por delante del anterior (mínimo i+1).
        idx[0] = Math.max(idx[0], 1);
        for (int i = 1; i < count; i++) {
            idx[i] = Math.max(idx[i], idx[i - 1] + 1);
        }
        // El último es siempre el final real de la rama: ahí vive el sector 7.
        idx[count - 1] = last;
        // Pasada hacia atrás: si al forzar el último algo se quedó igual o por delante, se retrasa.
        for (int i = count - 2; i >= 0; i--) {
            if (idx[i] >= idx[i + 1]) idx[i] = idx[i + 1] - 1;
        }
        for (int i = 0; i < count; i++) idx[i] = Math.max(idx[i], 0);

        for (int i = 0; i < MazeConfig.SECTOR_COUNT; i++) {
            int k = Math.min(idx[i], last);
            int[] cell = chain.get(k);
            int[] pred = chain.get(Math.max(0, k - 1));
            int[] succ = (k + 1 <= last) ? chain.get(k + 1) : null;

            Direction dirToPred = directionBetween(cell, pred);
            Direction dirToSucc = succ != null ? directionBetween(cell, succ) : null;

            addSectorRoom(i + 1, cell[0], cell[1], dirToPred, dirToSucc, ringOuterHalf);
        }

        // 8. El sector 7 (el último) marca además la dirección del túnel de salida: recto hacia
        //    fuera desde el centro del laberinto, siempre abierto.
        SectorRoom exitRoom = sectorRooms.get(MazeConfig.SECTOR_COUNT - 1);
        double edx = exitRoom.centerX() - center, edz = exitRoom.centerZ() - center;
        double elen = Math.hypot(edx, edz);
        if (elen < 0.001) { edx = 1; edz = 0; elen = 1; }
        exitDirX = edx / elen;
        exitDirZ = edz / elen;
        exitStartRadius = elen;
    }

    // ------------------------------------------------------------------
    // Sectores
    // ------------------------------------------------------------------

    private void addSectorRoom(int number, int cx, int cz, Direction dirToPred, Direction dirToSucc, int ringOuterHalf) {
        int bx = cellCenterBlock(cx), bz = cellCenterBlock(cz);
        int half = MazeConfig.SECTOR_ROOM_SIZE / 2;
        int x0 = clampGrid(bx - half), z0 = clampGrid(bz - half);
        int x1 = clampGrid(bx + half), z1 = clampGrid(bz + half);

        forceOpenSquareAvoidingHub(x0, z0, x1 - x0, z1 - z0, ringOuterHalf);

        Direction signFace = pickSignFace(bx, bz, dirToPred, dirToSucc);
        forceSignBacking(x0, z0, x1, z1, signFace);

        sectorRooms.add(new SectorRoom(x0, z0, x1, z1, number, signFace));
    }

    /** Elige, de las 4 caras posibles de la sala, una que NO sea por donde entra o sale el
     *  camino (así el número grabado siempre cae sobre pared sólida garantizada), prefiriendo
     *  entre las que quedan la más alejada del centro. */
    private Direction pickSignFace(int bx, int bz, Direction dirToPred, Direction dirToSucc) {
        Direction best = null;
        double bestDist = -1;
        for (Direction d : CARVE_DIRS) {
            if (d == dirToPred || d == dirToSucc) continue;
            int fx = bx + d.getOffsetX() * (MazeConfig.SECTOR_ROOM_SIZE / 2 + 1);
            int fz = bz + d.getOffsetZ() * (MazeConfig.SECTOR_ROOM_SIZE / 2 + 1);
            double dist = Math.hypot(fx - center, fz - center);
            if (dist > bestDist) { bestDist = dist; best = d; }
        }
        if (best == null) best = Direction.NORTH; // salvaguarda, no debería ocurrir (a lo sumo se excluyen 2 de 4)
        return best;
    }

    /** Fuerza a sólida la franja de pared, justo fuera de la sala, donde se va a grabar el
     *  número: así nunca queda "flotando" sobre un hueco abierto por azar. */
    private void forceSignBacking(int x0, int z0, int x1, int z1, Direction face) {
        boolean varyX = (face == Direction.NORTH || face == Direction.SOUTH);
        int cxCenter = (x0 + x1) / 2, czCenter = (z0 + z1) / 2;
        int faceCoord = switch (face) {
            case EAST -> x1 + 1;
            case WEST -> x0 - 1;
            case SOUTH -> z1 + 1;
            default -> z0 - 1; // NORTH
        };
        for (int off = -2; off <= 2; off++) {
            int x = varyX ? cxCenter + off : faceCoord;
            int z = varyX ? faceCoord : czCenter + off;
            if (x < 0 || z < 0 || x >= size || z >= size) continue;
            open[x][z] = false;
        }
    }

    // ------------------------------------------------------------------
    // Utilidades de rejilla
    // ------------------------------------------------------------------

    private void markCriticalCell(int cx, int cz) {
        criticalCells.add(cx * 100000L + cz);
    }

    /** ¿Es esta celda (índice de celda, no de bloque) parte del único camino garantizado entre
     *  la plaza y el sector 7? Esas celdas nunca las toca el cambio periódico del laberinto. */
    public boolean isCriticalCell(int cx, int cz) {
        return criticalCells.contains(cx * 100000L + cz);
    }

    /** Versión en coordenadas de bloque, con un pequeño margen de celdas alrededor, para que
     *  {@link MazeInstance} pueda comprobar de forma barata si un punto candidato al cambio de
     *  laberinto cae demasiado cerca del camino crítico. */
    public boolean isNearCriticalPath(int blockX, int blockZ, int marginCells) {
        int cx = cellIndexNear(blockX), cz = cellIndexNear(blockZ);
        for (int dx = -marginCells; dx <= marginCells; dx++) {
            for (int dz = -marginCells; dz <= marginCells; dz++) {
                int ncx = cx + dx, ncz = cz + dz;
                if (ncx < 0 || ncz < 0 || ncx >= MazeConfig.CELLS || ncz >= MazeConfig.CELLS) continue;
                if (isCriticalCell(ncx, ncz)) return true;
            }
        }
        return false;
    }

    private static Direction directionBetween(int[] from, int[] to) {
        if (to[0] > from[0]) return Direction.EAST;
        if (to[0] < from[0]) return Direction.WEST;
        if (to[1] > from[1]) return Direction.SOUTH;
        return Direction.NORTH;
    }

    private double blockDistFromCenter(int cx, int cz) {
        return Math.hypot(cellCenterBlock(cx) - center, cellCenterBlock(cz) - center);
    }

    private int clampGrid(int v) {
        return Math.max(1, Math.min(size - 2, v));
    }

    private int clampCell(int c) {
        return Math.max(0, Math.min(MazeConfig.CELLS - 1, c));
    }

    public int cellBase(int c) {
        return c * MazeConfig.STEP + MazeConfig.WALL_W;
    }

    public int cellCenterBlock(int c) {
        return cellBase(c) + MazeConfig.FLOOR_W / 2;
    }

    /** Índice de celda más cercano a una coordenada de bloque (inverso aproximado de {@link #cellBase}). */
    private int cellIndexNear(int blockCoord) {
        return Math.round((blockCoord - MazeConfig.WALL_W - MazeConfig.FLOOR_W / 2f) / MazeConfig.STEP);
    }

    private void carveCellFloor(int cx, int cz) {
        int bx = cellBase(cx), bz = cellBase(cz);
        for (int x = bx; x < bx + MazeConfig.FLOOR_W; x++) {
            for (int z = bz; z < bz + MazeConfig.FLOOR_W; z++) {
                open[x][z] = true;
            }
        }
    }

    private void carveConnector(int cx, int cz, Direction dir) {
        int bx = cellBase(cx), bz = cellBase(cz);
        int x0, x1, z0, z1;
        if (dir == Direction.EAST) { x0 = bx + MazeConfig.FLOOR_W; x1 = x0 + MazeConfig.WALL_W - 1; z0 = bz; z1 = bz + MazeConfig.FLOOR_W - 1; }
        else if (dir == Direction.WEST) { x0 = bx - MazeConfig.WALL_W; x1 = bx - 1; z0 = bz; z1 = bz + MazeConfig.FLOOR_W - 1; }
        else if (dir == Direction.SOUTH) { x0 = bx; x1 = bx + MazeConfig.FLOOR_W - 1; z0 = bz + MazeConfig.FLOOR_W; z1 = z0 + MazeConfig.WALL_W - 1; }
        else { x0 = bx; x1 = bx + MazeConfig.FLOOR_W - 1; z0 = bz - MazeConfig.WALL_W; z1 = bz - 1; } // NORTH
        for (int x = Math.max(0, x0); x <= Math.min(size - 1, x1); x++) {
            for (int z = Math.max(0, z0); z <= Math.min(size - 1, z1); z++) {
                open[x][z] = true;
            }
        }
    }

    private void forceOpenSquareAvoidingHub(int x0, int z0, int w, int h, int ringOuterHalf) {
        for (int x = Math.max(0, x0); x < Math.min(size, x0 + w); x++) {
            for (int z = Math.max(0, z0); z < Math.min(size, z0 + h); z++) {
                if (Math.abs(x - center) <= ringOuterHalf && Math.abs(z - center) <= ringOuterHalf) continue;
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
