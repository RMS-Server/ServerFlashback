package cn.net.rms.serverflashback.mixin;

import cn.net.rms.serverflashback.record.RecordingManager;
import net.minecraft.network.protocol.Packet;
import net.minecraft.network.protocol.game.ClientGamePacketListener;
import net.minecraft.network.protocol.game.ClientboundBlockEventPacket;
import net.minecraft.resources.ResourceKey;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.players.PlayerList;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.level.Level;
import org.spongepowered.asm.mixin.Final;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

@Mixin(PlayerList.class)
public class MixinPlayerList {

    @Shadow @Final private MinecraftServer server;

    @SuppressWarnings("unchecked")
    @Inject(method = "broadcastAll(Lnet/minecraft/network/protocol/Packet;)V", at = @At("HEAD"))
    private void serverflashback$onBroadcastAll(Packet<?> packet, CallbackInfo ci) {
        if (!RecordingManager.getInstance().hasActiveRecordings()) return;

        RecordingManager.getInstance().onGlobalGamePacket(
                (Packet<? super ClientGamePacketListener>) packet);
    }

    @SuppressWarnings("unchecked")
    @Inject(method = "broadcast(Lnet/minecraft/world/entity/player/Player;DDDDLnet/minecraft/resources/ResourceKey;Lnet/minecraft/network/protocol/Packet;)V",
            at = @At("HEAD"))
    private void serverflashback$onBroadcastPositioned(Player player, double x, double y, double z,
                                                       double range, ResourceKey<Level> levelKey,
                                                       Packet<?> packet, CallbackInfo ci) {
        if (!(packet instanceof ClientboundBlockEventPacket)) return;
        if (!RecordingManager.getInstance().hasActiveRecordings()) return;

        ServerLevel level = server.getLevel(levelKey);
        if (level == null) return;
        RecordingManager.getInstance().onPositionedGamePacket(level, x, z,
                (Packet<? super ClientGamePacketListener>) packet);
    }
}
