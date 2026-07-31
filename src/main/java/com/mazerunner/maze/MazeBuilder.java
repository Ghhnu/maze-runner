package com.mazerunner.maze;

import net.minecraft.block.BlockState;
import net.minecraft.block.Blocks;
import net.minecraft.server.command.ServerCommandSource;
import net.minecraft.server.world.ServerWorld;
import net.minecraft.text.Text;
import net.minecraft.util.math.BlockPos;
import net.minecraft.util.math.Direction;

import java.util.ArrayList;
import java.util.Collections;
import java.util.Iterator;
import java.util.List;
import java.util.NoSuchElementException;
import java.util.Random;

/**
 * Traduce una {@link CircularMazeGrid} a bloques reales: suelo, paredes, techo de barreras,
 * plaza central con vegetación/lago/estructuras, las 7 salas de sector con su número grabado,
 * las 4 puertas grandes (cerradas al generar), el porche + desierto de la salida y algunos
 * símbolos sueltos grabados en las paredes del laberinto.
 */
public final class MazeBuilder {

    private static final Direction[] SIDE_DIRS = {Direction.EAST, Direction.WEST, Direction.SOUTH, Direction.NORTH};

    private MazeBuilder() {}

    public static void generate(ServerWorld world, BlockPos center, ServerCommandSource source) {
        long seed = new Random().nextLong() ^ System.nanoTime();
        CircularMazeGrid grid = new CircularMazeGrid(seed);
        // Random separada para el cuerpo perezoso del laberinto: se va consumiendo poco a poco
        // a lo largo de varios minutos (a medida que se colocan bloques), así que no debe
        // compartirse con la que usan las decoraciones de abajo (esas sí se calculan de golpe).
        Random gridRnd = new Random(seed ^ 0x9E3779B97F4A7C15L);
        Random rnd = new Random(seed ^ 0x2545F4914F6CDD1DL);

        int origX = center.getX() - grid.center;
        int origZ = center.getZ() - grid.center;
        int baseY = center.getY();

        // Si ya había un laberinto construido, lo borramos primero (se encola antes que la
        // construcción nueva, así que se ejecuta primero gracias al orden FIFO de la cola).
        // clearJobs() también es perezoso, por la misma razón que el cuerpo del laberinto.
        MazeInstance old = MazeLiveManager.get(world);
        if (old != null) {
            MazeBuildQueue.submit(old.clearJobs(), null);
            MazeLiveManager.clear(world);
        }

        // Pasada ligera sobre la rejilla: solo cuenta cuántas celdas hay (para el mensaje de
        // progreso) y anota dónde pueden ir símbolos grabados en las paredes. No crea ningún
        // BlockJob todavía, así que cuesta un puñado de milisegundos aunque el laberinto sea
        // enorme.
        CellScan scan = scanCells(grid);

        List<MazeBuildQueue.BlockJob> extraJobs = new ArrayList<>();

        // --- Porches exteriores de las 4 puertas (siempre transitables, sin techo) ---
        List<MazeInstance.Gate> gates = new ArrayList<>();
        for (Direction dir : GateGeometry.DIRECTIONS) {
            GateGeometry.Rect shellRect = GateGeometry.shellRect(grid.center, dir);
            gates.add(buildGateData(world, origX, origZ, baseY, dir, shellRect));

            GateGeometry.Rect porch = GateGeometry.porchRect(grid.center, dir);
            for (int x = porch.x0(); x <= porch.x1(); x++) {
                for (int z = porch.z0(); z <= porch.z1(); z++) {
                    BlockPos p = new BlockPos(origX + x, baseY, origZ + z);
                    extraJobs.add(new MazeBuildQueue.BlockJob(world, p, pickMazeFloor(rnd)));
                    for (int y = 1; y <= MazeConfig.WALL_HEIGHT; y++) {
                        extraJobs.add(new MazeBuildQueue.BlockJob(world, p.up(y), Blocks.AIR.getDefaultState()));
                    }
                }
            }
        }

        // --- Desierto de salida, justo más allá del porche de la puerta sur ---
        buildDesert(extraJobs, world, grid, origX, origZ, baseY, rnd);

        // --- Decoración de la plaza central: árboles, vegetación, lago, estructuras ---
        buildHubDecorations(extraJobs, world, grid, origX, origZ, baseY, rnd);

        // --- Números de sector grabados en la pared exterior de cada sala ---
        for (CircularMazeGrid.SectorRoom room : grid.sectorRooms) {
            stampSectorNumber(extraJobs, world, grid, origX, origZ, baseY, room);
        }

        // --- Símbolos sueltos grabados en algunas paredes del laberinto ---
        stampSymbols(extraJobs, world, grid, origX, origZ, baseY, rnd, scan.symbolCandidates());

        int gridJobCount = scan.cellCount() * (MazeConfig.WALL_HEIGHT + 2);
        int totalJobs = gridJobCount + extraJobs.size();

        source.sendFeedback(() -> Text.literal(String.format(
                "§6[MazeRunner] §fConstruyendo laberinto circular en (%d, %d, %d)... (%d bloques, esto puede tardar varios minutos)",
                center.getX(), center.getY(), center.getZ(), totalJobs)), false);

        long startMillis = System.currentTimeMillis();

        MazeInstance instance = new MazeInstance(world, grid, origX, origZ, baseY, seed, gates);

        // El cuerpo del laberinto (suelo + paredes + techo de todas las celdas) es, con
        // diferencia, la parte más grande: para un laberinto de este tamaño son decenas de
        // millones de bloques. En vez de precalcularlos todos en una List (lo que reventaba la
        // memoria antes de colocar ni uno), un GridJobIterator los calcula uno a uno, justo
        // antes de que MazeBuildQueue los coloque, tick a tick. Encadenado con los "extras"
        // (porches, desierto, decoración, números, símbolos) preserva el mismo orden que antes.
        Iterator<MazeBuildQueue.BlockJob> gridIt = new GridJobIterator(grid, world, origX, origZ, baseY, gridRnd);
        Iterator<MazeBuildQueue.BlockJob> combined = MazeBuildQueue.chain(List.of(gridIt, extraJobs.iterator()));

        MazeBuildQueue.submit(combined, () -> {
            double seconds = (System.currentTimeMillis() - startMillis) / 1000.0;
            world.getServer().getPlayerManager().broadcast(Text.literal(String.format(
                    "§6[MazeRunner] §fLaberinto terminado en %.1fs. Las puertas están cerradas: usa §b/maze open§f para abrirlas.",
                    seconds)), false);
            MazeLiveManager.register(instance);
        });
    }

