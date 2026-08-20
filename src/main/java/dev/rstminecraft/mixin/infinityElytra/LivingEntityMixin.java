package dev.rstminecraft.mixin.infinityElytra;

import dev.rstminecraft.RustElytraClient;
import dev.rstminecraft.elytra.infinityElytra;
import net.minecraft.client.network.ClientPlayerEntity;
import net.minecraft.entity.LivingEntity;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

@Mixin(LivingEntity.class)
public abstract class LivingEntityMixin {
    @Shadow
    protected int glidingTicks;

    @Inject(method = "tickGliding", at = @At("HEAD"))
    private void onTickGliding(CallbackInfo ci) {
        LivingEntity self = (LivingEntity) (Object) this;
        if (!(self instanceof ClientPlayerEntity player))
            return;

        // 每 tick 递减 POSE 宽限期
        infinityElytra.tickPoseLock();

        if (!infinityElytra.shouldUnbreakable(player))
            return;

        if (infinityElytra.transactionPending) {
            infinityElytra.durabilityCounter = 0;
            return;
        }

        infinityElytra.durabilityCounter++;

        if (infinityElytra.durabilityCounter >= infinityElytra.period * RustElytraClient.timerMultiplier
            && infinityElytra.canContinueGliding(player)) {
            infinityElytra.startTransaction(player);
            if (infinityElytra.resetVanilla) {
                glidingTicks = 0;
            }
        }
    }


}
