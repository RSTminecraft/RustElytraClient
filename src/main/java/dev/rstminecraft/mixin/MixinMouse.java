package dev.rstminecraft.mixin;

import net.minecraft.client.Mouse;
import net.minecraft.client.input.MouseInput;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

import static dev.rstminecraft.RustElytraClient.blockedPlayerInput;

@Mixin(Mouse.class)
public class MixinMouse {

    // 拦截物理鼠标移动
    @Inject(method = "onCursorPos", at = @At("HEAD"), cancellable = true)
    private void onHardwareMouseMove(long window, double x, double y, CallbackInfo ci) {
        if (blockedPlayerInput) {
            ci.cancel();
        }
    }

    // 拦截物理鼠标点击
    @Inject(method = "onMouseButton", at = @At("HEAD"), cancellable = true)
    private void onHardwareMouseClick(long window, MouseInput input, int action, CallbackInfo ci) {
        if (blockedPlayerInput) {
            ci.cancel();
        }
    }

    // 拦截物理鼠标滚轮
    @Inject(method = "onMouseScroll", at = @At("HEAD"), cancellable = true)
    private void onHardwareMouseScroll(long window, double horizontal, double vertical, CallbackInfo ci) {
        if (blockedPlayerInput) {
            ci.cancel();
        }
    }
}
