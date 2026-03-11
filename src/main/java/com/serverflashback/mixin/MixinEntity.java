package com.serverflashback.mixin;

import com.serverflashback.record.RecordingManager;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.entity.Entity;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

@Mixin(Entity.class)
public class MixinEntity {

    @Inject(method = "setRemoved", at = @At("HEAD"))
    private void serverflashback$onSetRemoved(Entity.RemovalReason reason, CallbackInfo ci) {
        if (reason != Entity.RemovalReason.KILLED && reason != Entity.RemovalReason.DISCARDED) return;
        Entity self = (Entity) (Object) this;
//#if MC >= 12002
        if (self.level() instanceof ServerLevel serverLevel
//#else
//$$         if (self.level instanceof ServerLevel serverLevel
//#endif
                && RecordingManager.getInstance().hasActiveRecordings()) {
            RecordingManager.getInstance().onEntityDespawn(serverLevel, self);
        }
    }
}
