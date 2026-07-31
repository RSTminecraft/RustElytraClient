package dev.rstminecraft.mixin.takeoffCheckerMixin;

import baritone.process.ElytraProcess;
import baritone.process.elytra.ElytraBehavior;
import net.minecraft.client.MinecraftClient;
import net.minecraft.text.Text;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Redirect;

@Mixin(ElytraProcess.class)
public class baritoneLOL {

    @Redirect(
            // 目标方法已经变为了 onTick，包含两个 boolean 参数
            method = "onTick(ZZ)Lbaritone/api/process/PathingCommand;",
            at = @At(
                    value = "INVOKE",
                    // 这里的目标需要指向变混淆后的类和方法名（var4 对应的真实类名和方法 a）
                    // 请根据你反编译 var4 的真实类型，把下面的 path/to/Var4Class 替换掉
                    target = "Lbaritone/process/elytra/ElytraBehavior;a(Ljava/lang/String;)V"
            )
    )
    private void redirectNoSolutionLog(ElytraBehavior instance, String s) {
        instance.a(s);

       if ("no solution".equals(s)) {
           MinecraftClient c = MinecraftClient.getInstance();
           if(c.player != null){
               c.execute(()->c.player.sendMessage(Text.of("!!!"), false));
           }
        }
    }
}
