package cn.net.rms.serverflashback.mixin;

import cn.net.rms.serverflashback.record.RecordingManager;
import net.minecraft.network.protocol.game.ClientboundRemoveMobEffectPacket;
import net.minecraft.network.protocol.game.ClientboundUpdateMobEffectPacket;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.effect.MobEffect;
import net.minecraft.world.effect.MobEffectInstance;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.LivingEntity;
//#if MC >= 12005
import java.util.Collection;
import net.minecraft.core.Holder;
//#endif
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

@Mixin(LivingEntity.class)
public class MixinLivingEntityEffects {

    @Inject(method = "onEffectAdded", at = @At("RETURN"))
    private void serverflashback$onEffectAdded(MobEffectInstance effect, Entity source,
                                               CallbackInfo ci) {
        Entity self = (Entity) (Object) this;
//#if MC >= 12002
        if (self.level() instanceof ServerLevel serverLevel
//#else
//$$         if (self.level instanceof ServerLevel serverLevel
//#endif
                && RecordingManager.getInstance().hasActiveRecordings()) {
//#if MC >= 11900
            ClientboundUpdateMobEffectPacket packet = new ClientboundUpdateMobEffectPacket(self.getId(), effect, true);
//#else
//$$             ClientboundUpdateMobEffectPacket packet = new ClientboundUpdateMobEffectPacket(self.getId(), effect);
//#endif
            RecordingManager.getInstance().onPositionedGamePacket(
                    serverLevel, self.getX(), self.getZ(), packet);
        }
    }

    @Inject(method = "onEffectUpdated", at = @At("RETURN"))
    private void serverflashback$onEffectUpdated(MobEffectInstance effect, boolean reapply,
                                                  Entity source, CallbackInfo ci) {
        Entity self = (Entity) (Object) this;
//#if MC >= 12002
        if (self.level() instanceof ServerLevel serverLevel
//#else
//$$         if (self.level instanceof ServerLevel serverLevel
//#endif
                && RecordingManager.getInstance().hasActiveRecordings()) {
//#if MC >= 11900
            ClientboundUpdateMobEffectPacket packet = new ClientboundUpdateMobEffectPacket(self.getId(), effect, false);
//#else
//$$             ClientboundUpdateMobEffectPacket packet = new ClientboundUpdateMobEffectPacket(self.getId(), effect);
//#endif
            RecordingManager.getInstance().onPositionedGamePacket(
                    serverLevel, self.getX(), self.getZ(), packet);
        }
    }

//#if MC >= 12005
    @Inject(method = "onEffectsRemoved", at = @At("RETURN"))
    private void serverflashback$onEffectRemoved(Collection<MobEffectInstance> effects, CallbackInfo ci) {
        Entity self = (Entity) (Object) this;
        if (self.level() instanceof ServerLevel serverLevel
                && RecordingManager.getInstance().hasActiveRecordings()) {
            for (MobEffectInstance effect : effects) {
                ClientboundRemoveMobEffectPacket packet = new ClientboundRemoveMobEffectPacket(
                        self.getId(), effect.getEffect());
                RecordingManager.getInstance().onPositionedGamePacket(
                        serverLevel, self.getX(), self.getZ(), packet);
            }
        }
    }
//#else
//$$     @Inject(method = "onEffectRemoved", at = @At("RETURN"))
//$$     private void serverflashback$onEffectRemoved(MobEffectInstance effect, CallbackInfo ci) {
//$$         Entity self = (Entity) (Object) this;
//$$         if (self.level instanceof ServerLevel serverLevel
//$$                 && RecordingManager.getInstance().hasActiveRecordings()) {
//$$             ClientboundRemoveMobEffectPacket packet = new ClientboundRemoveMobEffectPacket(
//$$                     self.getId(), effect.getEffect());
//$$             RecordingManager.getInstance().onPositionedGamePacket(
//$$                     serverLevel, self.getX(), self.getZ(), packet);
//$$         }
//$$     }
//#endif
}
