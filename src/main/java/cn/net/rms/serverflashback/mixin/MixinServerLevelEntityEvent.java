package cn.net.rms.serverflashback.mixin;

import cn.net.rms.serverflashback.record.RecordingManager;
import net.minecraft.network.protocol.game.ClientboundEntityEventPacket;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.entity.Entity;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

@Mixin(ServerLevel.class)
public class MixinServerLevelEntityEvent {

    @Inject(method = "broadcastEntityEvent", at = @At("HEAD"))
    private void serverflashback$onEntityEvent(Entity entity, byte status, CallbackInfo ci) {
        if (RecordingManager.getInstance().hasActiveRecordings()) {
            RecordingManager.getInstance().onEntityEvent(
                    (ServerLevel) (Object) this, entity, status);
        }
    }
}
