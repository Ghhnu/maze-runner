package com.mazerunner;

import com.mazerunner.command.MazeCommand;
import net.fabricmc.api.ModInitializer;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

public class MazeRunnerMod implements ModInitializer {

    public static final String MOD_ID = "mazerunner";
    public static final Logger LOGGER = LoggerFactory.getLogger(MOD_ID);

    @Override
    public void onInitialize() {
        LOGGER.info("[MazeRunner] Inicializando MazeRunner...");
        MazeCommand.register();
    }
}
