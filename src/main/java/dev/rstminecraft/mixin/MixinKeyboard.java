package dev.rstminecraft.mixin;


import net.minecraft.client.Keyboard;
import net.minecraft.client.input.KeyInput;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

import static dev.rstminecraft.RustElytraClient.blockedPlayerInput;

@Mixin(Keyboard.class)
public class MixinKeyboard {
    @Inject(method = "onKey", at = @At("HEAD"), cancellable = true)
    private void onHardwareKey(long window, int action, KeyInput input, CallbackInfo ci) {
        if (blockedPlayerInput) {
            ci.cancel();
        }
    }
}