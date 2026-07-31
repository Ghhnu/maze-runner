package com.mazerunner.maze;

import net.minecraft.block.BlockState;
import net.minecraft.block.Blocks;
import net.minecraft.entity.EntityType;
import net.minecraft.entity.attribute.EntityAttributeInstance;
import net.minecraft.entity.attribute.EntityAttributes;
import net.minecraft.entity.mob.SpiderEntity;
import net.minecraft.server.network.ServerPlayerEntity;
import net.minecraft.server.world.ServerWorld;
import net.minecraft.text.Text;
import net.minecraft.util.math.BlockPos;
import net.minecraft.util.math.Direction;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Random;

/**
 * Un laberinto ya construido en el mundo. Sabe:
 * <p>
 * - "removerse" solo cada 120s ({@link #shuffleSector()}), apartando a cualquier jugador
 *   que esté justo donde va a aparecer una pared nueva.<br>
 * - abrir/cerrar sus 4 puertas grandes con animación cubo a cubo ({@link #openGates()} /
 *   {@link #closeGates()}), apartando también a quien esté en medio.<br>
 * - hacer aparecer y desaparecer arañas gigantes cada 120s ({@link #spawnSpiders()} /
 *   {@link #despawnSpiders()}).<br>
 * - borrarse del mundo si se va a construir uno nuevo encima ({@link #clearJobs()}).
 */
public final class MazeInstance {

    /** Una de las 4 puertas grandes del laberinto. */
    public static final class Gate {
        public final Direction direction;
        public final BlockPos worldMin, worldMax;
        public final List<List<BlockPos>> columns;
        public boolean open = false;

        public Gate(Direction direction, BlockPos worldMin, BlockPos worldMax, List<List<BlockPos>> columns) {
            this.direction = direction;
            this.worldMin = worldMin;
            this.worldMax = worldMax;
            this.columns = columns;
        }
    }

    private final ServerWorld world;
    private final CircularMazeGrid grid;
    private final int origX, origZ, baseY;
    private final boolean[][] liveOpen;
    private final List<Gate> gates;
    private final Random rnd;
    private final List<SpiderEntity> spawnedSpiders = new ArrayList<>();
    private int shuffleCount = 0;

    public MazeInstance(ServerWorld world, CircularMazeGrid grid, int origX, int origZ, int baseY,
                         long seed, List<Gate> gates) {
        this.world = world;
        this.grid = grid;
        this.origX = origX;
        this.origZ = origZ;
        this.baseY = baseY;
        this.gates = gates;
        this.rnd = new Random(seed ^ 0x51EDL);
        this.liveOpen = new boolean[grid.size][grid.size];
        for (int x = 0; x < grid.size; x++) {
            System.arraycopy(grid.open[x], 0, liveOpen[x], 0, grid.size);
        }
    }

    public ServerWorld world() { return world; }
    public List<Gate> gates() { return gates; }

    // ------------------------------------------------------------------
    // Borrado (cuando se reconstruye encima con /maze x y z)
    // ------------------------------------------------------------------

    public List<MazeBuildQueue.BlockJob> clearJobs() {
        List<MazeBuildQueue.BlockJob> jobs = new ArrayList<>();
        int reach = MazeConfig.SHELL_OUTER + MazeConfig.PORCH_LENGTH + 45; // cubre también el desierto
        for (int x = 0; x < grid.size; x++) {
            for (int z = 0; z < grid.size; z++) {
                double dist = Math.hypot(x - grid.center, z - grid.center);
                if (dist > reach) continue;
                BlockPos p = new BlockPos(origX + x, baseY, origZ + z);
                jobs.add(new MazeBuildQueue.BlockJob(world, p, Blocks.GRASS_BLOCK.getDefaultState()));
                for (int y = 1; y <= MazeConfig.WALL_HEIGHT + 1; y++) {
                    jobs.add(new MazeBuildQueue.BlockJob(world, p.up(y), Blocks.AIR.getDefaultState()));
                }
            }
        }
        for (SpiderEntity spider : spawnedSpiders) {
            if (spider.isAlive()) spider.discard();
        }
        spawnedSpiders.clear();
        return jobs;
    }

    // ------------------------------------------------------------------
    // El laberinto cambia cada 120s
    // ------------------------------------------------------------------

