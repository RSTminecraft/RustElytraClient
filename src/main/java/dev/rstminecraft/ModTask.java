package dev.rstminecraft;

import baritone.api.BaritoneAPI;
import dev.rstminecraft.RustClientTemplate.ModTaskManager;
import dev.rstminecraft.RustClientTemplate.MsgLevel;
import net.minecraft.client.MinecraftClient;
import net.minecraft.client.network.ClientPlayerEntity;
import net.minecraft.item.Items;
import net.minecraft.network.packet.s2c.common.DisconnectS2CPacket;
import net.minecraft.text.MutableText;
import net.minecraft.text.Text;
import net.minecraft.util.math.BlockPos;
import org.jetbrains.annotations.NotNull;

import static dev.rstminecraft.ElytraTask.elytraTask;
import static dev.rstminecraft.FireballProtect.FireballProtector;
import static dev.rstminecraft.RustElytraClient.*;
import static dev.rstminecraft.SupplyTask.supplyTask;

public class ModTask {
    public static TaskType type;
    public static TaskStatus status;
    private static int TargetX, TargetZ;
    private static boolean isAutoLog, isAutoLogOnSeg1;
    private static BlockPos StartPos;
    private static int startTick;

    // region 任务信息统计函数

    /**
     * 统计剩余距离
     *
     * @param player 非空玩家对象
     * @return 剩余距离
     */
    public static double TaskRemainDistance(@NotNull ClientPlayerEntity player) {
        if (status == TaskStatus.NO_TASK)
            return -1;
        return Math.sqrt(new BlockPos(TargetX, 0, TargetZ).getSquaredDistance(player.getBlockPos()));
    }

    /**
     * 统计已飞行距离
     *
     * @param player 非空玩家对象
     * @return 已飞行距离
     */
    public static double TaskFlyDistance(@NotNull ClientPlayerEntity player) {
        if (status == TaskStatus.NO_TASK)
            return -1;
        return Math.sqrt(StartPos.getSquaredDistance(player.getBlockPos()));
    }

    /**
     * 统计平均速度
     *
     * @param player 非空玩家对象
     * @return 平均速度
     */
    public static double TaskAverageSpeed(@NotNull ClientPlayerEntity player) {
        if (status == TaskStatus.NO_TASK)
            return -1;
        return TaskFlyDistance(player) / (currentTick - startTick) * 20;
    }

    /**
     * 统计剩余时间
     *
     * @param player 非空玩家对象
     * @return 剩余时间
     */
    public static double TaskRemainSecond(@NotNull ClientPlayerEntity player) {
        if (status == TaskStatus.NO_TASK)
            return -1;
        return TaskRemainDistance(player) / TaskAverageSpeed(player);
    }

    // endregion

    private static void resetMixin() {
        fixEyeHeight = false;
        cameraMixinSwitch = false;
        fixedYaw = 0f;
        fixedPitch = 0f;
        timerMultiplier = 1f;
    }

    private static void resetClient(@NotNull MinecraftClient client) {
        client.options.forwardKey.setPressed(false);
        client.options.jumpKey.setPressed(false);
        client.options.useKey.setPressed(false);

        if (BaritoneAPI.getProvider().getPrimaryBaritone().getElytraProcess().isActive() ||
            BaritoneAPI.getProvider().getPrimaryBaritone().getMineProcess().isActive()) {
            if (client.isOnThread())
                BaritoneAPI.getProvider().getPrimaryBaritone().getCommandManager().execute("stop");
            else
                ModTaskManager.runOnMainSync(
                        () -> BaritoneAPI.getProvider().getPrimaryBaritone().getCommandManager().execute("stop"));
        }
    }

    /**
     * 开启模组任务
     *
     * @param type            任务补给类型
     * @param isAutoLog       是否自动退出
     * @param isAutoLogOnSeg1 是否在第一段自动退出
     * @param TargetX         目的地X坐标
     * @param TargetZ         目的地Z坐标
     */
    public static void startTask(TaskType type, boolean isAutoLog, boolean isAutoLogOnSeg1, int TargetX, int TargetZ) {
        MinecraftClient client = MinecraftClient.getInstance();
        if (client == null)
            return;
        if (client.player == null)
            taskFailed(client, "player为null!", -1);
        if (status != TaskStatus.NO_TASK)
            taskFailed(client, "不能同时存在2个飞行任务!", -1);
        ModTask.type = type;
        ModTask.isAutoLog = isAutoLog;
        ModTask.isAutoLogOnSeg1 = isAutoLogOnSeg1;
        ModTask.TargetX = TargetX;
        ModTask.TargetZ = TargetZ;
        startTick = currentTick;

        StartPos = client.player.getBlockPos();
        status = TaskStatus.START;

        resetMixin();
        resetClient(client);
        ModTaskManager.startThread(() -> runTask(client)).setUncaughtExceptionHandler((thread, exception) -> {
            resetMixin();
            resetClient(client);
            status = TaskStatus.NO_TASK;
        });
    }