    // ------------------------------------------------------------------
    // Cuerpo del laberinto (perezoso)
    // ------------------------------------------------------------------

    /** Resultado de la pasada ligera sobre la rejilla: cuántas celdas hay dentro del caparazón
     *  (para el mensaje de progreso) y dónde puede ir un símbolo grabado (paredes cerradas del
     *  laberinto propiamente dicho, junto a un pasillo abierto). */
    private record CellScan(int cellCount, List<int[]> symbolCandidates) {}

    private static CellScan scanCells(CircularMazeGrid grid) {
        int hubHalf = MazeConfig.HUB_SIZE / 2;
        int cellCount = 0;
        List<int[]> symbolCandidates = new ArrayList<>();

        for (int x = 0; x < grid.size; x++) {
            for (int z = 0; z < grid.size; z++) {
                double dist = Math.hypot(x - grid.center, z - grid.center);
                if (dist > MazeConfig.SHELL_OUTER) continue;
                cellCount++;

                boolean shell = dist > MazeConfig.INNER_RADIUS;
                boolean hub = Math.abs(x - grid.center) < hubHalf && Math.abs(z - grid.center) < hubHalf;
                CircularMazeGrid.SectorRoom room = roomAt(grid, x, z);

                boolean open;
                if (shell) open = false;
                else if (hub || room != null) open = true;
                else open = grid.open[x][z];

                if (open || shell || hub) continue; // solo interesa: pared cerrada del laberinto

                for (int d = 0; d < SIDE_DIRS.length; d++) {
                    int nx = x + SIDE_DIRS[d].getOffsetX();
                    int nz = z + SIDE_DIRS[d].getOffsetZ();
                    if (nx < 0 || nz < 0 || nx >= grid.size || nz >= grid.size) continue;
                    double ndist = Math.hypot(nx - grid.center, nz - grid.center);
                    if (ndist > MazeConfig.INNER_RADIUS) continue;
                    if (grid.open[nx][nz]) {
                        symbolCandidates.add(new int[]{x, z, d});
                    }
                }
            }
        }
        return new CellScan(cellCount, symbolCandidates);
    }

