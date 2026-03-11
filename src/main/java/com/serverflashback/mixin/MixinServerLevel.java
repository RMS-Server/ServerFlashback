package com.serverflashback.mixin;

import com.serverflashback.record.RecordingManager;
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

    @Inject(method = "setBlock(Lnet/minecraft/core/BlockPos;Lnet/minecraft/world/level/block/state/BlockState;II)Z",
            at = @At("RETURN"))
    private void serverflashback$onSetBlock(BlockPos pos, BlockState state, int flags, int recursionLeft,
                                            CallbackInfoReturnable<Boolean> cir) {
        if (cir.getReturnValue() && (Object) this instanceof ServerLevel serverLevel
                && RecordingManager.getInstance().hasActiveRecordings()) {
            RecordingManager.getInstance().onBlockChange(serverLevel, pos, state);
        }
    }
}
