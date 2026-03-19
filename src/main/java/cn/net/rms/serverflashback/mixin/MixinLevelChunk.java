package cn.net.rms.serverflashback.mixin;

import cn.net.rms.serverflashback.record.RecordingManager;
import net.minecraft.core.BlockPos;
//#if MC >= 11900
import net.minecraft.network.protocol.game.ClientboundBlockEntityDataPacket;
//#endif
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.chunk.LevelChunk;
import net.minecraft.world.level.block.entity.BlockEntity;
//#if MC >= 11900
import net.minecraft.world.level.block.entity.BlockEntityType;
//#endif
import net.minecraft.world.level.block.state.BlockState;
import org.spongepowered.asm.mixin.Final;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

/**
 * Hooks LevelChunk.setBlockState to access the {@code moved} flag unavailable at Level.setBlock level,
 * enabling fine-grained filtering: during block events, only skip blocks physically moved by pistons
 * (moved=true) to preserve client-side animation, while still capturing other block changes.
 */
@Mixin(LevelChunk.class)
public class MixinLevelChunk {

    @Shadow
    @Final
    private Level level;

    @Inject(method = "setBlockState(Lnet/minecraft/core/BlockPos;Lnet/minecraft/world/level/block/state/BlockState;Z)Lnet/minecraft/world/level/block/state/BlockState;",
            at = @At("RETURN"))
    private void serverflashback$onSetBlockState(BlockPos pos, BlockState state, boolean moved,
                                                 CallbackInfoReturnable<BlockState> cir) {
        if (cir.getReturnValue() == null) return;
        if (!(level instanceof ServerLevel serverLevel)) return;
        if (!RecordingManager.getInstance().hasActiveRecordings()) return;
//#if MC < 11900
//$$         if (RecordingManager.isBlockEventInProgress()) return;
//#else
        // During piston block events, the client-side animation is driven by the block event
        // plus moving-piston block entity data. Recording the moved intermediate block states
        // here makes the replay jump straight to the post-move snapshot and kills the animation.
        if (RecordingManager.isBlockEventInProgress() && moved) return;
//#endif

        LevelChunk chunk = (LevelChunk) (Object) this;
        BlockState currentState = chunk.getBlockState(pos);
        // Nested callbacks can mutate the same position before this invocation returns
        // (e.g. moving_piston -> TNT -> air). Only record the final surviving state.
        if (currentState != state) return;

        RecordingManager.getInstance().onBlockChange(serverLevel, pos, state);

        BlockEntity blockEntity = chunk.getBlockEntity(pos);
        if (blockEntity != null) {
            var updatePacket = blockEntity.getUpdatePacket();
            if (updatePacket != null) {
                RecordingManager.getInstance().onPositionedGamePacket(
                        serverLevel, pos.getX(), pos.getZ(), updatePacket);
            }
        }
    }

    @Inject(method = "setBlockEntity", at = @At("RETURN"))
    private void serverflashback$onSetBlockEntity(BlockEntity blockEntity, CallbackInfo ci) {
        if (!(level instanceof ServerLevel serverLevel)) return;
        if (!RecordingManager.getInstance().hasActiveRecordings()) return;
//#if MC >= 11900
        if (blockEntity.getType() != BlockEntityType.PISTON) return;

        RecordingManager.getInstance().onPositionedGamePacket(
                serverLevel,
                blockEntity.getBlockPos().getX(),
                blockEntity.getBlockPos().getZ(),
                ClientboundBlockEntityDataPacket.create(blockEntity)
        );
//#endif
    }
}
