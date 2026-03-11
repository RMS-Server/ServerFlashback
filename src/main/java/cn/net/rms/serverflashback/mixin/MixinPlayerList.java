package cn.net.rms.serverflashback.mixin;

import cn.net.rms.serverflashback.record.RecordingManager;
import net.minecraft.network.protocol.Packet;
import net.minecraft.network.protocol.game.ClientGamePacketListener;
import net.minecraft.server.players.PlayerList;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

@Mixin(PlayerList.class)
public class MixinPlayerList {

    @SuppressWarnings("unchecked")
    @Inject(method = "broadcastAll(Lnet/minecraft/network/protocol/Packet;)V", at = @At("HEAD"))
    private void serverflashback$onBroadcastAll(Packet<?> packet, CallbackInfo ci) {
        if (!RecordingManager.getInstance().hasActiveRecordings()) return;

        RecordingManager.getInstance().onGlobalGamePacket(
                (Packet<? super ClientGamePacketListener>) packet);
    }
}
