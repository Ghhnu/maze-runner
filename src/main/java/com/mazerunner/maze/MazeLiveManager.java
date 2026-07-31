package com.mazerunner.maze;

import net.fabricmc.fabric.api.event.lifecycle.v1.ServerTickEvents;
import net.minecraft.server.world.ServerWorld;

import java.util.HashMap;
import java.util.Map;

/**
 * Guarda el laberinto activo de cada mundo (uno a la vez por mundo: si se lanza
 * {@code /maze x y z} otra vez, el anterior se borra y este pasa a ser el activo) y
 * dispara sus dos ciclos de 120 segundos: el cambio de sector y las arañas gigantes.
 */
public final class MazeLiveManager {

    private static final Map<ServerWorld, MazeInstance> active = new HashMap<>();
    private static boolean registered = false;
    private static int tickCounter = 0;
    /** Ciclo completo de las arañas: 120s presentes + 120s ausentes. */
    private static int mobTickCounter = 0;

    private MazeLiveManager() {}

    public static MazeInstance get(ServerWorld world) {
        return active.get(world);
    }

    public static void clear(ServerWorld world) {
        active.remove(world);
    }

    public static void register(MazeInstance instance) {
        ensureRegistered();
        active.put(instance.world(), instance);
    }

    private static void ensureRegistered() {
        if (registered) return;
        registered = true;
        ServerTickEvents.END_SERVER_TICK.register(server -> tick());
    }

    private static void tick() {
        if (active.isEmpty()) return;

        tickCounter++;
        boolean shiftNow = tickCounter >= MazeConfig.SHIFT_INTERVAL_TICKS;
        if (shiftNow) tickCounter = 0;

        mobTickCounter++;
        boolean spawnPhaseStart = mobTickCounter == 1;
        boolean despawnPhaseStart = mobTickCounter == MazeConfig.MOB_PHASE_TICKS + 1;
        if (mobTickCounter >= MazeConfig.MOB_PHASE_TICKS * 2) mobTickCounter = 0;

        for (MazeInstance instance : active.values()) {
            if (!instance.world().getServer().isRunning()) continue;
            if (shiftNow) instance.shuffleSector();
            if (spawnPhaseStart) instance.spawnSpiders();
            if (despawnPhaseStart) instance.despawnSpiders();
        }
    }
}
