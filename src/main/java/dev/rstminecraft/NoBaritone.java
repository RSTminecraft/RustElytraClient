package dev.rstminecraft;

import net.minecraft.client.gui.DrawContext;
import net.minecraft.client.gui.screen.Screen;
import net.minecraft.client.gui.widget.ButtonWidget;
import net.minecraft.text.Text;
import org.jetbrains.annotations.NotNull;


public class NoBaritone extends Screen {
    private final NoBaritoneReason reason;
    private final boolean mustQuit;

    public NoBaritone(NoBaritoneReason reason, boolean mustQuit) {
        super(Text.literal("缺少核心依赖"));
        this.reason = reason;
        this.mustQuit = mustQuit;
    }

    @Override
    protected void init() {
        if (mustQuit)
            // 添加一个“退出游戏”按钮
            addDrawableChild(ButtonWidget.builder(Text.literal("退出游戏"), button -> {
                if (client != null) {
                    client.scheduleStop();
                }
            }).dimensions(width / 2 - 100, height / 2 + 20, 200, 20).build());
        else {
            addDrawableChild(ButtonWidget.builder(Text.literal("退出游戏"), button -> {
                if (client != null) {
                    client.scheduleStop();
                }
            }).dimensions(width / 3 - 60, height / 2 + 20, 120, 20).build());
            addDrawableChild(ButtonWidget.builder(Text.literal("退出游戏"), button -> {
                if (client != null) {
                    client.scheduleStop();
                }
            }).dimensions(width / 3 * 2 - 60, height / 2 + 20, 120, 20).build());
        }
    }

    @Override
    public void render(@NotNull DrawContext context, int mouseX, int mouseY, float delta) {
        super.render(context, mouseX, mouseY, delta);
        switch (reason) {
            case NoModId -> {
                context.drawCenteredTextWithShadow(textRenderer, "Rust Elytra Client 未检测到 Baritone 模组！", width / 2, height / 2 - 50, 0xFFFFFFFF);
                context.drawCenteredTextWithShadow(textRenderer, "请安装原生 Baritone 或 Meteor 版以运行模组！", width / 2, height / 2 - 30, 0xFFAAAAAA);
                context.drawCenteredTextWithShadow(textRenderer, "手机版用户可使用BaritonePE。（详见QQ群）", width / 2, height / 2 - 10, 0xFFAAAAAA);
            }
            case NoAPI -> {
                context.drawCenteredTextWithShadow(textRenderer, "您似乎使用了错误的Baritone(Standalone版本)", width / 2, height / 2 - 50, 0xFFFFFFFF);
                context.drawCenteredTextWithShadow(textRenderer, "此版本不包含可供模组调用的API,请使用API版、unoptimized版或彗星版", width / 2, height / 2 - 30, 0xFFAAAAAA);
                context.drawCenteredTextWithShadow(textRenderer, "若您不确定应该用哪个版本，请使用QQ群文件中的推荐版", width / 2, height / 2 - 10, 0xFFAAAAAA);
            }
        }

    }

    @Override
    public boolean shouldCloseOnEsc() {
        return !mustQuit;
    }

    public enum NoBaritoneReason {
        NoModId, NoAPI
    }
}
