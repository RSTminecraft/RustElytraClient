package dev.rstminecraft.mixin;

import net.minecraft.entity.Entity;
import net.minecraft.entity.player.PlayerEntity;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

import static dev.rstminecraft.RustElytraClient.*;

@Mixin(Entity.class)
public abstract class EntityEyeHeightMixin {
    @Inject(method = "getStandingEyeHeight", at = @At("HEAD"), cancellable = true)
    private void redirectEyeHeight(CallbackInfoReturnable<Float> cir) {
        if (((Object) this instanceof PlayerEntity) && fixEyeHeight){
            // 0.6F 是鞘翅滑翔时的标准眼睛高度
            cir.setReturnValue(0.6f);
        }
    }
}