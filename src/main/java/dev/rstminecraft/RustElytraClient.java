package dev.rstminecraft;

//提示：本代码完全由RSTminecraft 编写，部分内容可能不符合编程规范，有意愿者请修改。
//关于有人质疑后门的事，请自行阅读代码，你要是能找出后门，我把电脑吃了。
//本模组永不收费，永远开源，许可证相关事项正在考虑。

//文件解释：本文件为模组主文件。

import dev.rstminecraft.RustClientTemplate.Messenger;
import dev.rstminecraft.RustClientTemplate.ModConfig;
import dev.rstminecraft.RustClientTemplate.ModTaskManager;
import dev.rstminecraft.RustClientTemplate.MsgLevel;
import dev.rstminecraft.utils.BaritoneControlChecker;
import dev.rstminecraft.utils.TrajectoryRenderer;
import net.fabricmc.api.ClientModInitializer;
import net.fabricmc.fabric.api.client.command.v2.ClientCommandManager;
import net.fabricmc.fabric.api.client.command.v2.ClientCommandRegistrationCallback;
import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientTickEvents;
import net.fabricmc.fabric.api.client.keybinding.v1.KeyBindingHelper;
import net.fabricmc.fabric.api.client.networking.v1.ClientPlayConnectionEvents;
import net.fabricmc.fabric.api.client.rendering.v1.hud.HudElementRegistry;
import net.fabricmc.fabric.api.client.rendering.v1.hud.VanillaHudElements;
import net.fabricmc.loader.api.FabricLoader;
import net.minecraft.client.MinecraftClient;
import net.minecraft.client.option.KeyBinding;
import net.minecraft.client.util.InputUtil;
import net.minecraft.item.Item;
import net.minecraft.item.Items;
import net.minecraft.network.packet.c2s.play.ClientCommandC2SPacket;
import net.minecraft.util.Identifier;
import org.jetbrains.annotations.NotNull;
import org.lwjgl.glfw.GLFW;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.Map;
import java.util.Objects;

import static dev.rstminecraft.ModHud.DrawHud;

public class RustElytraClient implements ClientModInitializer {
    public static final Logger MODLOGGER = LoggerFactory.getLogger("rust-elytra-client");
    public static final Item[] FoodList = {Items.GOLDEN_CARROT, Items.GOLDEN_APPLE, Items.ENCHANTED_GOLDEN_APPLE,
            Items.COOKED_BEEF, Items.COOKED_PORKCHOP, Items.COOKED_CHICKEN};
    public static int currentTick = 0;
    public static boolean autoLogEnabled = false;

    // region 飞行mixin控制变量
    public static boolean fixEyeHeight = false;
    public static boolean cameraMixinSwitch = false;
    public static float fixedYaw = 0f, fixedPitch = 0f;
    public static float timerMultiplier = 1f;
    // endregion

    // region 其他mixin信息
    public static boolean isLookMixinSuccess = false;
    public static boolean isPausedMixinSuccess = false;
    public static boolean[] paused;
    // endregion

    // region HUD显示控制
    public static int HudX;
    public static int HudY;
    public static boolean enableHud;
    // endregion

    public static Messenger msg;
    public static KeyBinding openCustomScreenKey;
    public static KeyBinding elytraDebugKey;
    public static ModConfig config;
    static @NotNull ModStatuses ModStatus = ModStatuses.idle;
    FabricLoader loader = FabricLoader.getInstance();

