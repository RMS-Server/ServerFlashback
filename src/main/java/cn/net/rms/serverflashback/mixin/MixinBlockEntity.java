package cn.net.rms.serverflashback.mixin;

import cn.net.rms.serverflashback.record.RecordingManager;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.block.entity.BlockEntity;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

@Mixin(BlockEntity.class)
public abstract class MixinBlockEntity {

    @Inject(method = "setChanged()V", at = @At("RETURN"))
    private void serverflashback$onSetChanged(CallbackInfo ci) {
        if (!RecordingManager.getInstance().hasActiveRecordings()) return;

        BlockEntity self = (BlockEntity) (Object) this;
        if (!(self.getLevel() instanceof ServerLevel serverLevel)) return;

        RecordingManager.getInstance().onBlockEntityChanged(serverLevel, self.getBlockPos());
    }
}
