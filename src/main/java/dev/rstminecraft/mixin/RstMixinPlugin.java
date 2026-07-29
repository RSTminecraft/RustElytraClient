package dev.rstminecraft.mixin;


import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;
import org.objectweb.asm.tree.ClassNode;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.spongepowered.asm.mixin.extensibility.IMixinConfigPlugin;
import org.spongepowered.asm.mixin.extensibility.IMixinInfo;
import org.spongepowered.asm.mixin.transformer.ClassInfo;

import java.util.List;
import java.util.Set;

public class RstMixinPlugin implements IMixinConfigPlugin {

    public static final Logger MODLOGGER = LoggerFactory.getLogger("rust-RstMixinPlugin-client");

    @Override
    public boolean shouldApplyMixin(@NotNull String targetClassName, @NotNull String mixinClassName) {
        MODLOGGER.info("try mixin to {} from {}", targetClassName, mixinClassName);
        if (mixinClassName.contains("dev.rstminecraft.mixin.BaritoneUpdateTarget") || mixinClassName.contains("dev.rstminecraft.mixin.PausedTestMixin")) {
            ClassInfo info = ClassInfo.forName(targetClassName);
            if (info == null) {
                MODLOGGER.error("mixin to {} from {} failed!!!,Class Not Found!!!", targetClassName, mixinClassName);
                return false;
            }
            if (info.isInterface()) {
                MODLOGGER.error("mixin to {} from {} failed!!!,Is Interface!!!", targetClassName, mixinClassName);
                return false;
            }
        }
        return true;
    }

    @Override
    public void onLoad(String mixinPackage) {
    }

    @Override
    public @Nullable String getRefMapperConfig() {
        return null;
    }

    @Override
    public void acceptTargets(Set<String> myTargets, Set<String> otherTargets) {
    }

    @Override
    public @Nullable List<String> getMixins() {
        return null;
    }

    @Override
    public void preApply(String targetClassName, ClassNode targetClass, String mixinClassName, IMixinInfo mixinInfo) {
    }

    @Override
    public void postApply(String targetClassName, ClassNode targetClass, String mixinClassName, IMixinInfo mixinInfo) {
    }
}