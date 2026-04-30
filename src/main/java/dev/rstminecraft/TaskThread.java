package dev.rstminecraft;

import baritone.api.BaritoneAPI;
import dev.rstminecraft.utils.MsgLevel;
import dev.rstminecraft.utils.RSTTask;
import net.minecraft.client.MinecraftClient;
import net.minecraft.client.network.ClientPlayerEntity;
import net.minecraft.item.Items;
import net.minecraft.network.packet.s2c.common.DisconnectS2CPacket;
import net.minecraft.text.MutableText;
import net.minecraft.text.Text;
import net.minecraft.util.math.BlockPos;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import java.util.concurrent.CountDownLatch;
import java.util.function.Supplier;

import static dev.rstminecraft.RSTFireballProtect.FireballProtector;
import static dev.rstminecraft.RustElytraClient.*;
import static dev.rstminecraft.utils.RSTConfig.setBoolean;
import static dev.rstminecraft.utils.RSTTask.scheduleTask;

public class TaskThread extends Thread {
    private static @Nullable TaskThread ModThread = null;
    private final int TargetX, TargetZ;
    private final boolean isAutoLog, isAutoLogOnSeg1;
    public final TaskType type;
    private final BlockPos StartPos;
    private final int startTick;

    public static TaskStatus status;


    /**
     * private的构造函数，用于阻止本class外的函数新建Mod线程，即只有零个/一个Mod线程（单例模式）
     *
     * @param type            补给类型
     * @param isAutoLog       是否自动退出
     * @param isAutoLogOnSeg1 是否在第一段自动退出
     * @param TargetX         目标X坐标
     * @param TargetZ         目标Z坐标
     */
    private TaskThread(TaskType type, boolean isAutoLog, boolean isAutoLogOnSeg1, int TargetX, int TargetZ, BlockPos StartPos) {
        this.type = type;
        this.isAutoLog = isAutoLog;
        this.isAutoLogOnSeg1 = isAutoLogOnSeg1;
        this.TargetX = TargetX;
        this.TargetZ = TargetZ;
        this.StartPos = StartPos;
        this.startTick = currentTick;
    }

    /**
     * 获取一个唯一的Mod线程(单例模式)
     *
     * @return 一个唯一的Mod线程
     */
    public static @Nullable TaskThread getModThread() {
        return ModThread;
    }

    /**
     * 返回模组线程是否在运行，即Mod是否在工作
     *
     * @return 是否在运行
     */
    public static boolean isThreadRunning() {
        return ModThread != null;
    }

    public static double TaskRemainDistance(@NotNull ClientPlayerEntity player) {
        if (getModThread() == null) return -1;
        return Math.sqrt(new BlockPos(getModThread().TargetX, 0, getModThread().TargetZ).getSquaredDistance(player.getBlockPos()));
    }

    public static double TaskFlyDistance(@NotNull ClientPlayerEntity player) {
        if (getModThread() == null) return -1;
        return Math.sqrt(getModThread().StartPos.getSquaredDistance(player.getBlockPos()));
    }

    public static double TaskAverageSpeed(@NotNull ClientPlayerEntity player) {
        if (getModThread() == null) return -1;
        return TaskFlyDistance(player) / (currentTick - getModThread().startTick) * 20;
    }

    public static double TaskRemainSecond(@NotNull ClientPlayerEntity player) {
        if (getModThread() == null) return -1;
        return TaskRemainDistance(player) / TaskAverageSpeed(player);
    }

    public static TaskStatus getTaskStatus(){
        if(!isThreadRunning()) return TaskStatus.NO_TASK;
        return status;
    }
    /**
     * 启动一个新鞘翅补给模式的任务
     *
     * @param isAutoLog       是否自动退出
     * @param isAutoLogOnSeg1 是否在第一段自动退出
     * @param TargetX         目标X坐标
     * @param TargetZ         目标Z坐标
     */
    public static void StartModThread(TaskType type, boolean isAutoLog, boolean isAutoLogOnSeg1, int TargetX, int TargetZ, BlockPos startPos) {
        ModThread = new TaskThread(type, isAutoLog, isAutoLogOnSeg1, TargetX, TargetZ, startPos);
        status = TaskStatus.START;
        ModThread.start();
    }


