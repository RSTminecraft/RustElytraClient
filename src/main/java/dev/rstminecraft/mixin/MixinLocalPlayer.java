package dev.rstminecraft.mixin;

import dev.rstminecraft.utils.SilentRotation;
import net.minecraft.client.network.ClientPlayerEntity;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

@Mixin(ClientPlayerEntity.class)
public class MixinLocalPlayer {

    @Inject(method = "tickMovement", at = @At("HEAD"))
    private void tickMovementMixin(CallbackInfo ci) {
        ClientPlayerEntity self = (ClientPlayerEntity) (Object) this;
        if (SilentRotation.isActive()) {
            SilentRotation.savePrevRotation(self.getYaw(), self.getPitch());
            self.setYaw(SilentRotation.getTargetYaw());
            self.setPitch(SilentRotation.getTargetPitch());
        }
    }
}
