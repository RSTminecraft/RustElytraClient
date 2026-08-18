package dev.rstminecraft;

import dev.rstminecraft.RustClientCore.MinecraftContext;
import net.minecraft.client.gui.DrawContext;
import org.jetbrains.annotations.NotNull;

import static dev.rstminecraft.RustElytraClient.*;

public class ModHud {
    public static void DrawHud(@NotNull DrawContext context) {
        if (!enableHud.get() || ModTask.status == ModTask.TaskStatus.NO_TASK)
            return;
        StringBuilder sb = new StringBuilder();
        sb.append("当前状态:");
        sb.append("无尽鞘翅模式");
        sb.append(",");
        switch (ModTask.status) {
            case START -> sb.append("任务正在启动");
            case SUPPLY -> sb.append("正在获取补给");
            case FLYING -> sb.append("正在飞行");
            case REPAIR_ELYTRA -> sb.append("正在修补鞘翅");
            case LANDING -> sb.append("正在降落");
        }
        sb.append('\n');
        sb.append("已飞行距离:").append(String.format("%.2f", ModTask.TaskFlyDistance())).append('\n');
        sb.append("剩余飞行距离:").append(String.format("%.2f", ModTask.TaskRemainDistance())).append(
                '\n');
        sb.append("平均飞行速度:").append(String.format("%.2f", ModTask.TaskAverageSpeed())).append(
                " " + "m/s\n");
        int second = (int) ModTask.TaskRemainSecond();
        int hour = second / 3600; // 得到分钟数
        second = second % 3600;//剩余的秒数
        int minute = second / 60;//得到分
        second = second % 60;//剩余的秒
        sb.append(String.format("预计剩余时间:%02d:%02d:%02d", hour, minute, second));
        String[] strings = sb.toString().split("\n");
        for (int i = 0; i < strings.length; i++) {
            context.drawText(MinecraftContext.client().textRenderer, strings[i], HudX.get(), HudY.get() + 10 * i,
                             0xFFFFFFFF,
                             false);
        }
    }

}
