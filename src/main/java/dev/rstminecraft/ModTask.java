package dev.rstminecraft;

import dev.rstminecraft.RustClientCore.MinecraftContext;
import dev.rstminecraft.RustClientCore.messenger.MsgLevel;
import dev.rstminecraft.RustClientCore.task.TaskManager;
import dev.rstminecraft.RustClientCore.task.TickPhase;
import dev.rstminecraft.elytra.ElytraTask;
import dev.rstminecraft.elytra.NoKineticDamage;
import dev.rstminecraft.elytra.infinityElytra;
import net.minecraft.client.MinecraftClient;
import net.minecraft.item.Items;
import net.minecraft.network.packet.s2c.common.DisconnectS2CPacket;
import net.minecraft.text.MutableText;
import net.minecraft.text.Text;
import net.minecraft.util.math.BlockPos;
import org.jetbrains.annotations.NotNull;

import java.util.concurrent.CompletableFuture;

import static dev.rstminecraft.RustElytraClient.*;
import static dev.rstminecraft.SupplyTask.supplyTask;

public class ModTask {
    public static volatile @NotNull TaskStatus status = TaskStatus.NO_TASK;
    private static int TargetX, TargetZ;
    private static boolean isAutoLog, isAutoLogOnSeg1;
    private static BlockPos StartPos;
    private static int startTick;

    // region 任务信息统计函数

    /**
     * 统计剩余距离
     *
     * @return 剩余距离
     */
    public static double TaskRemainDistance() {
        if (status == TaskStatus.NO_TASK)
            return -1;
        return Math.sqrt(new BlockPos(TargetX, 0, TargetZ).getSquaredDistance(MinecraftContext.player().getBlockPos()));
    }

    /**
     * 统计已飞行距离
     *
     * @return 已飞行距离
     */
    public static double TaskFlyDistance() {
        if (status == TaskStatus.NO_TASK)
            return -1;
        return Math.sqrt(StartPos.getSquaredDistance(MinecraftContext.player().getBlockPos()));
    }

    /**
     * 统计平均速度
     *
     * @return 平均速度
     */
    public static double TaskAverageSpeed() {
        if (status == TaskStatus.NO_TASK)
            return -1;
        return TaskFlyDistance() / (currentTick - startTick) * 20;
    }

    /**
     * 统计剩余时间
     *
     * @return 剩余时间
     */
    public static double TaskRemainSecond() {
        if (status == TaskStatus.NO_TASK)
            return -1;
        return TaskRemainDistance() / TaskAverageSpeed();
    }

    // endregion

    static void resetMixin() {
        timerMultiplier = 1f;
        blockedPlayerInput = false;
        infinityElytra.enabled = false;
        NoKineticDamage.enabled = false;
    }

    static void resetClient() {
        MinecraftContext.client().options.forwardKey.setPressed(false);
        MinecraftContext.client().options.jumpKey.setPressed(false);
        MinecraftContext.client().options.useKey.setPressed(false);
    }

    /**
     * 开启模组任务
     *
     * @param isAutoLog       是否自动退出
     * @param isAutoLogOnSeg1 是否在第一段自动退出
     * @param TargetX         目的地X坐标
     * @param TargetZ         目的地Z坐标
     */
    public static void startTask(boolean isAutoLog, boolean isAutoLogOnSeg1, int TargetX, int TargetZ) {
        MinecraftClient client = MinecraftClient.getInstance();
        if (client == null)
            return;
        if (client.player == null)
            taskFailed("player为null!", -1);
        if (status != TaskStatus.NO_TASK)
            taskFailed("不能同时存在2个飞行任务!", -1);

        ModTask.isAutoLog = isAutoLog;
        ModTask.isAutoLogOnSeg1 = isAutoLogOnSeg1;
        ModTask.TargetX = TargetX;
        ModTask.TargetZ = TargetZ;
        startTick = currentTick;

        StartPos = client.player.getBlockPos();
        status = TaskStatus.START;

        resetMixin();
        resetClient();


        ModStatus = ModStatuses.running;

        Thread modMainTask = TaskManager.build(ModTask::runTask).setPhase(TickPhase.PRE).atEnd(() -> {
            resetMixin();
            resetClient();

            status = TaskStatus.NO_TASK;
            ModStatus = ModStatuses.idle;
        }).start();

        TaskManager.build(() -> {
            while (ModStatus != ModStatuses.idle) {
                if (ModStatus == ModStatuses.canceled) {
                    msg.SendMsg("任务中止", MsgLevel.error);
                    modMainTask.interrupt();
                    return;
                }
                TaskManager.delay(1);
            }
        }).setPhase(TickPhase.POST).start();
    }

