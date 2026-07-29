package dev.rstminecraft;

import net.minecraft.client.MinecraftClient;
import net.minecraft.client.gui.DrawContext;

import static dev.rstminecraft.RustElytraClient.*;

public class ModHud {
    public static void DrawHud(DrawContext context) {
        MinecraftClient client = MinecraftClient.getInstance();
        if (!enableHud.get() || ModTask.status == ModTask.TaskStatus.NO_TASK || client.player == null)
            return;
        StringBuilder sb = new StringBuilder();
        sb.append("当前状态:");
        switch (ModTask.type) {
            case ELYTRA -> sb.append("鞘翅模式");
            case EXP_BOTTLE -> sb.append("经验模式");
            case INFINITY_ELYTRA -> sb.append("无尽鞘翅模式");
        }
        sb.append(",");
        switch (ModTask.status) {
            case START -> sb.append("任务正在启动");
            case SUPPLY -> sb.append("正在获取补给");
            case FLYING -> sb.append("正在飞行");
            case REPAIR_ELYTRA -> sb.append("正在修补鞘翅");
            case LANDING -> sb.append("正在降落");
        }
        sb.append('\n');
        sb.append("已飞行距离:").append(String.format("%.2f", ModTask.TaskFlyDistance(client.player))).append('\n');
        sb.append("剩余飞行距离:").append(String.format("%.2f", ModTask.TaskRemainDistance(client.player))).append(
                '\n');
        sb.append("平均飞行速度:").append(String.format("%.2f", ModTask.TaskAverageSpeed(client.player))).append(
                " " + "m/s\n");
        int second = (int) ModTask.TaskRemainSecond(client.player); //这是随便输入的秒值
        int hour = second / 3600; // 得到分钟数
        second = second % 3600;//剩余的秒数
        int minute = second / 60;//得到分
        second = second % 60;//剩余的秒
        sb.append(String.format("预计剩余时间:%02d:%02d:%02d", hour, minute, second));
        String[] strings = sb.toString().split("\n");
        for (int i = 0; i < strings.length; i++) {
            context.drawText(MinecraftClient.getInstance().textRenderer, strings[i], HudX.get(), HudY.get() + 10 * i,
                             0xFFFFFFFF,
                             false);
        }
    }

}