    public void shuffleSector() {
        Random r = new Random(rnd.nextLong() + (shuffleCount++));
        int hubHalf = MazeConfig.HUB_SIZE / 2;
        int minR = hubHalf + MazeConfig.STEP;
        int maxR = MazeConfig.INNER_RADIUS - MazeConfig.STEP;
        if (maxR <= minR) return;

        List<int[]> candidates = new ArrayList<>();
        for (int i = 0; i < 260; i++) {
            double angle = r.nextDouble() * Math.PI * 2;
            int radius = minR + r.nextInt(maxR - minR);
            int x = grid.center + (int) Math.round(Math.cos(angle) * radius);
            int z = grid.center + (int) Math.round(Math.sin(angle) * radius);
            if (x < MazeConfig.WALL_W || z < MazeConfig.WALL_W
                    || x >= grid.size - MazeConfig.WALL_W || z >= grid.size - MazeConfig.WALL_W) continue;
            if (nearAnySectorRoom(x, z, 10)) continue;
            candidates.add(new int[]{x, z});
        }
        Collections.shuffle(candidates, r);

        int amount = Math.min(candidates.size(), 10 + r.nextInt(10));
        List<MazeBuildQueue.BlockJob> jobs = new ArrayList<>();

        for (int i = 0; i < amount; i++) {
            int[] c = candidates.get(i);
            int cx = c[0], cz = c[1];
            boolean newState = !liveOpen[cx][cz];

            pushPlayersOut(cx, cz, newState);

            int half = MazeConfig.FLOOR_W / 2;
            for (int dx = -half; dx < MazeConfig.FLOOR_W - half; dx++) {
                for (int dz = -half; dz < MazeConfig.FLOOR_W - half; dz++) {
                    int x = cx + dx, z = cz + dz;
                    if (x < 0 || z < 0 || x >= grid.size || z >= grid.size) continue;
                    liveOpen[x][z] = newState;
                    BlockPos worldPos = new BlockPos(origX + x, baseY, origZ + z);
                    if (newState) {
                        jobs.add(new MazeBuildQueue.BlockJob(world, worldPos, MazeBuilder.pickMazeFloor(r)));
                        for (int y = 1; y <= MazeConfig.WALL_HEIGHT; y++) {
                            jobs.add(new MazeBuildQueue.BlockJob(world, worldPos.up(y), Blocks.AIR.getDefaultState()));
                        }
                    } else {
                        BlockState wallState = MazeBuilder.pickWallMaterial(r);
                        for (int y = 1; y <= MazeConfig.WALL_HEIGHT; y++) {
                            jobs.add(new MazeBuildQueue.BlockJob(world, worldPos.up(y), wallState));
                        }
                    }
                }
            }
        }

        MazeBuildQueue.submit(jobs);
        world.getServer().getPlayerManager().broadcast(
                Text.literal("§6[MazeRunner] §fEl laberinto está cambiando..."), false);
    }

    private boolean nearAnySectorRoom(int x, int z, int margin) {
        for (CircularMazeGrid.SectorRoom room : grid.sectorRooms) {
            if (x >= room.x0() - margin && x <= room.x1() + margin
                    && z >= room.z0() - margin && z <= room.z1() + margin) return true;
        }
        return false;
    }

    private void pushPlayersOut(int cx, int cz, boolean becomingOpen) {
        if (becomingOpen) return; // sólo hace falta apartar a alguien cuando el hueco se va a cerrar
        double worldX = origX + cx + 0.5;
        double worldZ = origZ + cz + 0.5;
        double dirX = grid.center - cx;
        double dirZ = grid.center - cz;
        double len = Math.hypot(dirX, dirZ);
        if (len < 0.001) { dirX = 1; dirZ = 0; len = 1; }
        dirX /= len; dirZ /= len;

        for (ServerPlayerEntity player : world.getPlayers()) {
            double px = player.getX() - (origX + cx);
            double pz = player.getZ() - (origZ + cz);
            if (Math.abs(px) > MazeConfig.FLOOR_W || Math.abs(pz) > MazeConfig.FLOOR_W) continue;
            if (player.getY() < baseY - 2 || player.getY() > baseY + MazeConfig.WALL_HEIGHT) continue;

            double newX = worldX + dirX * 9.0;
            double newZ = worldZ + dirZ * 9.0;
            player.requestTeleport(newX, player.getY(), newZ);
            player.sendMessage(Text.literal("§c¡El laberinto se cierra a tu alrededor! Te has apartado a tiempo."), true);
        }
    }

    // ------------------------------------------------------------------
    // Puertas
    // ------------------------------------------------------------------

    public void openGates() {
        world.getServer().getPlayerManager().broadcast(
                Text.literal("§a[MazeRunner] §fLas puertas del laberinto se están abriendo..."), false);
        for (Gate gate : gates) {
            if (gate.open) continue;
            gate.open = true;
            pushPlayersFromGate(gate);
            for (List<BlockPos> column : gate.columns) { // izquierda -> derecha
                AnimationQueue.submit(new AnimationQueue.ColumnJob(world, column, Blocks.AIR.getDefaultState(), null));
            }
        }
    }

