package cn.net.rms.serverflashback.mixin;

import cn.net.rms.serverflashback.record.RecordingManager;
import net.minecraft.network.protocol.Packet;
import net.minecraft.network.protocol.game.ClientGamePacketListener;
import net.minecraft.network.protocol.game.ClientboundEntityEventPacket;
import net.minecraft.network.protocol.game.ClientboundMoveEntityPacket;
import net.minecraft.network.protocol.game.ClientboundRotateHeadPacket;
import net.minecraft.server.level.ServerChunkCache;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.entity.Entity;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

@Mixin(ServerChunkCache.class)
public class MixinServerChunkCache {

    @SuppressWarnings("unchecked")
    @Inject(method = "broadcast", at = @At("HEAD"))
    private void serverflashback$onBroadcast(Entity entity, Packet<?> packet, CallbackInfo ci) {
        if (packet instanceof ClientboundMoveEntityPacket
                || packet instanceof ClientboundRotateHeadPacket
                || packet instanceof ClientboundEntityEventPacket) {
            return;
        }
//#if MC >= 12002
        if (entity.level() instanceof ServerLevel serverLevel
//#else
//$$         if (entity.level instanceof ServerLevel serverLevel
//#endif
                && RecordingManager.getInstance().hasActiveRecordings()) {
            RecordingManager.getInstance().onEntityPacket(
                    serverLevel, entity,
                    (Packet<? super ClientGamePacketListener>) packet);
        }
    }

    @SuppressWarnings("unchecked")
    @Inject(method = "broadcastAndSend", at = @At("HEAD"))
    private void serverflashback$onBroadcastAndSend(Entity entity, Packet<?> packet, CallbackInfo ci) {
        if (packet instanceof ClientboundMoveEntityPacket
                || packet instanceof ClientboundRotateHeadPacket
                || packet instanceof ClientboundEntityEventPacket) {
            return;
        }
//#if MC >= 12002
        if (entity.level() instanceof ServerLevel serverLevel
//#else
//$$         if (entity.level instanceof ServerLevel serverLevel
//#endif
                && RecordingManager.getInstance().hasActiveRecordings()) {
            RecordingManager.getInstance().onEntityPacket(
                    serverLevel, entity,
                    (Packet<? super ClientGamePacketListener>) packet);
        }
    }
}
