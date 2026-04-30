package dev.rstminecraft;

//提示：本代码完全由RSTminecraft 编写，部分内容可能不符合编程规范，有意愿者请修改。
//关于有人质疑后门的事，请自行阅读代码，你要是能找出后门，我把电脑吃了。
//本模组永不收费，永远开源，许可证相关事项正在考虑。

//文件解释：本文件为模组主文件。

import dev.rstminecraft.utils.BaritoneControlChecker;
import dev.rstminecraft.utils.MsgLevel;
import dev.rstminecraft.utils.RSTMsgSender;
import dev.rstminecraft.utils.TrajectoryRenderer;
import net.fabricmc.api.ClientModInitializer;
import net.fabricmc.fabric.api.client.command.v2.ClientCommandManager;
import net.fabricmc.fabric.api.client.command.v2.ClientCommandRegistrationCallback;
import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientTickEvents;
import net.fabricmc.fabric.api.client.keybinding.v1.KeyBindingHelper;
import net.fabricmc.fabric.api.client.networking.v1.ClientPlayConnectionEvents;
import net.fabricmc.fabric.api.client.rendering.v1.HudRenderCallback;
import net.fabricmc.loader.api.FabricLoader;
import net.minecraft.client.MinecraftClient;
import net.minecraft.client.gui.DrawContext;
import net.minecraft.client.option.KeyBinding;
import net.minecraft.client.render.RenderTickCounter;
import net.minecraft.client.util.InputUtil;
import net.minecraft.item.Item;
import net.minecraft.item.Items;
import net.minecraft.network.packet.c2s.play.ClientCommandC2SPacket;
import net.minecraft.screen.slot.SlotActionType;
import org.jetbrains.annotations.NotNull;
import org.lwjgl.glfw.GLFW;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.concurrent.CountDownLatch;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Supplier;

import static dev.rstminecraft.utils.RSTConfig.*;
import static dev.rstminecraft.utils.RSTTask.scheduleTask;
import static dev.rstminecraft.utils.RSTTask.tick;

public class RustElytraClient implements ClientModInitializer {
    public static final Logger MODLOGGER = LoggerFactory.getLogger("rust-elytra-client");
    public static final AtomicReference<TaskHolder<?>> currentTask = new AtomicReference<>();
    public static final Item[] FoodList = {Items.GOLDEN_CARROT, Items.GOLDEN_APPLE, Items.ENCHANTED_GOLDEN_APPLE, Items.COOKED_BEEF, Items.COOKED_PORKCHOP, Items.COOKED_CHICKEN};
    static final Object ThreadLock = new Object();
    public static int currentTick = 0;
    public static boolean autoLogEnabled = false;
    public static boolean fixEyeHeight = false;
    // mixin相关变量
    public static boolean cameraMixinSwitch = false;
    public static float fixedYaw = 0f, fixedPitch = 0f;
    public static boolean isLookMixinSuccess = false;
    public static boolean isPausedMixinSuccess = false;
    public static boolean[] paused;

    // timer mixin相关
    public static float timerMultiplier = 1f;

    // HUD显示坐标
    public static int HudX;
    public static int HudY;
    public static boolean enableHud;

    public static RSTMsgSender MsgSender;
    public static KeyBinding openCustomScreenKey;
    public static KeyBinding elytraDebugKey;

    static @NotNull ModStatuses ModStatus = ModStatuses.idle;
    FabricLoader loader = FabricLoader.getInstance();