    public void closeGates() {
        world.getServer().getPlayerManager().broadcast(
                Text.literal("§c[MazeRunner] §fLas puertas del laberinto se están cerrando..."), false);
        for (Gate gate : gates) {
            if (!gate.open) continue;
            gate.open = false;
            pushPlayersFromGate(gate);
            List<List<BlockPos>> reversed = new ArrayList<>(gate.columns);
            Collections.reverse(reversed); // derecha -> izquierda, tal y como se pidió
            for (List<BlockPos> column : reversed) {
                BlockState wallState = MazeBuilder.pickWallMaterial(rnd);
                AnimationQueue.submit(new AnimationQueue.ColumnJob(world, column, wallState, null));
            }
        }
    }

    private void pushPlayersFromGate(Gate gate) {
        double minX = gate.worldMin.getX() - 1, maxX = gate.worldMax.getX() + 1;
        double minZ = gate.worldMin.getZ() - 1, maxZ = gate.worldMax.getZ() + 1;
        double cx = (minX + maxX) / 2.0, cz = (minZ + maxZ) / 2.0;

        double towardHubX = grid.center - (cx - origX);
        double towardHubZ = grid.center - (cz - origZ);
        double len = Math.hypot(towardHubX, towardHubZ);
        if (len < 0.001) { towardHubX = 0; towardHubZ = -1; len = 1; }
        towardHubX /= len; towardHubZ /= len;

        for (ServerPlayerEntity player : world.getPlayers()) {
            double x = player.getX(), z = player.getZ(), y = player.getY();
            if (x < minX || x > maxX || z < minZ || z > maxZ) continue;
            if (y < baseY - 2 || y > baseY + MazeConfig.WALL_HEIGHT) continue;

            double newX = cx + towardHubX * 10.0;
            double newZ = cz + towardHubZ * 10.0;
            player.requestTeleport(newX, y, newZ);
            player.sendMessage(Text.literal("§c¡Te has apartado de la puerta a tiempo!"), true);
        }
    }

    // ------------------------------------------------------------------
    // Arañas gigantes
    // ------------------------------------------------------------------

    public void spawnSpiders() {
        int hubHalf = MazeConfig.HUB_SIZE / 2;
        int minR = hubHalf + MazeConfig.STEP;
        int maxR = MazeConfig.INNER_RADIUS - MazeConfig.STEP;
        if (maxR <= minR) return;

        int count = 4 + rnd.nextInt(4); // pocas, nunca una plaga
        int spawned = 0;
        int attempts = 0;
        while (spawned < count && attempts < count * 25) {
            attempts++;
            double angle = rnd.nextDouble() * Math.PI * 2;
            int radius = minR + rnd.nextInt(maxR - minR);
            int x = grid.center + (int) Math.round(Math.cos(angle) * radius);
            int z = grid.center + (int) Math.round(Math.sin(angle) * radius);
            if (x < 0 || z < 0 || x >= grid.size || z >= grid.size) continue;
            if (!liveOpen[x][z]) continue;

            SpiderEntity spider = EntityType.SPIDER.create(world);
            if (spider == null) continue;
            spider.refreshPositionAndAngles(origX + x + 0.5, baseY + 1, origZ + z + 0.5, 0, 0);

            EntityAttributeInstance scale = spider.getAttributeInstance(EntityAttributes.GENERIC_SCALE);
            if (scale != null) scale.setBaseValue(1.7);
            EntityAttributeInstance health = spider.getAttributeInstance(EntityAttributes.GENERIC_MAX_HEALTH);
            if (health != null) health.setBaseValue(health.getBaseValue() * 2.5);
            EntityAttributeInstance damage = spider.getAttributeInstance(EntityAttributes.GENERIC_ATTACK_DAMAGE);
            if (damage != null) damage.setBaseValue(damage.getBaseValue() * 2.0);
            EntityAttributeInstance speed = spider.getAttributeInstance(EntityAttributes.GENERIC_MOVEMENT_SPEED);
            if (speed != null) speed.setBaseValue(speed.getBaseValue() * 1.15);
            spider.setHealth(spider.getMaxHealth());

            world.spawnEntity(spider);
            spawnedSpiders.add(spider);
            spawned++;
        }

        if (spawned > 0) {
            world.getServer().getPlayerManager().broadcast(
                    Text.literal("§4[MazeRunner] §fSe oyen crujidos entre las paredes... ¡arañas gigantes sueltas en el laberinto!"), false);
        }
    }

    public void despawnSpiders() {
        if (spawnedSpiders.isEmpty()) return;
        for (SpiderEntity spider : spawnedSpiders) {
            if (spider.isAlive()) spider.discard();
        }
        spawnedSpiders.clear();
        world.getServer().getPlayerManager().broadcast(
                Text.literal("§7[MazeRunner] §fLas arañas gigantes se han retirado a la oscuridad."), false);
    }
}
