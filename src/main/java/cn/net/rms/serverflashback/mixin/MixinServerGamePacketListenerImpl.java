package cn.net.rms.serverflashback.mixin;

import cn.net.rms.serverflashback.record.RecordingManager;
import net.minecraft.network.protocol.Packet;
import net.minecraft.network.protocol.game.*;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.server.network.ServerGamePacketListenerImpl;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

import java.util.Set;

@Mixin(ServerGamePacketListenerImpl.class)
public class MixinServerGamePacketListenerImpl {

    @Shadow public ServerPlayer player;

    @Unique
    private static final Set<Class<?>> CAPTURED_PACKETS = Set.of(
            ClientboundSetTitleTextPacket.class,
            ClientboundSetSubtitleTextPacket.class,
            ClientboundSetActionBarTextPacket.class,
            ClientboundClearTitlesPacket.class,
            ClientboundSetTitlesAnimationPacket.class
    );

    @SuppressWarnings("unchecked")
    @Inject(method = "send(Lnet/minecraft/network/protocol/Packet;)V", at = @At("HEAD"))
    private void serverflashback$onSend(Packet<?> packet, CallbackInfo ci) {
        if (!CAPTURED_PACKETS.contains(packet.getClass())) return;
        if (!RecordingManager.getInstance().hasActiveRecordings()) return;

//#if MC >= 12002
        ServerLevel level = (ServerLevel) this.player.level();
//#else
//$$         ServerLevel level = (ServerLevel) this.player.level;
//#endif
        RecordingManager.getInstance().onPositionedGamePacket(
                level, this.player.getX(), this.player.getZ(),
                (Packet<? super ClientGamePacketListener>) packet);
    }
}
