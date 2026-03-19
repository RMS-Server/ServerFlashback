package cn.net.rms.serverflashback.mixin;

import cn.net.rms.serverflashback.record.RecordingManager;
import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.state.BlockState;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

@Mixin(Level.class)
public class MixinServerLevel {

    @Inject(method = "removeBlock(Lnet/minecraft/core/BlockPos;Z)Z", at = @At("RETURN"))
    private void serverflashback$onRemoveBlockDuringBlockEvent(BlockPos pos, boolean move,
                                                               CallbackInfoReturnable<Boolean> cir) {
//#if MC >= 11900
        return;
//#else
//$$         if (!cir.getReturnValue()) return;
//$$         if (!RecordingManager.isBlockEventInProgress()) return;
//$$         if (!((Object) this instanceof ServerLevel serverLevel)) return;
//$$         if (!RecordingManager.getInstance().hasActiveRecordings()) return;
//$$
//$$         BlockState newState = ((Level) (Object) this).getBlockState(pos);
//$$         RecordingManager.getInstance().onBlockChange(serverLevel, pos, newState);
//#endif
    }
}
