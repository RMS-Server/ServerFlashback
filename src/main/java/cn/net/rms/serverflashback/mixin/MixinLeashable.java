package cn.net.rms.serverflashback.mixin;

import cn.net.rms.serverflashback.record.RecordingManager;
import net.minecraft.network.protocol.game.ClientboundSetEntityLinkPacket;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.entity.Entity;
//#if MC >= 12005
import net.minecraft.world.entity.Leashable;
//#else
//$$ import net.minecraft.world.entity.Mob;
//#endif
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

//#if MC >= 12005
@Mixin(Leashable.class)
public interface MixinLeashable {
//#else
//$$ @Mixin(Mob.class)
//$$ public abstract class MixinLeashable {
//#endif

//#if MC >= 12005
    @Inject(method = "setLeashedTo", at = @At("RETURN"))
    private void serverflashback$onSetLeashedTo(Entity leashHolder, boolean broadcast, CallbackInfo ci) {
        if (!broadcast) return;
        Entity self = (Entity) (Object) this;
        if (self.level() instanceof ServerLevel serverLevel
                && RecordingManager.getInstance().hasActiveRecordings()) {
            ClientboundSetEntityLinkPacket packet = new ClientboundSetEntityLinkPacket(self, leashHolder);
            RecordingManager.getInstance().onPositionedGamePacket(
                    serverLevel, self.getX(), self.getZ(), packet);
        }
    }

    @Inject(method = "dropLeash", at = @At("HEAD"))
    private void serverflashback$onDropLeash(CallbackInfo ci) {
        Entity self = (Entity) (Object) this;
        if (self.level() instanceof ServerLevel serverLevel
                && RecordingManager.getInstance().hasActiveRecordings()) {
            ClientboundSetEntityLinkPacket packet = new ClientboundSetEntityLinkPacket(self, null);
            RecordingManager.getInstance().onPositionedGamePacket(
                    serverLevel, self.getX(), self.getZ(), packet);
        }
    }
//#else
//$$     @Inject(method = "setLeashedTo", at = @At("RETURN"))
//$$     private void serverflashback$onSetLeashedTo(Entity leashHolder, boolean broadcast, CallbackInfo ci) {
//$$         if (!broadcast) return;
//$$         Entity self = (Entity) (Object) this;
//$$         if (self.level instanceof ServerLevel serverLevel
//$$                 && RecordingManager.getInstance().hasActiveRecordings()) {
//$$             ClientboundSetEntityLinkPacket packet = new ClientboundSetEntityLinkPacket(self, leashHolder);
//$$             RecordingManager.getInstance().onPositionedGamePacket(
//$$                     serverLevel, self.getX(), self.getZ(), packet);
//$$         }
//$$     }
//$$
//$$     @Inject(method = "dropLeash", at = @At("HEAD"))
//$$     private void serverflashback$onDropLeash(boolean sendPacket, boolean dropItem, CallbackInfo ci) {
//$$         if (!sendPacket) return;
//$$         Entity self = (Entity) (Object) this;
//$$         if (self.level instanceof ServerLevel serverLevel
//$$                 && RecordingManager.getInstance().hasActiveRecordings()) {
//$$             ClientboundSetEntityLinkPacket packet = new ClientboundSetEntityLinkPacket(self, null);
//$$             RecordingManager.getInstance().onPositionedGamePacket(
//$$                     serverLevel, self.getX(), self.getZ(), packet);
//$$         }
//$$     }
//#endif
}
