package dev.rstminecraft.mixin.noKinetic;


import dev.rstminecraft.elytra.NoKineticDamage;
import net.fabricmc.api.EnvType;
import net.fabricmc.api.Environment;
import net.minecraft.client.network.ClientPlayerEntity;
import net.minecraft.entity.player.PlayerEntity;
import net.minecraft.util.math.Vec3d;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

@Environment(EnvType.CLIENT)
@Mixin(PlayerEntity.class)
public abstract class PlayerTravelMixin {
    @Inject(method = "travel", at = @At("HEAD"))
    private void beforeTravel(Vec3d movementInput, CallbackInfo ci) {
        if ((Object) this instanceof ClientPlayerEntity player) {
            NoKineticDamage.onTravelHead(player);
        }
    }
}
