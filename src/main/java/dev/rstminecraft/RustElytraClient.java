package dev.rstminecraft;

//提示：本代码完全由RSTminecraft 编写，部分内容可能不符合编程规范，有意愿者请修改。

//文件解释：本文件为模组主文件。

import com.mojang.brigadier.arguments.IntegerArgumentType;
import dev.rstminecraft.RustClientCore.ModConfig;
import dev.rstminecraft.RustClientCore.ModConfig.BoolConfigEntry;
import dev.rstminecraft.RustClientCore.ModConfig.IntConfigEntry;
import dev.rstminecraft.RustClientCore.ModConfig.LongConfigEntry;
import dev.rstminecraft.RustClientCore.messenger.Messenger;
import dev.rstminecraft.RustClientCore.messenger.MsgLevel;
import dev.rstminecraft.RustClientCore.task.TaskManager;
import dev.rstminecraft.RustClientCore.task.TickPhase;
import dev.rstminecraft.elytra.ElytraTask;
import dev.rstminecraft.utils.SilentRotation;
import net.fabricmc.api.ClientModInitializer;
import net.fabricmc.fabric.api.client.command.v2.ClientCommandManager;
import net.fabricmc.fabric.api.client.command.v2.ClientCommandRegistrationCallback;
import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientTickEvents;
import net.fabricmc.fabric.api.client.keybinding.v1.KeyBindingHelper;
import net.fabricmc.fabric.api.client.networking.v1.ClientPlayConnectionEvents;
import net.fabricmc.fabric.api.client.rendering.v1.hud.HudElementRegistry;
import net.fabricmc.fabric.api.client.rendering.v1.hud.VanillaHudElements;
import net.fabricmc.loader.api.FabricLoader;
import net.minecraft.client.option.KeyBinding;
import net.minecraft.client.util.InputUtil;
import net.minecraft.item.Item;
import net.minecraft.item.Items;
import net.minecraft.util.Identifier;
import net.minecraft.util.math.BlockPos;
import org.jetbrains.annotations.NotNull;
import org.lwjgl.glfw.GLFW;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import static dev.rstminecraft.ModHud.DrawHud;
import static net.fabricmc.fabric.api.client.command.v2.ClientCommandManager.literal;

public class RustElytraClient implements ClientModInitializer {
    public static final Logger MODLOGGER = LoggerFactory.getLogger("rust-elytra-client");
    public static final Item[] FoodList = {Items.GOLDEN_CARROT, Items.GOLDEN_APPLE, Items.ENCHANTED_GOLDEN_APPLE, Items.COOKED_BEEF, Items.COOKED_PORKCHOP, Items.COOKED_CHICKEN};
    public volatile static int currentTick = 0;

    // region 飞行mixin控制变量
    public static boolean fixEyeHeight = false;
    public static boolean cameraMixinSwitch = false;
    public static float fixedYaw = 0f, fixedPitch = 0f;
    public static float timerMultiplier = 1f;
    // endregion


    public static KeyBinding openCustomScreenKey;
    public static KeyBinding elytraDebugKey;

    public static volatile @NotNull ModStatuses ModStatus = ModStatuses.idle;