    @Override
    public void onInitializeClient() {
        boolean hasBaritone = loader.isModLoaded("baritone") || loader.isModLoaded("baritone-meteor");
        if (!hasBaritone) {
            MODLOGGER.error(" [MyMod] 需要安装 Baritone（baritone / baritone-meteor 任选其一");
        }
        loadConfig(FabricLoader.getInstance().getConfigDir().resolve("RSTConfig.json"));
        MsgSender = new RSTMsgSender(getBoolean("DisplayDebug", false) ? MsgLevel.debug : MsgLevel.info);
        autoLogEnabled = getBoolean("autoLogEnabled", false);
        HudX = getInt("HudX", 0);
        HudY = getInt("HudY", 0);
        enableHud = getBoolean("enableHud", true);
        // GUI按键注册
        openCustomScreenKey = KeyBindingHelper.registerKeyBinding(new KeyBinding("RST Auto Elytra Mod主界面", InputUtil.Type.KEYSYM, GLFW.GLFW_KEY_R, "RST Auto Elytra Mod"));
        elytraDebugKey = KeyBindingHelper.registerKeyBinding(new KeyBinding("RST Auto Elytra Mod无尽鞘翅调试按钮", InputUtil.Type.KEYSYM, InputUtil.UNKNOWN_KEY.getCode(), "RST Auto Elytra Mod"));
        TrajectoryRenderer.init();

        HudRenderCallback.EVENT.register((DrawContext context, RenderTickCounter tickCounter) -> {
            MinecraftClient client = MinecraftClient.getInstance();
            if (!enableHud || TaskThread.getModThread() == null || client.player == null) return;
            StringBuilder sb = new StringBuilder();
            sb.append("当前状态:");
            switch (TaskThread.getModThread().type) {
                case ELYTRA -> sb.append("鞘翅模式");
                case EXP_BOTTLE -> sb.append("经验模式");
                case INFINITY_ELYTRA -> sb.append("无尽鞘翅模式");
            }
            sb.append(",");
            switch (TaskThread.getTaskStatus()) {
                case START -> sb.append("任务正在启动");
                case SUPPLY -> sb.append("正在获取补给");
                case FLYING -> sb.append("正在飞行");
                case REPAIR_ELYTRA -> sb.append("正在修补鞘翅");
                case LANDING -> sb.append("正在降落");
            }
            sb.append('\n');
            sb.append("已飞行距离:").append(String.format("%.2f", TaskThread.TaskFlyDistance(client.player))).append('\n');
            sb.append("剩余飞行距离:").append(String.format("%.2f", TaskThread.TaskRemainDistance(client.player))).append('\n');
            sb.append("平均飞行速度:").append(String.format("%.2f", TaskThread.TaskAverageSpeed(client.player))).append(" m/s\n");
            int second = (int) TaskThread.TaskRemainSecond(client.player); //这是随便输入的秒值
            int hour = second / 3600; // 得到分钟数
            second = second % 3600;//剩余的秒数
            int minute = second / 60;//得到分
            second = second % 60;//剩余的秒
            sb.append(String.format("预计剩余时间:%02d:%02d:%02d", hour, minute, second));
            String[] strs = sb.toString().split("\n");
            for (int i = 0; i < strs.length; i++) {
                context.drawText(MinecraftClient.getInstance().textRenderer, strs[i], HudX, HudY + 10 * i, 0xFFFFFFFF, false);
            }
        });

        // tick末事件注册
        ClientTickEvents.END_CLIENT_TICK.register(client -> {
            currentTick++;
            if (TaskThread.isThreadRunning()) {
                synchronized (ThreadLock) {
                    ThreadLock.notify();
                }
                try {
                    while (TaskThread.getModThread() != null && !(TaskThread.getModThread().getState() == Thread.State.TERMINATED || TaskThread.getModThread().getState() == Thread.State.TIMED_WAITING)) {
                        TaskHolder<?> task = currentTask.get();
                        if (task != null) {
                            task.execute();
                            currentTask.set(null);
                        }
                    }
                } catch (NullPointerException e) {
                    if (!e.getMessage().contains("TaskThread.getState")) throw e;
                }
            }
            tick();
//            MODLOGGER.error(String.valueOf(currentTick));
//
//            if (client.player != null) {
//                MsgSender.SendMsg(client.player, String.valueOf(BaritoneControlChecker.isControlPlayer()),MsgLevel.warning);
//            }
            if (client.player != null && openCustomScreenKey.isPressed())
                client.setScreen(new RSTScr(MinecraftClient.getInstance().currentScreen, getBoolean("FirstUse", true)));

            // 自动重装鞘翅，避免鞘翅耐久损耗（无尽鞘翅模式）
            if (currentTick % 16 == 0 && client.player != null && (elytraDebugKey.isPressed() || (TaskThread.getModThread() != null && TaskThread.getModThread().type == TaskThread.TaskType.INFINITY_ELYTRA && client.player.isFallFlying() && client.interactionManager != null && client.getNetworkHandler() != null))) {
                fixEyeHeight = true;
                scheduleTask((s, a) -> fixEyeHeight = false, 0, 0, 3, 100000);
                client.player.stopFallFlying();
                for(int i = 0;i<3;i++) {
                    client.interactionManager.clickSlot(client.player.currentScreenHandler.syncId, 6, 0, SlotActionType.PICKUP, client.player);
                    client.interactionManager.clickSlot(client.player.currentScreenHandler.syncId, 6, 0, SlotActionType.PICKUP, client.player);
                }
                client.getNetworkHandler().sendPacket(new ClientCommandC2SPacket(client.player, ClientCommandC2SPacket.Mode.START_FALL_FLYING));
            }
            BaritoneControlChecker.lookFlag = false;
        });

        // 自动开始飞行
        ClientTickEvents.START_CLIENT_TICK.register(client -> {
            if (currentTick % 16 == 1 && client.player != null && (elytraDebugKey.isPressed() || (TaskThread.getModThread() != null && TaskThread.getModThread().type == TaskThread.TaskType.INFINITY_ELYTRA && client.interactionManager != null && client.getNetworkHandler() != null))) {
                client.getNetworkHandler().sendPacket(new ClientCommandC2SPacket(client.player, ClientCommandC2SPacket.Mode.START_FALL_FLYING));
                client.player.startFallFlying();
            }
        });
        // 本命令用于进入主菜单GUI(也可以通过上方按键进入)
        ClientCommandRegistrationCallback.EVENT.register((dispatcher, registryAccess) -> dispatcher.register(ClientCommandManager.literal("RSTAutoElytraMenu").executes(context -> {
            scheduleTask((s, a) -> MinecraftClient.getInstance().setScreen(new RSTScr(MinecraftClient.getInstance().currentScreen, getBoolean("FirstUse", true))), 1, 0, 2, 100000);
            return 1;
        })));
        ClientCommandRegistrationCallback.EVENT.register((dispatcher, registryAccess) -> dispatcher.register(ClientCommandManager.literal("RSTDebug-IDLE").executes(context -> {
            ModStatus = ModStatuses.idle;
            TrajectoryRenderer.path.clear();
            return 1;
        })));

        ClientPlayConnectionEvents.DISCONNECT.register((handler, client) -> {
            // 确保 client.world 为 null 时不崩溃
            if (ModStatus != ModStatuses.idle) {
                ModStatus = ModStatuses.canceled;
            }
        });
    }

    enum ModStatuses {
        idle, running, canceled
    }

    public static class TaskHolder<T> {
        private final Supplier<T> lambda;
        private final CountDownLatch latch;
        private T result;
        private Throwable error;

        TaskHolder(Supplier<T> lambda, CountDownLatch latch) {
            this.lambda = lambda;
            this.latch = latch;
        }

        void execute() {
            try {
                this.result = lambda.get();
            } catch (Throwable t) {
                this.error = t;
            } finally {
                latch.countDown();
            }
        }

        T getResult() {
            if (error != null) throw new TaskThread.TaskException(error.getMessage());
            return result;
        }
    }
}