    private static void runTask(@NotNull MinecraftClient client) {
        if (client.player == null)
            taskFailed(client, "player为null!", -1);

        ModStatus = ModStatuses.running;

        for (int nowSeg = 0; ; nowSeg++) {
            int finalNowSeg = nowSeg;

            // region 补给任务
            msg.SendMsg(client.player, "第" + nowSeg + "段补给任务开始！", MsgLevel.info);

            // 开启补给保护任务
            Thread protectThread = ModTaskManager.startThread(() -> SupplyTaskProtector(client, finalNowSeg));

            // 开启补给任务
            try {
                supplyTask(client, type);
                // 关闭保护任务
                protectThread.interrupt();
                ModTaskManager.delay(1);
            } catch (TaskException e) {
                // 补给失败
                // 关闭保护任务
                protectThread.interrupt();
                e.printStackTrace();
                msg.SendMsg(client.player, e.getMessage(), MsgLevel.error);
                msg.SendMsg(client.player, "补给任务失败", MsgLevel.fatal);
                taskFailed(client, "补给任务失败！自动退出！", nowSeg - 1);
                break;
            } catch (TaskCanceled e) {
                // 关闭保护任务
                protectThread.interrupt();
                msg.SendMsg(client.player, "任务中止！", MsgLevel.warning);
                break;
            }
            // endregion

            // region 飞行任务
            msg.SendMsg(client.player, "第" + nowSeg + "段飞行任务开始！", MsgLevel.info);

            // 开启鞘翅保护任务
            protectThread = ModTaskManager.startThread(() -> ElytraTaskProtector(client, finalNowSeg));
            // 开启鞘翅任务
            try {
                if (elytraTask(client, TargetX, TargetZ, type)) {
                    msg.SendMsg(client.player, "到达目的地！圆满完成！！！", MsgLevel.warning);
                    if (isAutoLog) {
                        MutableText text = Text.literal("[RSTAutoLog] ");
                        text.append(Text.literal("已经到达目的地"));
                        client.player.networkHandler.onDisconnect(new DisconnectS2CPacket(text));
                    }
                    ModStatus = ModStatuses.idle;
                    // 关闭保护任务
                    protectThread.interrupt();
                    break;
                }
                // 关闭保护任务
                protectThread.interrupt();
                ModTaskManager.delay(1);

            } catch (TaskException e) {
                // 飞行失败
                msg.SendMsg(client.player, e.getMessage(), MsgLevel.error);
                e.printStackTrace();
                taskFailed(client, e.getMessage(), nowSeg);
                // 关闭保护任务
                protectThread.interrupt();
                break;
            } catch (TaskCanceled e) {
                msg.SendMsg(client.player, "任务中止！", MsgLevel.warning);
                // 关闭保护任务
                protectThread.interrupt();
                break;
            }
            // endregion
        }
        resetMixin();
        resetClient(client);
        status = TaskStatus.NO_TASK;
    }

    private static void SupplyTaskProtector(@NotNull MinecraftClient client, int nowSeg) {
        if (client.player == null)
            return;
        float h = client.player.getHealth();
        while (true) {
            if (!ModTaskManager.computeOnMain(() -> FireballProtector(client))) {
                ModStatus = ModStatuses.canceled;
                taskFailed(client, "无法拦截火球！自动退出！", nowSeg - 1);
                return;
            }
            if (client.player.getHealth() < h) {
                ModStatus = ModStatuses.canceled;
                taskFailed(client, "补给过程受伤！紧急！", nowSeg - 1);
                return;
            }
            ModTaskManager.delay(1);
        }
    }

    private static void ElytraTaskProtector(@NotNull MinecraftClient client, int nowSeg) {
        if (client.player == null)
            return;
        while (true) {
            if (client.player.getHealth() < 3.5) {
                int count = 0;
                for (int i = 0; i < 45; i++) {
                    if (client.player.getInventory().getStack(i).getItem() == Items.TOTEM_OF_UNDYING)
                        count += client.player.getInventory().getStack(i).getCount();
                }
                if (count <= 1) {
                    ModStatus = ModStatuses.canceled;
                    taskFailed(client, "AutoLog图腾数量过少且血量过低！", nowSeg);
                    return;
                }
            }
            ModTaskManager.delay(1);
        }
    }

    /**
     * 任务失败处理函数
     *
     * @param client 客户端对象
     * @param str    失败原因
     * @param seg    当前段数
     */
    public static void taskFailed(@NotNull MinecraftClient client, @NotNull String str, int seg) {
        client.options.forwardKey.setPressed(false);
        client.options.jumpKey.setPressed(false);
        client.options.useKey.setPressed(false);

        if (BaritoneAPI.getProvider().getPrimaryBaritone().getElytraProcess().isActive() ||
            BaritoneAPI.getProvider().getPrimaryBaritone().getMineProcess().isActive()) {
            if (client.isOnThread())
                BaritoneAPI.getProvider().getPrimaryBaritone().getCommandManager().execute("stop");
            else
                ModTaskManager.runOnMainSync(
                        () -> BaritoneAPI.getProvider().getPrimaryBaritone().getCommandManager().execute("stop"));
        }
        if (seg == -1 && isAutoLogOnSeg1 || seg != -1 && isAutoLog) {
            MutableText text = Text.literal("[RSTAutoLog] ");
            text.append(Text.literal(str));
            if (client.player != null) {
                client.player.networkHandler.onDisconnect(new DisconnectS2CPacket(text));
            }
        } else if (client.player != null) {
            msg.SendMsg(client.player, "任务结束。" + str, MsgLevel.fatal);
        }
        ModStatus = ModStatuses.idle;
    }

    // region 模组任务状态枚举
    public enum TaskType {
        EXP_BOTTLE, ELYTRA, INFINITY_ELYTRA
    }

    public enum TaskStatus {
        NO_TASK, START, SUPPLY, FLYING, LANDING, REPAIR_ELYTRA
    }
    // endregion

    // region mod异常
    // 一个异常：用于表达任务异常
    public static class TaskException extends RuntimeException {
        public TaskException(String reason) {
            super(reason);
        }
    }

    // 一个异常：用于表达任务中止
    public static class TaskCanceled extends RuntimeException {
        public TaskCanceled() {
            super("任务已经取消");
        }
    }
    // endregion

}