    /**
     * Genera perezosamente, celda a celda, todos los {@link MazeBuildQueue.BlockJob} del
     * "cuerpo" del laberinto (suelo + columna vertical + barrera de techo de cada celda dentro
     * del caparazón). Solo recuerda el estado de la celda actual, así que el consumo de memoria
     * es constante sin importar lo grande que sea el laberinto — imprescindible aquí, donde son
     * decenas de millones de bloques.
     */
    private static final class GridJobIterator implements Iterator<MazeBuildQueue.BlockJob> {
        private final CircularMazeGrid grid;
        private final ServerWorld world;
        private final int origX, origZ, baseY;
        private final Random rnd;
        private final int hubHalf = MazeConfig.HUB_SIZE / 2;

        private int x = 0, z = -1;
        private boolean exhausted = false;
        private int phase; // 0 = suelo, 1..WALL_HEIGHT = columna vertical, WALL_HEIGHT+1 = barrera
        private BlockPos worldPos;
        private BlockState floorState;
        private boolean open;
        private BlockState wallState;

        GridJobIterator(CircularMazeGrid grid, ServerWorld world, int origX, int origZ, int baseY, Random rnd) {
            this.grid = grid;
            this.world = world;
            this.origX = origX;
            this.origZ = origZ;
            this.baseY = baseY;
            this.rnd = rnd;
            advanceToNextCell();
        }

        private void advanceToNextCell() {
            while (true) {
                z++;
                if (z >= grid.size) { z = 0; x++; }
                if (x >= grid.size) { exhausted = true; return; }
                double dist = Math.hypot(x - grid.center, z - grid.center);
                if (dist > MazeConfig.SHELL_OUTER) continue;
                prepareCell(dist);
                phase = 0;
                return;
            }
        }

        private void prepareCell(double dist) {
            boolean shell = dist > MazeConfig.INNER_RADIUS;
            boolean hub = Math.abs(x - grid.center) < hubHalf && Math.abs(z - grid.center) < hubHalf;
            CircularMazeGrid.SectorRoom room = roomAt(grid, x, z);

            if (shell) open = false; // el caparazón nace sólido: las puertas empiezan cerradas
            else if (hub || room != null) open = true;
            else open = grid.open[x][z];

            worldPos = new BlockPos(origX + x, baseY, origZ + z);

            if (hub) floorState = Blocks.GRASS_BLOCK.getDefaultState();
            else if (room != null) floorState = Blocks.ANDESITE.getDefaultState();
            else if (open) floorState = pickMazeFloor(rnd);
            else floorState = pickWallMaterial(rnd);

            wallState = open ? null : pickWallMaterial(rnd);
        }

        @Override
        public boolean hasNext() {
            return !exhausted;
        }

        @Override
        public MazeBuildQueue.BlockJob next() {
            if (exhausted) throw new NoSuchElementException();
            MazeBuildQueue.BlockJob job;
            if (phase == 0) {
                job = new MazeBuildQueue.BlockJob(world, worldPos, floorState);
            } else if (phase <= MazeConfig.WALL_HEIGHT) {
                BlockState state = open ? Blocks.AIR.getDefaultState() : wallState;
                job = new MazeBuildQueue.BlockJob(world, worldPos.up(phase), state);
            } else {
                job = new MazeBuildQueue.BlockJob(world, worldPos.up(MazeConfig.WALL_HEIGHT + 1), Blocks.BARRIER.getDefaultState());
            }
            phase++;
            if (phase > MazeConfig.WALL_HEIGHT + 1) {
                advanceToNextCell();
            }
            return job;
        }
    }

    // ------------------------------------------------------------------
    // Puertas
    // ------------------------------------------------------------------

    private static MazeInstance.Gate buildGateData(ServerWorld world, int origX, int origZ, int baseY,
                                                     Direction dir, GateGeometry.Rect rect) {
        List<List<BlockPos>> columns = new ArrayList<>();
        if (rect.wideOnX()) {
            for (int x = rect.x0(); x <= rect.x1(); x++) {
                List<BlockPos> col = new ArrayList<>();
                for (int z = rect.z0(); z <= rect.z1(); z++) {
                    for (int y = 1; y <= MazeConfig.WALL_HEIGHT; y++) {
                        col.add(new BlockPos(origX + x, baseY + y, origZ + z));
                    }
                }
                columns.add(col);
            }
        } else {
            for (int z = rect.z0(); z <= rect.z1(); z++) {
                List<BlockPos> col = new ArrayList<>();
                for (int x = rect.x0(); x <= rect.x1(); x++) {
                    for (int y = 1; y <= MazeConfig.WALL_HEIGHT; y++) {
                        col.add(new BlockPos(origX + x, baseY + y, origZ + z));
                    }
                }
                columns.add(col);
            }
        }
        BlockPos worldMin = new BlockPos(origX + rect.x0(), baseY, origZ + rect.z0());
        BlockPos worldMax = new BlockPos(origX + rect.x1(), baseY, origZ + rect.z1());
        return new MazeInstance.Gate(dir, worldMin, worldMax, columns);
    }

