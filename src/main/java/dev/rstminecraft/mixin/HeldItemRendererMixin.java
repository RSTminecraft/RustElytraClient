package dev.rstminecraft.mixin;

import net.fabricmc.api.EnvType;
import net.fabricmc.api.Environment;
import net.minecraft.client.network.AbstractClientPlayerEntity;
import net.minecraft.client.render.command.OrderedRenderCommandQueue;
import net.minecraft.client.render.item.HeldItemRenderer;
import net.minecraft.client.util.math.MatrixStack;
import net.minecraft.item.ItemStack;
import net.minecraft.util.Hand;
import org.jetbrains.annotations.NotNull;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

import static dev.rstminecraft.RustElytraClient.cameraMixinSwitch;

@Environment(EnvType.CLIENT)
@Mixin(HeldItemRenderer.class)
public class HeldItemRendererMixin {
    @Inject(method = "renderFirstPersonItem", at = @At("HEAD"), cancellable = true)
    private void hideHand(AbstractClientPlayerEntity abstractClientPlayerEntity, float f, float g, Hand hand, float h,
                          ItemStack itemStack, float i, MatrixStack matrixStack,
                          OrderedRenderCommandQueue orderedRenderCommandQueue, int j, @NotNull CallbackInfo ci) {
        if (cameraMixinSwitch) {
            ci.cancel(); // 取消渲染
        }
    }
}