    // region 配置文件与配置项
    public static @NotNull ModConfig config = new ModConfig(FabricLoader.getInstance().getConfigDir().resolve("RSTConfig" + ".json"));
    public static @NotNull IntConfigEntry FoodIndex = config.new IntConfigEntry("FoodIndex", 0);
    public static @NotNull IntConfigEntry HudX = config.new IntConfigEntry("HudX", 0);
    public static @NotNull IntConfigEntry HudY = config.new IntConfigEntry("HudY", 0);
    public static @NotNull BoolConfigEntry FirstUse = config.new BoolConfigEntry("FirstUse", true);
    public static @NotNull BoolConfigEntry isAutoLog = config.new BoolConfigEntry("isAutoLog", true);
    public static @NotNull BoolConfigEntry isAutoLogOnSeg1 = config.new BoolConfigEntry("isAutoLogOnSeg1", false);
    public static @NotNull BoolConfigEntry DisplayDebug = config.new BoolConfigEntry("DisplayDebug", false);
    public static @NotNull BoolConfigEntry inspectArmor = config.new BoolConfigEntry("inspectArmor", true);
    public static @NotNull BoolConfigEntry verboseDisplayDebug = config.new BoolConfigEntry("verboseDisplayDebug", false);
    public static @NotNull BoolConfigEntry enableHud = config.new BoolConfigEntry("enableHud", true);
    public static @NotNull BoolConfigEntry elytraPredictTerrain = config.new BoolConfigEntry("elytraPredictTerrain", true);
    public static @NotNull LongConfigEntry netherSeed = config.new LongConfigEntry("netherSeed", -7346913998703726680L /* 3c3u种子作为默认 */);
    public static @NotNull IntConfigEntry elytraFireworkSpeed = config.new IntConfigEntry("elytraFireworkSpeed", 1);
    // endregion

    public static ElytraTask et;
    // 信息发送器
    public static Messenger msg;

    public static volatile boolean paused = false;

    @Override
    public void onInitializeClient() {
        msg = new Messenger("Rust Elytra", DisplayDebug.get() ? MsgLevel.debug : MsgLevel.info, MODLOGGER);
        // GUI按键注册

        KeyBinding.Category RST_CATEGORY = KeyBinding.Category.create(Identifier.of("rst_auto_elytra", "general"));

        openCustomScreenKey = KeyBindingHelper.registerKeyBinding(
                new KeyBinding("RST Auto Elytra Mod主界面", InputUtil.Type.KEYSYM, GLFW.GLFW_KEY_R, RST_CATEGORY));
        elytraDebugKey = KeyBindingHelper.registerKeyBinding(
                new KeyBinding("RST Auto Elytra Mod无尽鞘翅调试按钮", InputUtil.Type.KEYSYM, InputUtil.UNKNOWN_KEY.getCode(), RST_CATEGORY));

        HudElementRegistry.attachElementBefore(VanillaHudElements.CHAT, Identifier.of("rust_elytra_client", "hud_layer"),
                (context, tickCounter) -> DrawHud(context));

        // tick末事件注册
        ClientTickEvents.END_CLIENT_TICK.register(client -> {
            currentTick++;
            if (client.player != null && openCustomScreenKey.isPressed())
                client.setScreen(new RSTScr(client.currentScreen));
        });


        ClientPlayConnectionEvents.DISCONNECT.register((handler, client) -> {
            if (ModStatus != ModStatuses.idle) {
                ModStatus = ModStatuses.canceled;
            }
        });

        ClientCommandRegistrationCallback.EVENT.register((dispatcher, registryAccess) -> dispatcher.register(literal("rtest")
                .then(ClientCommandManager.argument("x", IntegerArgumentType.integer())
                        .then(ClientCommandManager.argument("z", IntegerArgumentType.integer())
                                .executes(context -> {
                                    int targetX = IntegerArgumentType.getInteger(context, "x");
                                    int targetZ = IntegerArgumentType.getInteger(context, "z");
                                    et = new ElytraTask(new BlockPos(targetX, 64, targetZ));
                                    TaskManager.build(et::run).setPhase(TickPhase.PRE).setName("elytra").start();
                                    return 1;
                                })))));


        ClientCommandRegistrationCallback.EVENT.register((dispatcher, registryAccess) -> dispatcher.register(literal("pause").executes(context -> {
            paused = true;
            return 1;
        })));

        ClientCommandRegistrationCallback.EVENT.register((dispatcher, registryAccess) -> dispatcher.register(literal("resume").executes(context -> {
            paused = false;
            return 1;
        })));

        SilentRotation.init();
    }

    public enum ModStatuses {
        idle, running, canceled
    }

}