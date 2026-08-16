package dev.rstminecraft.mixin;

import net.minecraft.client.render.RenderTickCounter;
import org.objectweb.asm.Opcodes;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

import static dev.rstminecraft.RustElytraClient.timerMultiplier;

@Mixin(RenderTickCounter.Dynamic.class)
public abstract class TimerMixin {
    @Shadow
    private float dynamicDeltaTicks;

    /**
     * 目标方法: private int beginRenderTick(long timeMillis)
     * 注入点: 当 dynamicDeltaTicks 计算完毕并存入字段后，立即乘以倍率
     * 目标字段: lastTimeMillis (1.21.11 中对应的字段名)
     */
    @Inject(method = "beginRenderTick(J)I", at = @At(value = "FIELD", target = "Lnet/minecraft/client/render/RenderTickCounter$Dynamic;lastTimeMillis:J", opcode = Opcodes.PUTFIELD))
    private void onBeginRenderTick(long timeMillis, CallbackInfoReturnable<Integer> info) {
        dynamicDeltaTicks *= timerMultiplier;
    }
}