    /**
     * 用于在Mod线程中延迟几个tick
     *
     * @param ticks 需要延迟的tick数
     */
    public static void delay(int ticks) {
        if (!(Thread.currentThread() instanceof TaskThread)) return;
        if (ModStatus == ModStatuses.canceled) {
            RunAsMainThread(() -> BaritoneAPI.getProvider().getPrimaryBaritone().getCommandManager().execute("stop"));
            MinecraftClient.getInstance().options.forwardKey.setPressed(false);
            ModStatus = ModStatuses.idle;
            throw new TaskCanceled();
        }
        for (int i = 0; i < ticks; i++) {
            try {
                synchronized (ThreadLock) {
                    ThreadLock.wait(2147483647);
                }
            } catch (InterruptedException e) {
                e.printStackTrace();
            }
        }
    }


    /**
     * 在主线程(MC Render Thread)运行一个lambda(无参数,不定类型返回值)
     * 会在lambda执行完之前阻塞
     * 用于规避一些API必须在主线程执行
     *
     * @param lambda 一个lambda(无参数,不定类型返回值)
     * @param <T>    一个不定类型
     * @return 运行结果, 类型不定
     */
    public static <T> T RunAsMainThread(@NotNull Supplier<T> lambda) {
        if (Thread.currentThread() != ModThread) return lambda.get();

        CountDownLatch latch = new CountDownLatch(1);
        TaskHolder<T> holder = new TaskHolder<>(lambda, latch);

        if (!currentTask.compareAndSet(null, holder)) {
            throw new TaskException("同时只能存在一个任务");
        }
        try {
            latch.await();
            return holder.getResult();
        } catch (InterruptedException e) {
            throw new TaskException("任务执行异常");
        }
    }

    public static <T> T RunAsMainThread2(@NotNull Supplier<T> lambda) {
        if (Thread.currentThread() != ModThread) return lambda.get();

        CountDownLatch latch = new CountDownLatch(1);
        TaskHolder<T> holder = new TaskHolder<>(lambda, latch);

        if (!currentTask.compareAndSet(null, holder)) {
            throw new TaskException("同时只能存在一个任务");
        }
        try {
            latch.await();
            return holder.getResult();
        } catch (InterruptedException e) {
            throw new TaskException("任务执行异常");
        }
    }

    /**
     * 用于在主线程执行一个无参数无返回值的lambda(Runnable)
     *
     * @param lambda 一个无参数无返回值的lambda(Runnable)
     */
    public static void RunAsMainThread(@NotNull Runnable lambda) {
        RunAsMainThread(() -> {
            lambda.run();
            return null;
        });
    }

    /**
     * 任务失败处理函数
     *
     * @param client 客户端对象
     * @param str    失败原因
     * @param seg    当前段数
     */
    public void taskFailed(@NotNull MinecraftClient client, @NotNull String str, int seg) {
        MinecraftClient.getInstance().options.forwardKey.setPressed(false);
        MinecraftClient.getInstance().options.jumpKey.setPressed(false);
        MinecraftClient.getInstance().options.useKey.setPressed(false);

        if (BaritoneAPI.getProvider().getPrimaryBaritone().getElytraProcess().isActive() || BaritoneAPI.getProvider().getPrimaryBaritone().getMineProcess().isActive())
            RunAsMainThread(() -> BaritoneAPI.getProvider().getPrimaryBaritone().getCommandManager().execute("stop"));
        if (seg == -1 && isAutoLogOnSeg1 || seg != -1 && isAutoLog) {
            MutableText text = Text.literal("[RSTAutoLog] ");
            text.append(Text.literal(str));
            if (client.player != null) {
                client.player.networkHandler.onDisconnect(new DisconnectS2CPacket(text));
            }
        } else if (client.player != null) {
            MsgSender.SendMsg(client.player, "任务结束。" + str, MsgLevel.fatal);
        }
        ModStatus = ModStatuses.idle;
    }

    /**
     * ModThread运行函数，RealRun运行完后将ModThread设置为null，残余线程被gc清理
     */
    @Override
    public void run() {
        RealRun();
        timerMultiplier = 1;
        cameraMixinSwitch = false;
        ModThread = null;
    }

