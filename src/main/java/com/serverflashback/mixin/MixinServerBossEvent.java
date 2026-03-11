package com.serverflashback.mixin;

import com.serverflashback.record.RecordingManager;
import net.minecraft.network.protocol.game.ClientboundBossEventPacket;
import net.minecraft.server.level.ServerBossEvent;
import net.minecraft.world.BossEvent;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

import java.util.function.Function;

@Mixin(ServerBossEvent.class)
public class MixinServerBossEvent {

    @Inject(method = "broadcast", at = @At("HEAD"))
    private void serverflashback$onBroadcast(Function<BossEvent, ClientboundBossEventPacket> function,
                                             CallbackInfo ci) {
        if (RecordingManager.getInstance().hasActiveRecordings()) {
            ClientboundBossEventPacket packet = function.apply((BossEvent) (Object) this);
            RecordingManager.getInstance().onGlobalGamePacket(packet);
        }
    }
}