    @Override
    public void onInitializeClient() {
        // 初始化任务管理器
        ModTaskManager.init();

        boolean hasBaritone = loader.isModLoaded("baritone") || loader.isModLoaded("baritone-meteor");
        if (!hasBaritone) {
            MODLOGGER.error(" [Rust Elytra] 需要安装 Baritone（baritone / baritone-meteor 任选其一");
        }
        Map<String, Object> defaultsConfig = Map.ofEntries(Map.entry("FirstUse", true), Map.entry("isAutoLog", true),
                                                           Map.entry("isAutoLogOnSeg1", false),
                                                           Map.entry("DisplayDebug", false),
                                                           Map.entry("inspectArmor", true),
                                                           Map.entry("verboseDisplayDebug", false),
                                                           Map.entry("FoodIndex", 0),
                                                           Map.entry("autoLogEnabled", false), Map.entry("HudX", 0),
                                                           Map.entry("HudY", 0), Map.entry("enableHud", true));

        config = new ModConfig(FabricLoader.getInstance().getConfigDir().resolve("RSTConfig.json"), defaultsConfig);
        msg = new Messenger("Rust Elytra", config.getBoolean("DisplayDebug", false) ? MsgLevel.debug : MsgLevel.info,
                            MODLOGGER);
        autoLogEnabled = config.getBoolean("autoLogEnabled", false);
        HudX = config.getInt("HudX", 0);
        HudY = config.getInt("HudY", 0);
        enableHud = config.getBoolean("enableHud", true);
        // GUI按键注册
        KeyBinding.Category RST_CATEGORY = KeyBinding.Category.create(Identifier.of("rst_auto_elytra", "general"));
        openCustomScreenKey = KeyBindingHelper.registerKeyBinding(
                new KeyBinding("RST Auto Elytra Mod主界面", InputUtil.Type.KEYSYM, GLFW.GLFW_KEY_R, RST_CATEGORY));
        elytraDebugKey = KeyBindingHelper.registerKeyBinding(
                new KeyBinding("RST Auto Elytra Mod无尽鞘翅调试按钮", InputUtil.Type.KEYSYM,
                               InputUtil.UNKNOWN_KEY.getCode(), RST_CATEGORY));
        TrajectoryRenderer.init();

        HudElementRegistry.attachElementBefore(
                VanillaHudElements.CHAT,
                Identifier.of("rust_elytra_client", "hud_layer"),
                (context, tickCounter) -> DrawHud(context)
        );
        // tick末事件注册
        ClientTickEvents.END_CLIENT_TICK.register(client -> {
            currentTick++;
            if (client.player != null && (openCustomScreenKey.isPressed()))
                client.setScreen(new RSTScr(client.currentScreen));

            // 自动重装鞘翅，避免鞘翅耐久损耗（无尽鞘翅模式）
            if (currentTick % 16 == 0 && client.player != null && (elytraDebugKey.isPressed() ||
                                                                   (ModTask.type == ModTask.TaskType.INFINITY_ELYTRA &&
                                                                    client.player.isGliding() &&
                                                                    client.interactionManager != null &&
                                                                    client.getNetworkHandler() != null &&
                                                                    (ModTask.status == ModTask.TaskStatus.LANDING ||
                                                                     ModTask.status == ModTask.TaskStatus.FLYING)))) {
                fixEyeHeight = true;
                ModTaskManager.startThread(() -> {
                    ModTaskManager.delay(3);
                    fixEyeHeight = false;
                });
                client.player.stopGliding();
                Objects.requireNonNull(client.getNetworkHandler()).sendPacket(
                        new ClientCommandC2SPacket(client.player, ClientCommandC2SPacket.Mode.START_FALL_FLYING));
            }
            BaritoneControlChecker.lookFlag = false;
        });

        // 自动开始飞行
        ClientTickEvents.START_CLIENT_TICK.register(client -> {
            if (currentTick % 16 == 1 && client.player != null && (elytraDebugKey.isPressed() ||
                                                                   (ModTask.type == ModTask.TaskType.INFINITY_ELYTRA &&
                                                                    client.interactionManager != null &&
                                                                    client.getNetworkHandler() != null &&
                                                                    (ModTask.status == ModTask.TaskStatus.LANDING ||
                                                                     ModTask.status == ModTask.TaskStatus.FLYING)))) {
                client.player.startGliding();
                Objects.requireNonNull(client.getNetworkHandler()).sendPacket(
                        new ClientCommandC2SPacket(client.player, ClientCommandC2SPacket.Mode.START_FALL_FLYING));
            }
        });
        // 本命令用于进入主菜单GUI(也可以通过上方按键进入)
        ClientCommandRegistrationCallback.EVENT.register((dispatcher, registryAccess) -> dispatcher.register(
                ClientCommandManager.literal("RustElytraMenu").executes(context -> {
                    ModTaskManager.startThread(() -> {
                        ModTaskManager.delay(1);
                        ModTaskManager.runOnMainSync(() -> MinecraftClient.getInstance().setScreen(
                                new RSTScr(MinecraftClient.getInstance().currentScreen)));
                    });
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

}