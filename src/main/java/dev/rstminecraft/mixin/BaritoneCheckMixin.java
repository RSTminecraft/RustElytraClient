package dev.rstminecraft.mixin;

import dev.rstminecraft.NoBaritone;
import dev.rstminecraft.RustElytraClient;
import net.fabricmc.loader.api.FabricLoader;
import net.minecraft.client.MinecraftClient;
import net.minecraft.client.gui.screen.Screen;
import net.minecraft.client.gui.screen.TitleScreen;
import net.minecraft.text.Text;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

@Mixin(TitleScreen.class)
public class BaritoneCheckMixin extends Screen {
    protected BaritoneCheckMixin(Text title) {
        super(title);
    }

    @Inject(at = @At("HEAD"), method = "init")
    private void onInit(CallbackInfo ci) {
        // 检测是否安装了 Baritone
        boolean baritonePresent = FabricLoader.getInstance().isModLoaded("baritone") || FabricLoader.getInstance().isModLoaded("baritone-meteor");
        if (!baritonePresent) {
            MinecraftClient.getInstance().setScreen(new NoBaritone(NoBaritone.NoBaritoneReason.NoModId, true));
            return;
        }
        boolean hasAPI = true;
        try {
            Class.forName("baritone.api.BaritoneAPI", false, getClass().getClassLoader());
        } catch (ClassNotFoundException e) {
            hasAPI = false;
        }


        if (!hasAPI) {
            MinecraftClient.getInstance().setScreen(new NoBaritone(NoBaritone.NoBaritoneReason.NoAPI, true));
        }
    }
}