    // ------------------------------------------------------------------
    // Desierto de salida
    // ------------------------------------------------------------------

    private static void buildDesert(List<MazeBuildQueue.BlockJob> jobs, ServerWorld world, CircularMazeGrid grid,
                                     int origX, int origZ, int baseY, Random rnd) {
        GateGeometry.Rect porch = GateGeometry.porchRect(grid.center, Direction.SOUTH);
        int cx = porch.centerX();
        int startZ = porch.z1() + 1;
        int endZ = startZ + 40;
        int halfWidth = 22;

        for (int x = cx - halfWidth; x <= cx + halfWidth; x++) {
            for (int z = startZ; z <= endZ; z++) {
                BlockPos base = new BlockPos(origX + x, baseY, origZ + z);
                // Base sin gravedad, para que la arena de encima no se hunda con el tiempo.
                jobs.add(new MazeBuildQueue.BlockJob(world, base, Blocks.SMOOTH_SANDSTONE.getDefaultState()));
                jobs.add(new MazeBuildQueue.BlockJob(world, base.up(1), Blocks.SAND.getDefaultState()));
                double edgeDist = Math.min(Math.min(x - (cx - halfWidth), (cx + halfWidth) - x), Math.min(z - startZ, endZ - z));
                if (rnd.nextDouble() < 0.02 && edgeDist > 2) {
                    jobs.add(new MazeBuildQueue.BlockJob(world, base.up(2), Blocks.DEAD_BUSH.getDefaultState()));
                } else if (rnd.nextDouble() < 0.008 && edgeDist > 2) {
                    int h = 2 + rnd.nextInt(2);
                    for (int y = 2; y < 2 + h; y++) {
                        jobs.add(new MazeBuildQueue.BlockJob(world, base.up(y), Blocks.CACTUS.getDefaultState()));
                    }
                } else {
                    jobs.add(new MazeBuildQueue.BlockJob(world, base.up(2), Blocks.AIR.getDefaultState()));
                }
            }
        }
    }

    // ------------------------------------------------------------------
    // Plaza central: árboles, vegetación, lago, estructuras
    // ------------------------------------------------------------------

    private static void buildHubDecorations(List<MazeBuildQueue.BlockJob> jobs, ServerWorld world, CircularMazeGrid grid,
                                             int origX, int origZ, int baseY, Random rnd) {
        int c = grid.center;
        int hubHalf = MazeConfig.HUB_SIZE / 2;

        // Lago, ligeramente descentrado.
        int lakeCx = c - 30 + rnd.nextInt(20);
        int lakeCz = c - 20 + rnd.nextInt(20);
        int lakeRadius = 15;
        for (int x = lakeCx - lakeRadius - 2; x <= lakeCx + lakeRadius + 2; x++) {
            for (int z = lakeCz - lakeRadius - 2; z <= lakeCz + lakeRadius + 2; z++) {
                double d = Math.hypot(x - lakeCx, z - lakeCz);
                if (d > lakeRadius + 2) continue;
                BlockPos p = new BlockPos(origX + x, baseY, origZ + z);
                if (d <= lakeRadius) {
                    jobs.add(new MazeBuildQueue.BlockJob(world, p, Blocks.WATER.getDefaultState()));
                    jobs.add(new MazeBuildQueue.BlockJob(world, p.up(1), Blocks.AIR.getDefaultState()));
                } else {
                    jobs.add(new MazeBuildQueue.BlockJob(world, p, Blocks.SAND.getDefaultState()));
                }
            }
        }

        // Un puñado de árboles, evitando el lago y el propio centro (donde suele haber estructura).
        int treeCount = 22;
        List<int[]> treeSpots = new ArrayList<>();
        int attempts = 0;
        while (treeSpots.size() < treeCount && attempts < treeCount * 20) {
            attempts++;
            int tx = c - hubHalf + 10 + rnd.nextInt(MazeConfig.HUB_SIZE - 20);
            int tz = c - hubHalf + 10 + rnd.nextInt(MazeConfig.HUB_SIZE - 20);
            if (Math.hypot(tx - lakeCx, tz - lakeCz) < lakeRadius + 5) continue;
            if (Math.hypot(tx - c, tz - c) < 18) continue;
            treeSpots.add(new int[]{tx, tz});
        }
        for (int[] spot : treeSpots) {
            buildTree(jobs, world, origX, origZ, baseY, spot[0], spot[1], rnd);
        }

        // Vegetación suelta (hierba, helechos) repartida por el resto de la plaza.
        for (int i = 0; i < 900; i++) {
            int vx = c - hubHalf + 2 + rnd.nextInt(MazeConfig.HUB_SIZE - 4);
            int vz = c - hubHalf + 2 + rnd.nextInt(MazeConfig.HUB_SIZE - 4);
            if (Math.hypot(vx - lakeCx, vz - lakeCz) < lakeRadius + 3) continue;
            BlockPos p = new BlockPos(origX + vx, baseY + 1, origZ + vz);
            BlockState veg = rnd.nextDouble() < 0.75 ? Blocks.SHORT_GRASS.getDefaultState() : Blocks.FERN.getDefaultState();
            jobs.add(new MazeBuildQueue.BlockJob(world, p, veg));
        }

        // Un par de pequeñas estructuras en ruinas, cerca del centro.
        buildRuin(jobs, world, origX, origZ, baseY, c + 25, c + 15, rnd);
        buildRuin(jobs, world, origX, origZ, baseY, c - 15, c + 35, rnd);
    }