    private static void runTask() {


        for (int nowSeg = 0; ; nowSeg++) {
            int finalNowSeg = nowSeg;

            // region 补给任务
            msg.SendMsg("第" + nowSeg + "段补给任务开始！", MsgLevel.info);

            // 开启补给保护任务
            Thread SupplyProtectThread = TaskManager.build(() -> SupplyTaskProtector(finalNowSeg))
                    .setName("Rust Elytra 补给保护任务")
                    .daemon().setPhase(TickPhase.PRE)
                    .start();

            // 开启补给任务
            try {
                blockedPlayerInput = true;
                supplyTask();
            } catch (Exception e) {
                // 补给失败
                e.printStackTrace();
                taskFailed(e.getMessage(), nowSeg - 1);
                break;
            } finally {
                // 关闭保护任务
                SupplyProtectThread.interrupt();
                blockedPlayerInput = false;
            }

            TaskManager.delay(1);
            // endregion

            // region 飞行任务
            msg.SendMsg("第" + nowSeg + "段飞行任务开始！", MsgLevel.info);

            // 开启鞘翅保护任务
            Thread ElytraProtectThread = TaskManager.build(() -> ElytraTaskProtector(finalNowSeg)).setName("Rust Elytra 鞘翅保护任务")
                    .daemon().setPhase(TickPhase.PRE).start();
            // 开启鞘翅任务
            try {
                ElytraTask et = new ElytraTask(new BlockPos(TargetX, 64, TargetZ));
                CompletableFuture<Exception> latch = new CompletableFuture<>();
                infinityElytra.enabled = true;
                NoKineticDamage.enabled = true;
                TaskManager.build(()->{
                    try {
                        et.run();
                        latch.complete(null);
                    } catch (Exception e){
                        latch.complete(e);
                    }
                }).setPhase(TickPhase.PRE).daemon().setName("RustElytra鞘翅主任务").start();
                while (!latch.isDone())
                    TaskManager.delay(1);
                if(latch.join() != null)
                    throw latch.join();
                if (MinecraftContext.player().getBlockPos().isWithinDistance(new BlockPos(TargetX, 0, TargetZ), 3801)) {
                    msg.SendMsg("到达目的地！圆满完成！！！", MsgLevel.warning);
                    if (isAutoLog) {
                        MutableText text = Text.literal("[RustAutoLog] ");
                        text.append(Text.literal("已经到达目的地"));
                        MinecraftContext.player().networkHandler.onDisconnect(new DisconnectS2CPacket(text));
                    }
                    status = TaskStatus.NO_TASK;
                    ModStatus = ModStatuses.idle;
                    break;
                }
            } catch (Exception e) {
                // 飞行失败
                e.printStackTrace();
                taskFailed(e.getMessage(), nowSeg);
                break;
            } finally {
                // 关闭保护任务
                ElytraProtectThread.interrupt();
                infinityElytra.enabled = false;
                NoKineticDamage.enabled = false;
            }

            TaskManager.delay(1);
            // endregion
        }

    }

    private static void SupplyTaskProtector(int nowSeg) {
        float h = MinecraftContext.player().getHealth();
        while (true) {
            if (!TaskManager.computeOnMain(FireballProtect::FireballProtector)) {
                ModStatus = ModStatuses.canceled;
                taskFailed("无法拦截火球！自动退出！", nowSeg - 1);
                return;
            }
            if (MinecraftContext.player().getHealth() < h) {
                ModStatus = ModStatuses.canceled;
                taskFailed("补给过程受伤！紧急！", nowSeg - 1);
                return;
            }
            TaskManager.delay(1);
        }
    }

    private static void ElytraTaskProtector(int nowSeg) {
        while (true) {
            if (MinecraftContext.player().getHealth() < 3.5) {
                int count = 0;
                for (int i = 0; i < 45; i++) {
                    if (MinecraftContext.player().getInventory().getStack(i).getItem() == Items.TOTEM_OF_UNDYING)
                        count += MinecraftContext.player().getInventory().getStack(i).getCount();
                }
                if (count <= 1) {
                    ModStatus = ModStatuses.canceled;
                    taskFailed("AutoLog图腾数量过少且血量过低！", nowSeg);
                    return;
                }
            }
            TaskManager.delay(1);
        }
    }

    /**
     * 任务失败处理函数
     *
     * @param str 失败原因
     * @param seg 当前段数
     */
    public static void taskFailed(@NotNull String str, int seg) {
        MinecraftContext.client().options.forwardKey.setPressed(false);
        MinecraftContext.client().options.jumpKey.setPressed(false);
        MinecraftContext.client().options.useKey.setPressed(false);
        if (seg == -1 && isAutoLogOnSeg1 || seg != -1 && isAutoLog) {
            MutableText text = Text.literal("[RustAutoLog] ");
            text.append(Text.literal(str));
            if (MinecraftContext.client().player != null) {
                MinecraftContext.client().player.networkHandler.onDisconnect(new DisconnectS2CPacket(text));
            }
        } else if (MinecraftContext.client().player != null) {
            msg.SendMsg("任务中止。请仔细阅读错误信息:", MsgLevel.warning);
            String[] error = str.split("\n");
            for (String e : error)
                msg.SendMsg(e, MsgLevel.error);
        }
        ModStatus = ModStatuses.idle;
        status = TaskStatus.NO_TASK;
    }

    // region 模组任务状态枚举
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

    // endregion

}
