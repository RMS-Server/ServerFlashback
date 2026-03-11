package com.serverflashback.mixin;

import com.serverflashback.record.RecordingManager;
import net.minecraft.core.BlockPos;
//#if MC >= 11900
import net.minecraft.core.Holder;
//#endif
import net.minecraft.server.level.ServerLevel;
import net.minecraft.sounds.SoundEvent;
import net.minecraft.sounds.SoundSource;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.player.Player;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

@Mixin(ServerLevel.class)
public class MixinServerLevelRecording {

    @Inject(method = "levelEvent", at = @At("HEAD"))
    private void serverflashback$onLevelEvent(Player player, int type, BlockPos pos, int data,
                                              CallbackInfo ci) {
        if (RecordingManager.getInstance().hasActiveRecordings()) {
            RecordingManager.getInstance().onLevelEvent(
                    (ServerLevel) (Object) this, type, pos, data, false);
        }
    }

    @Inject(method = "globalLevelEvent", at = @At("HEAD"))
    private void serverflashback$onGlobalLevelEvent(int type, BlockPos pos, int data, CallbackInfo ci) {
        if (RecordingManager.getInstance().hasActiveRecordings()) {
            RecordingManager.getInstance().onLevelEvent(
                    (ServerLevel) (Object) this, type, pos, data, true);
        }
    }

//#if MC >= 11900
    @Inject(method = "playSeededSound(Lnet/minecraft/world/entity/player/Player;DDDLnet/minecraft/core/Holder;Lnet/minecraft/sounds/SoundSource;FFJ)V",
            at = @At("HEAD"))
    private void serverflashback$onPlaySound(Player player, double x, double y, double z,
                                             Holder<SoundEvent> sound, SoundSource source,
                                             float volume, float pitch, long seed, CallbackInfo ci) {
        if (RecordingManager.getInstance().hasActiveRecordings()) {
            RecordingManager.getInstance().onSound(
                    (ServerLevel) (Object) this, sound, source, x, y, z, volume, pitch, seed);
        }
    }
//#else
//$$     @Inject(method = "playSound(Lnet/minecraft/world/entity/player/Player;DDDLnet/minecraft/sounds/SoundEvent;Lnet/minecraft/sounds/SoundSource;FF)V",
//$$             at = @At("HEAD"))
//$$     private void serverflashback$onPlaySound(Player player, double x, double y, double z,
//$$                                              SoundEvent sound, SoundSource source,
//$$                                              float volume, float pitch, CallbackInfo ci) {
//$$         if (RecordingManager.getInstance().hasActiveRecordings()) {
//$$             RecordingManager.getInstance().onSound(
//$$                     (ServerLevel) (Object) this, sound, source, x, y, z, volume, pitch);
//$$         }
//$$     }
//#endif

    @Inject(method = "addFreshEntity", at = @At("RETURN"))
    private void serverflashback$onAddFreshEntity(Entity entity, CallbackInfoReturnable<Boolean> cir) {
        if (cir.getReturnValue() && RecordingManager.getInstance().hasActiveRecordings()) {
            RecordingManager.getInstance().onEntitySpawn((ServerLevel) (Object) this, entity);
        }
    }
}