    private static void buildTree(List<MazeBuildQueue.BlockJob> jobs, ServerWorld world, int origX, int origZ, int baseY,
                                   int x, int z, Random rnd) {
        int trunk = 4 + rnd.nextInt(3);
        BlockPos base = new BlockPos(origX + x, baseY + 1, origZ + z);
        for (int y = 0; y < trunk; y++) {
            jobs.add(new MazeBuildQueue.BlockJob(world, base.up(y), Blocks.OAK_LOG.getDefaultState()));
        }
        int topY = trunk - 1;
        for (int ly = -1; ly <= 2; ly++) {
            int radius = ly < 1 ? 2 : 1;
            for (int lx = -radius; lx <= radius; lx++) {
                for (int lz = -radius; lz <= radius; lz++) {
                    if (Math.abs(lx) == radius && Math.abs(lz) == radius && rnd.nextBoolean()) continue;
                    if (lx == 0 && lz == 0 && ly < 2) continue; // no pisa el tronco salvo en la copa
                    jobs.add(new MazeBuildQueue.BlockJob(world, base.up(topY + ly).add(lx, 0, lz),
                            Blocks.OAK_LEAVES.getDefaultState()));
                }
            }
        }
    }

    private static void buildRuin(List<MazeBuildQueue.BlockJob> jobs, ServerWorld world, int origX, int origZ, int baseY,
                                   int x, int z, Random rnd) {
        BlockPos base = new BlockPos(origX + x, baseY, origZ + z);
        for (int dx = -2; dx <= 2; dx++) {
            for (int dz = -2; dz <= 2; dz++) {
                jobs.add(new MazeBuildQueue.BlockJob(world, base.add(dx, 0, dz), Blocks.MOSSY_STONE_BRICKS.getDefaultState()));
            }
        }
        int[][] corners = {{-2, -2}, {-2, 2}, {2, -2}, {2, 2}};
        for (int[] corner : corners) {
            int height = 2 + rnd.nextInt(2);
            for (int y = 1; y <= height; y++) {
                BlockState mat = rnd.nextBoolean() ? Blocks.CRACKED_STONE_BRICKS.getDefaultState() : Blocks.MOSSY_STONE_BRICKS.getDefaultState();
                jobs.add(new MazeBuildQueue.BlockJob(world, base.add(corner[0], y, corner[1]), mat));
            }
        }
    }

    // ------------------------------------------------------------------
    // Números de sector
    // ------------------------------------------------------------------