    /**
     * 主逻辑。依次调用SupplyTask、ElytraTask
     */
    private void RealRun() {

        MinecraftClient client = MinecraftClient.getInstance();
        if (client == null || client.player == null) return;
        ModStatus = ModStatuses.running;
        for (int nowSeg = 0; ; nowSeg++) {
            try {
                MsgSender.SendMsg(client.player, "第" + nowSeg + "段补给任务开始！", MsgLevel.info);
                // 先开启补给守护任务（防火球、防伤害）
                float h = client.player.getHealth();
                int finalNowSeg = nowSeg;
                RSTTask FireballTask = scheduleTask((self, args) -> {
                    if (!FireballProtector(client)) {
                        ModStatus = ModStatuses.canceled;
                        self.repeatTimes = 0;
                        MsgSender.SendMsg(client.player, "无法拦截火球", MsgLevel.fatal);
                        taskFailed(client, "补给任务失败！自动退出！", finalNowSeg - 1);
                        return;
                    }
                    if (client.player.getHealth() < h) {
                        self.repeatTimes = 0;
                        taskFailed(client, "补给过程受伤！紧急！", finalNowSeg - 1);
                        ModStatus = ModStatuses.canceled;
                    }
                }, 1, -1, 1, 100);


                // 开启补给任务
                try {
                    RustSupplyTask.SupplyTask(client, type);
                    synchronized (FireballTask) {
                        FireballTask.repeatTimes = -2;
                    }
                    delay(1);
                } catch (TaskException e) {
                    // 补给失败
                    synchronized (FireballTask) {
                        FireballTask.repeatTimes = -2;
                    }
                    e.printStackTrace();
                    MsgSender.SendMsg(client.player, e.getMessage(), MsgLevel.error);
                    MsgSender.SendMsg(client.player, "补给任务失败", MsgLevel.fatal);
                    taskFailed(client, "补给任务失败！自动退出！", nowSeg - 1);
                    return;
                } catch (TaskCanceled e) {
                    synchronized (FireballTask) {
                        FireballTask.repeatTimes = -2;
                    }
                    MsgSender.SendMsg(client.player, "任务中止！", MsgLevel.warning);
                    return;
                }

                MsgSender.SendMsg(client.player, "第" + nowSeg + "段飞行任务开始！", MsgLevel.info);


                RSTTask AutoLogTask = scheduleTask((self, args) -> {
                    if (client.player != null && autoLogEnabled && TaskThread.isThreadRunning()) {
                        if (client.player.getHealth() < 3.5) {
                            int count = 0;
                            for (int i = 0; i < 45; i++) {
                                if (client.player.getInventory().getStack(i).getItem() == Items.TOTEM_OF_UNDYING)
                                    count += client.player.getInventory().getStack(i).getCount();
                            }
                            if (count <= 1) {
                                autoLogEnabled = false;
                                setBoolean("autoLogEnabled", false);
                                ModStatus = ModStatuses.canceled;
                                self.repeatTimes = 0;
                                taskFailed(client, "AutoLog图腾数量过少且血量过低！", finalNowSeg);
                            }
                        }
                    }
                }, 1, -1, 1, 100);
                // 开启鞘翅任务
                try {
                    if (RustElytraTask.ElytraTask(client, this.TargetX, this.TargetZ, type)) {
                        MsgSender.SendMsg(client.player, "到达目的地！圆满完成！！！", MsgLevel.warning);
                        if (isAutoLog) {
                            MutableText text = Text.literal("[RSTAutoLog] ");
                            text.append(Text.literal("已经到达目的地"));
                            if (client.player != null) {
                                client.player.networkHandler.onDisconnect(new DisconnectS2CPacket(text));
                            }
                        }
                        ModStatus = ModStatuses.idle;
                        synchronized (AutoLogTask) {
                            AutoLogTask.repeatTimes = -2;
                        }
                        return;
                    }
                    synchronized (AutoLogTask) {
                        AutoLogTask.repeatTimes = -2;
                    }
                    delay(1);
                } catch (TaskException e) {
                    // 飞行失败
                    MsgSender.SendMsg(client.player, e.getMessage(), MsgLevel.error);
                    e.printStackTrace();
                    taskFailed(client, e.getMessage(), nowSeg);
                    synchronized (AutoLogTask) {
                        AutoLogTask.repeatTimes = -2;
                    }
                    return;
                } catch (TaskCanceled e) {
                    MsgSender.SendMsg(client.player, "任务中止！", MsgLevel.warning);
                    synchronized (AutoLogTask) {
                        AutoLogTask.repeatTimes = -2;
                    }
                    return;
                }
            } catch (NullPointerException e) {
                e.printStackTrace();
                taskFailed(client, e.getMessage(), nowSeg);
                return;
            }
        }
    }

    public enum TaskType {
        EXP_BOTTLE, ELYTRA, INFINITY_ELYTRA
    }

    public enum TaskStatus{
        NO_TASK,START, SUPPLY, FLYING,LANDING,REPAIR_ELYTRA
    }

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
}
