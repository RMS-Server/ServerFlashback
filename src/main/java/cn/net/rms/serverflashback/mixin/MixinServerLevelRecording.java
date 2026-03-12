package cn.net.rms.serverflashback.mixin;

import cn.net.rms.serverflashback.record.RecordingManager;
import net.minecraft.core.BlockPos;
//#if MC >= 11900
import net.minecraft.core.Holder;
//#endif
import net.minecraft.core.particles.ParticleOptions;
import net.minecraft.network.protocol.game.ClientboundBlockDestructionPacket;
import net.minecraft.network.protocol.game.ClientboundExplodePacket;
import net.minecraft.network.protocol.game.ClientboundLevelParticlesPacket;
import net.minecraft.network.protocol.game.ClientboundSoundEntityPacket;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.sounds.SoundEvent;
import net.minecraft.sounds.SoundSource;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.level.BlockEventData;
import net.minecraft.world.phys.Vec3;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

@Mixin(ServerLevel.class)
public class MixinServerLevelRecording {

    @Inject(method = "doBlockEvent", at = @At("HEAD"))
    private void serverflashback$onBlockEventStart(BlockEventData data, CallbackInfoReturnable<Boolean> cir) {
        RecordingManager.setBlockEventInProgress(true);
    }

    @Inject(method = "doBlockEvent", at = @At("RETURN"))
    private void serverflashback$onBlockEventEnd(BlockEventData data, CallbackInfoReturnable<Boolean> cir) {
        RecordingManager.setBlockEventInProgress(false);
    }

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

    @Inject(method = "playSeededSound(Lnet/minecraft/world/entity/player/Player;Lnet/minecraft/world/entity/Entity;Lnet/minecraft/core/Holder;Lnet/minecraft/sounds/SoundSource;FFJ)V",
            at = @At("HEAD"))
    private void serverflashback$onPlaySoundEntity(Player source, Entity entity, Holder<SoundEvent> sound,
                                                   SoundSource soundSource, float volume, float pitch, long seed, CallbackInfo ci) {
        if (RecordingManager.getInstance().hasActiveRecordings()) {
            RecordingManager.getInstance().onEntitySound(
                    (ServerLevel) (Object) this, sound, soundSource, entity, volume, pitch, seed);
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

    @Inject(method = "sendParticles(Lnet/minecraft/core/particles/ParticleOptions;DDDIDDDD)I",
            at = @At("HEAD"))
    private void serverflashback$onSendParticles(ParticleOptions particle, double x, double y, double z,
                                                 int count, double xDist, double yDist, double zDist,
                                                 double speed, CallbackInfoReturnable<Integer> cir) {
        if (RecordingManager.getInstance().hasActiveRecordings()) {
//#if MC >= 11900
            ClientboundLevelParticlesPacket packet = new ClientboundLevelParticlesPacket(
                    particle, false, false, x, y, z, (float) xDist, (float) yDist, (float) zDist, (float) speed, count);
//#else
//$$             ClientboundLevelParticlesPacket packet = new ClientboundLevelParticlesPacket(
//$$                     particle, false, x, y, z, (float) xDist, (float) yDist, (float) zDist, (float) speed, count);
//#endif
            RecordingManager.getInstance().onPositionedGamePacket(
                    (ServerLevel) (Object) this, x, z, packet);
        }
    }

    @Inject(method = "destroyBlockProgress", at = @At("HEAD"))
    private void serverflashback$onDestroyBlockProgress(int breakerId, BlockPos pos, int progress,
                                                        CallbackInfo ci) {
        if (RecordingManager.getInstance().hasActiveRecordings()) {
            ClientboundBlockDestructionPacket packet = new ClientboundBlockDestructionPacket(breakerId, pos, progress);
            RecordingManager.getInstance().onPositionedGamePacket(
                    (ServerLevel) (Object) this, pos.getX(), pos.getZ(), packet);
        }
    }

//#if MC >= 12002
    @Inject(method = "explode(Lnet/minecraft/world/entity/Entity;Lnet/minecraft/world/damagesource/DamageSource;Lnet/minecraft/world/level/ExplosionDamageCalculator;DDDFZLnet/minecraft/world/level/Level$ExplosionInteraction;Lnet/minecraft/core/particles/ParticleOptions;Lnet/minecraft/core/particles/ParticleOptions;Lnet/minecraft/core/Holder;)V",
            at = @At("HEAD"))
    private void serverflashback$onExplode(Entity source,
                                           net.minecraft.world.damagesource.DamageSource damageSource,
                                           net.minecraft.world.level.ExplosionDamageCalculator calculator,
                                           double x, double y, double z, float radius, boolean fire,
                                           net.minecraft.world.level.Level.ExplosionInteraction interaction,
                                           ParticleOptions smallParticles, ParticleOptions largeParticles,
                                           Holder<SoundEvent> sound, CallbackInfo ci) {
        if (RecordingManager.getInstance().hasActiveRecordings()) {
            ClientboundExplodePacket packet = new ClientboundExplodePacket(
                    new Vec3(x, y, z), java.util.Optional.empty(), largeParticles, sound);
            RecordingManager.getInstance().onPositionedGamePacket(
                    (ServerLevel) (Object) this, x, z, packet);
        }
    }
//#else
//$$     @Inject(method = "explode(Lnet/minecraft/world/entity/Entity;Lnet/minecraft/world/damagesource/DamageSource;Lnet/minecraft/world/level/ExplosionDamageCalculator;DDDFZLnet/minecraft/world/level/Explosion$BlockInteraction;)Lnet/minecraft/world/level/Explosion;",
//$$             at = @At("HEAD"))
//$$     private void serverflashback$onExplode(Entity source,
//$$                                            net.minecraft.world.damagesource.DamageSource damageSource,
//$$                                            net.minecraft.world.level.ExplosionDamageCalculator calculator,
//$$                                            double x, double y, double z, float radius, boolean fire,
//$$                                            net.minecraft.world.level.Explosion.BlockInteraction interaction,
//$$                                            CallbackInfoReturnable<net.minecraft.world.level.Explosion> cir) {
//$$         if (RecordingManager.getInstance().hasActiveRecordings()) {
//$$             ClientboundExplodePacket packet = new ClientboundExplodePacket(
//$$                     x, y, z, radius, java.util.Collections.emptyList(), Vec3.ZERO);
//$$             RecordingManager.getInstance().onPositionedGamePacket(
//$$                     (ServerLevel) (Object) this, x, z, packet);
//$$         }
//$$     }
//#endif
}