    private static void stampSectorNumber(List<MazeBuildQueue.BlockJob> jobs, ServerWorld world, CircularMazeGrid grid,
                                           int origX, int origZ, int baseY, CircularMazeGrid.SectorRoom room) {
        int c = grid.center;
        // Cara más alejada del centro entre las 4 posibles: esa es la que "mira" hacia fuera.
        int[][] faces = {
                {room.x1() + 1, room.centerZ(), 1, 0}, // este
                {room.x0() - 1, room.centerZ(), -1, 0}, // oeste
                {room.centerX(), room.z1() + 1, 0, 1}, // sur
                {room.centerX(), room.z0() - 1, 0, -1}  // norte
        };
        int[] best = faces[0];
        double bestDist = -1;
        for (int[] f : faces) {
            double d = Math.hypot(f[0] - c, f[1] - c);
            if (d > bestDist) { bestDist = d; best = f; }
        }

        String[] pattern = PixelFonts.DIGITS.get(room.number() % 10);
        if (pattern == null) return;

        boolean varyX = best[3] == 0; // pared norte/sur -> el número se extiende en X; este/oeste -> en Z
        for (int row = 0; row < 7; row++) {
            String line = pattern[row];
            int y = baseY + 8 - row; // fila 0 arriba
            for (int col = 0; col < 5; col++) {
                if (line.charAt(col) != '1') continue;
                int offset = col - 2;
                int wx = varyX ? best[0] + offset : best[0];
                int wz = varyX ? best[1] : best[1] + offset;
                jobs.add(new MazeBuildQueue.BlockJob(world, new BlockPos(origX + wx, y, origZ + wz),
                        Blocks.BLACKSTONE.getDefaultState()));
            }
        }
    }

    // ------------------------------------------------------------------
    // Símbolos sueltos en las paredes
    // ------------------------------------------------------------------

    private static void stampSymbols(List<MazeBuildQueue.BlockJob> jobs, ServerWorld world, CircularMazeGrid grid,
                                      int origX, int origZ, int baseY, Random rnd, List<int[]> candidates) {
        if (candidates.isEmpty()) return;
        Collections.shuffle(candidates, rnd);
        int count = Math.min(candidates.size(), 60 + rnd.nextInt(40));

        for (int i = 0; i < count; i++) {
            int[] cand = candidates.get(i);
            int x = cand[0], z = cand[1], dirIdx = cand[2];
            Direction facing = SIDE_DIRS[dirIdx];
            boolean varyZ = facing == Direction.EAST || facing == Direction.WEST;

            String[] symbol = PixelFonts.SYMBOLS.get(rnd.nextInt(PixelFonts.SYMBOLS.size()));
            for (int row = 0; row < 5; row++) {
                String line = symbol[row];
                int y = baseY + 6 - row;
                for (int col = 0; col < 5; col++) {
                    if (line.charAt(col) != '1') continue;
                    int offset = col - 2;
                    int wx = varyZ ? x : x + offset;
                    int wz = varyZ ? z + offset : z;
                    if (wx < 0 || wz < 0 || wx >= grid.size || wz >= grid.size) continue;
                    if (grid.open[wx][wz]) continue; // no pintar sobre un pasillo
                    double d = Math.hypot(wx - grid.center, wz - grid.center);
                    if (d > MazeConfig.INNER_RADIUS) continue;
                    jobs.add(new MazeBuildQueue.BlockJob(world, new BlockPos(origX + wx, y, origZ + wz),
                            Blocks.CHISELED_STONE_BRICKS.getDefaultState()));
                }
            }
        }
    }

    // ------------------------------------------------------------------

    private static CircularMazeGrid.SectorRoom roomAt(CircularMazeGrid grid, int x, int z) {
        for (CircularMazeGrid.SectorRoom room : grid.sectorRooms) {
            if (x >= room.x0() && x <= room.x1() && z >= room.z0() && z <= room.z1()) return room;
        }
        return null;
    }

    static BlockState pickWallMaterial(Random rnd) {
        double r = rnd.nextDouble();
        if (r < 0.34) return Blocks.STONE.getDefaultState();
        if (r < 0.62) return Blocks.COBBLESTONE.getDefaultState();
        if (r < 0.85) return Blocks.MOSSY_COBBLESTONE.getDefaultState();
        return Blocks.MOSSY_STONE_BRICKS.getDefaultState();
    }

    static BlockState pickMazeFloor(Random rnd) {
        double r = rnd.nextDouble();
        if (r < 0.40) return Blocks.MOSSY_COBBLESTONE.getDefaultState();
        if (r < 0.75) return Blocks.STONE.getDefaultState();
        return Blocks.MOSS_BLOCK.getDefaultState();
    }
}
