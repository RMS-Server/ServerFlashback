package cn.net.rms.serverflashback;

import cn.net.rms.serverflashback.action.*;
import cn.net.rms.serverflashback.command.ServerFlashbackCommand;
import cn.net.rms.serverflashback.record.RecordingManager;
import net.fabricmc.api.ModInitializer;
//#if MC >= 11900
import net.fabricmc.fabric.api.command.v2.CommandRegistrationCallback;
//#else
//$$ import net.fabricmc.fabric.api.command.v1.CommandRegistrationCallback;
//#endif
import net.fabricmc.fabric.api.event.lifecycle.v1.ServerChunkEvents;
import net.fabricmc.fabric.api.event.lifecycle.v1.ServerLifecycleEvents;
import net.fabricmc.fabric.api.event.lifecycle.v1.ServerTickEvents;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

public final class ServerFlashback implements ModInitializer {

    public static final Logger LOGGER = LoggerFactory.getLogger("serverflashback");

    @Override
    public void onInitialize() {
        LOGGER.info("ServerFlashback initializing");

        ActionRegistry.register(ActionNextTick.INSTANCE);
        ActionRegistry.register(ActionGamePacket.INSTANCE);
//#if MC >= 12002
        ActionRegistry.register(ActionConfigurationPacket.INSTANCE);
//#endif
        ActionRegistry.register(ActionCreateLocalPlayer.INSTANCE);
        ActionRegistry.register(ActionMoveEntities.INSTANCE);
        ActionRegistry.register(ActionLevelChunkCached.INSTANCE);
        ActionRegistry.register(ActionAccuratePlayerPosition.INSTANCE);

//#if MC >= 11900
        CommandRegistrationCallback.EVENT.register((dispatcher, registryAccess, environment) ->
                ServerFlashbackCommand.register(dispatcher));
//#else
//$$         CommandRegistrationCallback.EVENT.register((dispatcher, dedicated) ->
//$$                 ServerFlashbackCommand.register(dispatcher));
//#endif

        ServerTickEvents.END_SERVER_TICK.register(server ->
                RecordingManager.getInstance().onServerTick(server));

        ServerChunkEvents.CHUNK_UNLOAD.register((level, chunk) ->
                RecordingManager.getInstance().onChunkUnload(level, chunk));

        ServerChunkEvents.CHUNK_LOAD.register((level, chunk) ->
                RecordingManager.getInstance().onChunkLoad(level, chunk));

        ServerLifecycleEvents.SERVER_STOPPING.register(server -> {
            if (RecordingManager.getInstance().hasActiveRecordings()) {
                LOGGER.info("Server stopping, finishing all active recordings...");
                RecordingManager.getInstance().stopAll(server);
            }
            RecordingManager.getInstance().waitForPendingExports();
        });

        LOGGER.info("ServerFlashback initialized");
    }
}
