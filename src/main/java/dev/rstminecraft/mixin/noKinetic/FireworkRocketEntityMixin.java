package dev.rstminecraft.mixin.noKinetic;

import dev.rstminecraft.elytra.NoKineticDamage;
import net.fabricmc.api.EnvType;
import net.fabricmc.api.Environment;
import net.minecraft.client.MinecraftClient;
import net.minecraft.entity.Entity;
import net.minecraft.entity.data.TrackedData;
import net.minecraft.entity.projectile.FireworkRocketEntity;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

@Environment(EnvType.CLIENT)
@Mixin(Entity.class)
public abstract class FireworkRocketEntityMixin {
    @Inject(method = "onTrackedDataSet", at = @At("TAIL"))
    private void afterTracked(TrackedData<?> data, CallbackInfo ci) {
        if ((Object) this instanceof FireworkRocketEntity firework) {
            MinecraftClient mc = MinecraftClient.getInstance();
            if (mc.player == null || mc.world == null) {
                return;
            }
            if(firework.getOwner() == mc.player) {
                NoKineticDamage.onFireworkSpawn(firework);
            }
        }
    }
}
