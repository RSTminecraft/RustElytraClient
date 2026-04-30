package dev.rstminecraft;

import baritone.api.BaritoneAPI;
import baritone.api.utils.Helper;
import dev.rstminecraft.utils.*;
import net.minecraft.client.MinecraftClient;
import net.minecraft.client.gui.hud.MessageIndicator;
import net.minecraft.client.gui.screen.ingame.HandledScreen;
import net.minecraft.client.gui.screen.ingame.InventoryScreen;
import net.minecraft.client.network.ClientPlayerEntity;
import net.minecraft.entity.player.PlayerInventory;
import net.minecraft.item.Item;
import net.minecraft.item.ItemStack;
import net.minecraft.item.Items;
import net.minecraft.network.packet.c2s.play.UpdateSelectedSlotC2SPacket;
import net.minecraft.registry.RegistryKey;
import net.minecraft.screen.ScreenHandler;
import net.minecraft.screen.slot.Slot;
import net.minecraft.screen.slot.SlotActionType;
import net.minecraft.util.Hand;
import net.minecraft.util.Identifier;
import net.minecraft.util.math.BlockPos;
import net.minecraft.util.math.ChunkPos;
import net.minecraft.util.math.MathHelper;
import net.minecraft.util.math.Vec3d;
import net.minecraft.world.World;
import net.minecraft.world.chunk.ChunkStatus;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import java.util.*;

import static com.mojang.text2speech.Narrator.LOGGER;
import static dev.rstminecraft.RSTFireballProtect.FireballProtector;
import static dev.rstminecraft.RustElytraClient.*;
import static dev.rstminecraft.RustSupplyTask.extinguishFire;
import static dev.rstminecraft.TaskThread.*;
import static dev.rstminecraft.TaskThread.TaskType.*;
import static dev.rstminecraft.utils.FindPathToOpen.getTakeoffDirection;
import static dev.rstminecraft.utils.FlightPredictor.predictPath;
import static dev.rstminecraft.utils.RSTConfig.getBoolean;
import static dev.rstminecraft.utils.RSTConfig.getInt;
import static dev.rstminecraft.utils.RSTTask.scheduleTask;


public class RustElytraTask {
    private final static TimelinessCounter jumpingCounter = new TimelinessCounter(100);
    private final static TimelinessCounter baritoneControlCounter = new TimelinessCounter(20);
    private final static TimelinessCounter FallingCounter = new TimelinessCounter(5);
    private final static TimelinessCounter SegFailedCounter = new TimelinessCounter(6);
    /**
     * 以下变量为鞘翅飞行的状态
     */
    private static boolean arrived = false;
    private static @Nullable BlockPos LastPos;
    private static boolean noFirework = false;
    private static boolean noElytra = false;
    private static @Nullable BlockPos oldPos = null;
    private static int spinTimes = 0;
    private static boolean waitReset = false;
    private static int inFireTick = 0;
    private static int LastWaitTick = 0;
    private static Item Food = FoodList[0];

    /**
     * 重置状态
     */
    private static void resetStatus() {
        arrived = false;
        noElytra = false;
        jumpingCounter.clear();
        baritoneControlCounter.clear();
        FallingCounter.clear();
        SegFailedCounter.clear();
        LastWaitTick = currentTick;
        LastPos = null;
        noFirework = false;
        spinTimes = 0;
        waitReset = false;
    }

    /**
     * 到达baritone目的地后处理(拿取补给)
     *
     * @param client 客户端对象
     * @param segPos 当前段开始坐标
     */
    private static void arrivedTarget(@NotNull MinecraftClient client, BlockPos segPos) {
        if (client.player == null) return;
        if (client.player.getBlockPos().isWithinDistance(oldPos, 100)) throw new TaskException("距离异常！");
        arrived = true;
        MsgSender.SendMsg(client.player, "到达目的地！本段飞行距离：" + Math.sqrt(client.player.getBlockPos().getSquaredDistance(segPos)), MsgLevel.info);
        for (int i = 0; i < 60; i++) {
            if (client.player.getVelocity().getX() < 0.01 && client.player.getVelocity().getZ() < 0.01) return;
            delay(1);
        }
        throw new TaskException("开启异常！");
    }

    /**
     * 烟花检测、自动补给与烟花、鞘翅消耗量统计
     *
     * @param client 客户端实体
     * @param segPos 当前段开始地点
     */
    private static void FireworkChecker(@NotNull MinecraftClient client, BlockPos segPos) {
        if (client.player == null || client.interactionManager == null) throw new TaskException("null");
        // 检查玩家烟花数量
        PlayerInventory inv = client.player.getInventory();
        int count = 0;
        int slots = 0;
        for (int i = 0; i < 9; i++) {
            ItemStack s = inv.getStack(i);
            if (s.isEmpty() || s.getItem() == Items.FIREWORK_ROCKET) slots++;
            if (s.getItem() == Items.FIREWORK_ROCKET) count += s.getCount();
        }
        List<Integer> replaceList = new ArrayList<>();
        if (slots <= 1) {
            for (int i = 0; i < 9; i++) {
                ItemStack s = inv.getStack(i);
                if (s.isEmpty() || s.getItem() != Items.ENDER_CHEST && s.getItem() != Items.DIAMOND_PICKAXE && s.getItem() != Items.NETHERITE_PICKAXE && s.getItem() != Items.DIAMOND_SWORD && s.getItem() != Items.NETHERITE_SWORD && s.getItem() != Food && s.getItem() != Items.TOTEM_OF_UNDYING)
                    replaceList.add(i);
            }
            if (replaceList.size() <= slots) throw new TaskException("无槽位放置烟花");
        }
        if (count < 64) {
            // 烟花数量少，从背包里拿一些
            HandledScreen<?> handled = RunAsMainThread(() -> {
                client.setScreen(new InventoryScreen(client.player));
                if (!(client.currentScreen instanceof HandledScreen<?> handled2)) throw new TaskException("窗口异常");
                return handled2;
            });
            int c = 0;
            ScreenHandler handler = handled.getScreenHandler();
            for (int i = 9; i < 36; i++) {
                Slot s = handler.getSlot(i);
                if (s == null) continue;
                ItemStack stack = s.getStack();
                if (stack == null || stack.isEmpty()) continue;
                Item item = stack.getItem();
                if (item == Items.FIREWORK_ROCKET) {
                    if (!replaceList.isEmpty()) {
                        int slot = replaceList.removeFirst();
                        int finalI = i;
                        RunAsMainThread(() -> {
                            client.interactionManager.clickSlot(handler.syncId, finalI, 0, SlotActionType.PICKUP, client.player);
                            client.interactionManager.clickSlot(handler.syncId, slot + 36, 0, SlotActionType.PICKUP, client.player);
                            client.interactionManager.clickSlot(handler.syncId, finalI, 0, SlotActionType.PICKUP, client.player);
                        });
                        c += stack.getCount();
                        continue;
                    }
                    c += stack.getCount();
                    int finalI = i;
                    RunAsMainThread(() -> client.interactionManager.clickSlot(handler.syncId, finalI, 0, SlotActionType.QUICK_MOVE, client.player));
                }
            }
            RunAsMainThread(handled::close);

            boolean hasTotem = client.player.getInventory().getStack(3).getItem() == Items.TOTEM_OF_UNDYING || client.player.getInventory().getStack(4).getItem() != Items.TOTEM_OF_UNDYING;
            boolean hasFood = client.player.getInventory().getStack(5).getItem() == Food && client.player.getInventory().getStack(5).getCount() > 18;

            if ((c <= 128 || !hasFood || !hasTotem) && !noFirework) {
                if (!client.player.getBlockPos().isWithinDistance(segPos, 50000 * timerMultiplier)) {
                    noFirework = true;
                    MsgSender.SendMsg(client.player, "图腾/烟花/食物不足，提前寻找位置降落！", MsgLevel.info);
                } else throw new TaskException("补给不足，飞行路程很少，可能是baritone设置错误？请检查！");
            }
        }
    }

    /**
     * 检查baritone寻路情况
     *
     * @param client 客户端对象
     */
    private static void baritoneChecker(@NotNull MinecraftClient client, int x, int z) {
        if (client.player == null) throw new TaskException("player 为null");
        // baritone寻路失败，等待重置状态或auto log
        if (SegFailedCounter.getCount() > 25) {
            if (SegFailedCounter.getCount() > 30) throw new TaskException("baritone寻路异常");
            else if (waitReset) {
                RunAsMainThread(() -> {
                    BaritoneAPI.getProvider().getPrimaryBaritone().getElytraProcess().resetState();
                    BaritoneAPI.getProvider().getPrimaryBaritone().getElytraProcess().repackChunks();
                });
                MsgSender.SendMsg(client.player, "SegFailed！正在重置baritone!", MsgLevel.warning);
                flyToOpen(client, x, z);
                waitReset = false;
            }
        }

        // 玩家是不是陷入了原地绕圈？尝试重置baritone或auto log
        if (currentTick % 400 == 0) {
            if (LastPos != null && client.player.getBlockPos().isWithinDistance(LastPos, 25)) {
                MsgSender.SendMsg(client.player, "SegFailed！原地绕圈!", MsgLevel.warning);
                if (spinTimes > 4) throw new TaskException("baritone寻路异常？！疑似原地转圈");
                else {
                    spinTimes++;
                    if (spinTimes > 1) {
                        RunAsMainThread(() -> BaritoneAPI.getProvider().getPrimaryBaritone().getCommandManager().execute("p"));
                        scheduleTask((ss, aa) -> {
                            BaritoneAPI.getProvider().getPrimaryBaritone().getCommandManager().execute("r");
                            BaritoneAPI.getProvider().getPrimaryBaritone().getElytraProcess().resetState();
                            BaritoneAPI.getProvider().getPrimaryBaritone().getElytraProcess().repackChunks();
                        }, 1, 0, 20, 100000);
                        flyToOpen(client, x, z);
                        for (int i = 0; i < 40; i++) {
                            if (!client.player.getBlockPos().isWithinDistance(LastPos, 30)) break;
                            TaskThread.delay(1);
                        }
                    } else
                        RunAsMainThread(() -> BaritoneAPI.getProvider().getPrimaryBaritone().getElytraProcess().resetState());
                }
            }
            LastPos = client.player.getBlockPos();
        }
    }

    /**
     * 自动进食
     *
     * @param client 客户端对象
     */
    private static void AutoEating(@NotNull MinecraftClient client) {
        if (client.player == null || client.getNetworkHandler() == null) throw new TaskException("null");
        int slot2 = -1;
        // 自动进食，恢复血量
        if (!client.player.isInLava() && client.player.getVelocity().length() > 1.3 && (client.player.getHungerManager().getFoodLevel() < 16 || client.player.getHealth() < 15 && client.player.getHungerManager().getFoodLevel() < 20)) {
            MsgSender.SendMsg(client.player, "准备食用", MsgLevel.tip);
            for (int i = 0; i < 8; i++) {
                ItemStack s = client.player.getInventory().getStack(i);
                Item item = s.getItem();
                if (item == Food) {
                    slot2 = i;
                    break;
                }
            }
            if (slot2 == -1) throw new TaskException("没有足够的食物了！");
            int finalSlot = slot2;
            RunAsMainThread(() -> {
                client.player.getInventory().selectedSlot = finalSlot;
                client.getNetworkHandler().sendPacket(new UpdateSelectedSlotC2SPacket(finalSlot));
                client.options.useKey.setPressed(true);
            });

            for (int i = 0; i < 35; i++) {
                if (client.player == null) {
                    client.options.useKey.setPressed(false);
                    return;
                }
                if (client.player.getVelocity().length() < 0.7 || client.player.isInLava() || client.player.isOnGround()) {
                    // 速度过低，放弃吃食物，防止影响baritone寻路
                    MsgSender.SendMsg(client.player, "放弃吃食物！！！", MsgLevel.tip);
                    client.options.useKey.setPressed(false);
                    if (client.interactionManager != null)
                        RunAsMainThread(() -> client.interactionManager.stopUsingItem(client.player));
                    return;
                }
                delay(1);
            }
            client.options.useKey.setPressed(false);

        }
    }

    /**
     * 自动检测并逃离烟花
     *
     * @param client 客户端对象
     */
    private static void AutoEscapeLava(@NotNull MinecraftClient client) {
        if (client.player == null || client.getNetworkHandler() == null) throw new TaskException("null");
        // 在岩浆中吗？
        if (inFireTick >= 0) {
            if (client.player.isInLava()) {
                inFireTick++;
            } else {
                inFireTick = 0;
            }
        } else {
            inFireTick++;
        }

        if (inFireTick == -1) {
            inFireTick = 0;
            if (client.player == null) return;
            if (client.player.isInLava()) throw new TaskException("逃离岩浆失败！");
        }


        if (inFireTick > 20 || inFireTick > 5 && !client.player.isFallFlying()) {
            // 位于岩浆中？自动逃离岩浆
            inFireTick = -45;
            // 打开鞘翅
            if (client.player.isOnGround()) {
                client.options.jumpKey.setPressed(true);
                delay(4);
                client.options.jumpKey.setPressed(false);
                delay(1);
            }
            client.options.jumpKey.setPressed(true);
            delay(3);
            client.options.jumpKey.setPressed(false);
            if (client.player != null && client.interactionManager != null) {
                MsgSender.SendMsg(client.player, "位于岩浆中，已鞘翅打开", MsgLevel.tip);
                // 抬头
                client.player.setPitch(-90);
                PlayerInventory inv = client.player.getInventory();

                // 找烟花
                int slots = -1;
                for (int i = 0; i < 8; i++) {
                    ItemStack s = inv.getStack(i);
                    if (s.isEmpty() || s.getItem() == Items.FIREWORK_ROCKET) slots = i;
                }
                if (slots == -1) throw new TaskException("找不到烟花");
                else {
                    // 切换到烟花所在格子
                    int finalSlots = slots;
                    RunAsMainThread(() -> {
                        client.player.getInventory().selectedSlot = finalSlots;
                        client.getNetworkHandler().sendPacket(new UpdateSelectedSlotC2SPacket(finalSlots));
                        client.interactionManager.interactItem(client.player, Hand.MAIN_HAND);
                    });

                    // 使用烟花
                    MsgSender.SendMsg(client.player, "已使用烟花！", MsgLevel.tip);

                }

            }
        }
    }


    /**
     * 用于检查玩家头顶有无方块阻挡玩家起跳
     *
     * @param checkY 上方检查格数
     * @return 阻挡方块列表
     */
    private static @NotNull List<BlockPos> getPotentialJumpBlockingBlocks(int checkY) {
        List<BlockPos> nonAirBlocks = new ArrayList<>();

        // 获取客户端和玩家实例
        MinecraftClient client = MinecraftClient.getInstance();
        if (client.world == null || client.player == null) {
            return nonAirBlocks;
        }

        World world = client.world;
        ClientPlayerEntity player = client.player;

        // 获取玩家头部位置
        Vec3d playerPos = player.getPos();
        double headX = playerPos.x;
        double headZ = playerPos.z;
        double headY = playerPos.y + player.getStandingEyeHeight();

        // 计算头顶上方一个方块层的Y坐标
        int aboveY = (int) Math.floor(headY) + 1;

        // 获取头部所在方块的整数坐标
        int baseX = (int) Math.floor(headX);
        int baseZ = (int) Math.floor(headZ);

        // 计算头部在方块内的相对偏移量
        double offsetX = headX - baseX;
        double offsetZ = headZ - baseZ;

        // 要检查的方块位置集合
        Set<BlockPos> blocksToCheck = new HashSet<>();
        blocksToCheck.add(new BlockPos(baseX, aboveY, baseZ));
        // 根据X方向偏移决定是否检查相邻方块
        if (offsetX > 0.7) {  // 靠近东侧边缘
            blocksToCheck.add(new BlockPos(baseX + 1, aboveY, baseZ));
        } else if (offsetX < 0.3) {  // 靠近西侧边缘
            blocksToCheck.add(new BlockPos(baseX - 1, aboveY, baseZ));
        }

        // 根据Z方向偏移决定是否检查相邻方块
        if (offsetZ > 0.7) {  // 靠近南侧边缘
            blocksToCheck.add(new BlockPos(baseX, aboveY, baseZ + 1));
        } else if (offsetZ < 0.3) {  // 靠近北侧边缘
            blocksToCheck.add(new BlockPos(baseX, aboveY, baseZ - 1));
        }

        // 检查对角线方向（当同时靠近两个方向的边缘时）
        if (offsetX > 0.7 && offsetZ > 0.7) {  // 东南角
            blocksToCheck.add(new BlockPos(baseX + 1, aboveY, baseZ + 1));
        } else if (offsetX > 0.7 && offsetZ < 0.3) {  // 东北角
            blocksToCheck.add(new BlockPos(baseX + 1, aboveY, baseZ - 1));
        } else if (offsetX < 0.3 && offsetZ > 0.7) {  // 西南角
            blocksToCheck.add(new BlockPos(baseX - 1, aboveY, baseZ + 1));
        } else if (offsetX < 0.3 && offsetZ < 0.3) {  // 西北角
            blocksToCheck.add(new BlockPos(baseX - 1, aboveY, baseZ - 1));
        }

        // 检查每个方块，只返回不是空气的方块
        for (BlockPos pos : blocksToCheck) {
            for (int i = 0; i < checkY; i++) {
                if (world.isInBuildLimit(pos.add(0, i, 0)) && !world.isAir(pos.add(0, i, 0))) {
                    nonAirBlocks.add(pos);
                }
            }
            for (int i = checkY; i < 0; i++) {
                if (world.isInBuildLimit(pos.add(0, i, 0)) && !world.isAir(pos.add(0, i, 0))) {
                    nonAirBlocks.add(pos);
                }
            }
        }

        return nonAirBlocks;
    }

    private static void elytraTakeoff(@NotNull MinecraftClient client) {
        if (client.player == null) throw new TaskException("null");
        client.player.setPitch(-30);

        client.options.jumpKey.setPressed(true);
        for (int i = 0; i < 8; i++) {
            if (i == 1) client.options.jumpKey.setPressed(false);
            delay(1);
            if (client.player == null) return;
            if (client.player.getVelocity().getY() < -0.1) break;
            delay(1);
        }
        client.options.jumpKey.setPressed(true);
        delay(1);
        client.options.jumpKey.setPressed(false);
    }

    private static @NotNull Vec3d getLookVec(float yaw, float pitch) {
        float f = pitch * ((float) Math.PI / 180F);
        float g = -yaw * ((float) Math.PI / 180F);
        float cosPitch = MathHelper.cos(f);
        float sinPitch = MathHelper.sin(f);
        float cosYaw = MathHelper.cos(g);
        float sinYaw = MathHelper.sin(g);
        return new Vec3d(sinYaw * cosPitch, -sinPitch, cosYaw * cosPitch);
    }

    private static void flyToOpen(@NotNull MinecraftClient client, int x, int z) {
        boolean nearGround = false;
        if (client.player == null || client.getNetworkHandler() == null || client.interactionManager == null)
            throw new TaskException("关键对象不能为null");
        if (!client.player.isFallFlying()) {
            elytraTakeoff(client);
            nearGround = true;
        }
        TimelinessCounter inLavaTicks = new TimelinessCounter(20);
        TakeTimeLoop:
        for (int TakeTime = 0; TakeTime < 6; TakeTime++) {
            TrajectoryRenderer.clear();
            MsgSender.SendMsg(client.player, "尝试飞往开阔地带:" + TakeTime, MsgLevel.warning);
            FindPathToOpen.TakeoffStruct t = getTakeoffDirection(client, 25, 20, nearGround ? 1.2 : 1.7, 0);
            double yh = 0;
            nearGround = false;
            if (t == null) {
                for (yh = 3; yh < 11; yh += 0.5) {
                    t = getTakeoffDirection(client, 25, 20, 1.7, yh);
                    MsgSender.SendMsg(client.player, "尝试" + yh, MsgLevel.warning);
                    if (t != null) break;
                }
                if (t == null) {
                    client.options.jumpKey.setPressed(false);
                    throw new TaskException("没有合适的路线飞往开阔地带！");
                }
            }
            int i2 = -1;
            for (int i = 0; i < 9; i++) {
                if (client.player.getInventory().getStack(i).getItem() == Items.FIREWORK_ROCKET) {
                    i2 = i;
                    break;
                }
            }
            if (i2 == -1) {
                client.options.jumpKey.setPressed(false);
                throw new TaskException("没有足够的烟花飞往开阔地带！");
            }

            client.player.getInventory().selectedSlot = i2;
            client.getNetworkHandler().sendPacket(new UpdateSelectedSlotC2SPacket(i2));

            if (yh != 0) {
                client.player.setPitch(-90);
                client.interactionManager.interactItem(client.player, Hand.MAIN_HAND);
                TrajectoryRenderer.drawTrajectory(predictPath(20, client.player.getPos(), client.player.getVelocity(), getLookVec(0, -90)));
                TrajectoryRenderer.markPos(BlockPos.ofFloored(client.player.getPos().add(0, (int) yh, 0)).add(0, 1, 0));
                Vec3d targetPos = client.player.getPos().add(0, yh, 0);
                for (int i = 0; i < 12; i++) {
                    if (client.player.getPos().getY() > targetPos.getY()) break;
                    else if (i == 11) continue TakeTimeLoop;

                    if (BaritoneControlChecker.isControlPlayer()) {
                        MsgSender.SendMsg(client.player, "已飞至开阔地带，且baritone已重新接管飞行", MsgLevel.warning);
                        TrajectoryRenderer.clear();
                        return;
                    }
                    TaskThread.delay(1);
                }

                cameraMixinSwitch = true;
                int tick = currentTick;
                while (client.player.getPos().getY() > targetPos.getY() + 0.5) {
                    fixedYaw = client.player.getYaw() + (180 * ((currentTick - tick) % 2));
                    fixedPitch = 0;
                    RunAsMainThread(() -> {
                        client.player.setPitch(0);
                        client.player.setYaw(client.player.getYaw() + 180);
                    });
                    if (BaritoneControlChecker.isControlPlayer()) {
                        MsgSender.SendMsg(client.player, "已飞至开阔地带，且baritone已重新接管飞行", MsgLevel.warning);
                        TrajectoryRenderer.clear();
                        cameraMixinSwitch = false;
                        return;
                    }
                    delay(1);
                }
                cameraMixinSwitch = false;

                t = getTakeoffDirection(client, 25, 20, 1.7, 0);
                if (t == null) throw new TaskException("目标角度核实失败！");
            }

            client.player.setYaw(t.yaw);
            client.player.setPitch(t.pitch);
            client.interactionManager.interactItem(client.player, Hand.MAIN_HAND);
            TrajectoryRenderer.drawTrajectory(predictPath(40, client.player.getPos(), client.player.getVelocity(), getLookVec(t.yaw, t.pitch)));
            TrajectoryRenderer.markPos(t.end);

            BlockPos TakePos = t.end;
            TaskThread.delay(1);
            while (!(client.player.getBlockPos().isWithinDistance(TakePos, 1.5) || (Vec3d.of(TakePos).subtract(client.player.getPos()).dotProduct(client.player.getVelocity()) < 0))) {
                if (client.player.isInLava()) inLavaTicks.accumulate();
                if (inLavaTicks.getCount() > 15) throw new TaskException("玩家在飞往开阔地带的路上飞入岩浆");
                if (!client.player.isFallFlying()) {
                    List<BlockPos> bp1 = RunAsMainThread(() -> getPotentialJumpBlockingBlocks(1));
                    if (!bp1.isEmpty()) clearBlockingBlock(client, x, z, bp1);
                    elytraTakeoff(client);
                    nearGround = true;
                    break;
                }
                if (BaritoneControlChecker.isControlPlayer()) {
                    MsgSender.SendMsg(client.player, "已飞至开阔地带，且baritone已重新接管飞行", MsgLevel.warning);
                    TrajectoryRenderer.clear();
                    return;
                }
                TaskThread.delay(1);
            }
            TaskThread.delay(3);
        }
        TrajectoryRenderer.clear();
        throw new TaskException("尝试飞往开阔地带次数过多");
    }

    private static void clearBlockingBlock(@NotNull MinecraftClient client, int x, int z, @NotNull List<BlockPos> bp) {
        if (client.player == null) throw new TaskException("player不能为null");
        // 玩家头顶有方块阻挡，调用baritone API清除
        MsgSender.SendMsg(client.player, "头顶有方块阻挡，正在清除障碍", MsgLevel.tip);
        RunAsMainThread(() -> {
            BaritoneAPI.getProvider().getPrimaryBaritone().getCommandManager().execute("stop");
            BaritoneAPI.getProvider().getPrimaryBaritone().getBuilderProcess().clearArea(new BlockPos((int) Math.floor(client.player.getPos().getX() - 0.3), bp.getFirst().getY(), (int) Math.floor(client.player.getPos().getZ() - 0.3)), new BlockPos((int) Math.floor(client.player.getPos().getX() - 0.3) + 1, bp.getFirst().getY() + 1, (int) Math.floor(client.player.getPos().getZ() - 0.3) + 1));
        });
        for (int i = 0; i < 200; i++) {
            if (client.player == null) return;
            List<BlockPos> bp2 = RunAsMainThread(() -> getPotentialJumpBlockingBlocks(1));
            if (!BaritoneAPI.getProvider().getPrimaryBaritone().getBuilderProcess().isActive() || bp2.isEmpty()) {
                MsgSender.SendMsg(client.player, "清除完毕", MsgLevel.tip);
                oldPos = client.player.getBlockPos();
                RunAsMainThread(() -> BaritoneAPI.getProvider().getPrimaryBaritone().getElytraProcess().pathTo(new BlockPos(x, 0, z)));
                delay(10);
                return;
            }
            delay(1);
        }
        throw new TaskException("挖掘异常！");
    }

    private static void FlightStatusCheck(@NotNull MinecraftClient client, int x, int z) {
        if (client.player == null) throw new TaskException("player不能为null");
        if (!BaritoneControlChecker.isControlPlayer()) {
            if (client.player.isFallFlying()) {
                if (paused[0]) return;
                baritoneControlCounter.accumulate();
                if (baritoneControlCounter.getCount() > 15) {
                    flyToOpen(client, x, z);
                    baritoneControlCounter.clear();
                }
            } else {
                if (!client.player.isOnGround()) {
                    FallingCounter.accumulate();
                    if (FallingCounter.getCount() > 2) {
                        client.options.jumpKey.setPressed(true);
                        delay(1);
                        client.options.jumpKey.setPressed(false);
                        FallingCounter.clear();
                    }
                } else if (client.player.getVelocity().getX() < 0.01 && client.player.getVelocity().getZ() < 0.01) {

                    List<BlockPos> bp = RunAsMainThread(() -> getPotentialJumpBlockingBlocks(1));
                    if (!bp.isEmpty()) clearBlockingBlock(client, x, z, bp);
                    MsgSender.SendMsg(client.player, "自动起跳：" + jumpingCounter.getCount(), MsgLevel.warning);
                    if (jumpingCounter.getCount() <= 3) {
                        elytraTakeoff(client);
                        for (int i = 0; i < 40; i++) {
                            if (BaritoneControlChecker.isControlPlayer() || client.player.isOnGround()) break;
                            delay(1);
                        }

                    } else {
                        flyToOpen(client, x, z);
                    }

                    jumpingCounter.accumulate();
                }
            }
        }
    }

    /**
     * 计算渲染距离内的未加载区块
     *
     * @param client 客户端对象
     * @param player 玩家对象
     * @return 未加载区块的占比(0 - 1)
     */
    private static float calculateUnloadedChunks(@NotNull MinecraftClient client, @NotNull ClientPlayerEntity player) {
        ChunkPos centerChunk = new ChunkPos(player.getBlockPos());
        if (client.world == null) return 1;
        int totalChunks = 0;
        int unloadedChunks = 0;
        int RenderChunk = Math.min(client.options.getClampedViewDistance(), 5);
        // 检查周围区块
        for (int dx = -RenderChunk; dx <= RenderChunk; dx++) {
            for (int dz = -RenderChunk; dz <= RenderChunk; dz++) {
                totalChunks++;
                ChunkPos chunkPos = new ChunkPos(centerChunk.x + dx, centerChunk.z + dz);

                // 检查区块是否已加载
                if (client.world.getChunk(chunkPos.x, chunkPos.z, ChunkStatus.FULL, false) == null) {
                    unloadedChunks++;
                }
            }
        }

        return totalChunks > 0 ? (float) unloadedChunks / totalChunks : 0;
    }

    /**
     * 一个函数，检查当前未加载区块,在未加载区块过多时暂停并接管baritone飞行,通过快速来回转头原地悬停(并通过开启一些mixin防止玩家视角旋转)
     * 以等待区块加载,并适当调整timer
     *
     * @param client 客户端对象
     */
    private static void WaitForLoadChunks(@NotNull MinecraftClient client, boolean verboseDisplayDebug) {
        if (client.player == null) throw new TaskException("null");

        float a = calculateUnloadedChunks(client, client.player);

        if (verboseDisplayDebug) MsgSender.SendMsg(client.player, "未加载区块比例：" + a, MsgLevel.debug);
        if (a > 0.4 && getPotentialJumpBlockingBlocks(-7).isEmpty()) {
            MsgSender.SendMsg(client.player, "未加载区块太多，暂停baritone等待加载，接下来可能出现视角剧烈晃动！请不要直视屏幕！", MsgLevel.warning);
            RunAsMainThread(() -> BaritoneAPI.getProvider().getPrimaryBaritone().getCommandManager().execute("p"));
            cameraMixinSwitch = true;
            int tick = currentTick;
            while (calculateUnloadedChunks(client, client.player) > 0.05 && getPotentialJumpBlockingBlocks(-3).isEmpty()) {
                fixedYaw = client.player.getYaw() + (180 * ((currentTick - tick) % 2));
                fixedPitch = 0;
                RunAsMainThread(() -> {
                    client.player.setPitch(0);
                    client.player.setYaw(client.player.getYaw() + 180);
                });
                delay(1);
            }
            cameraMixinSwitch = false;
            if ((currentTick - tick) % 2 == 1)
                RunAsMainThread(() -> client.player.setYaw(client.player.getYaw() + 180));
            RunAsMainThread(() -> BaritoneAPI.getProvider().getPrimaryBaritone().getCommandManager().execute("r"));


            int tick2 = currentTick - LastWaitTick;
            LastWaitTick = currentTick;
            if (tick2 < 1200) {
                // 一分钟内多次等待,适当减慢timer
                if (timerMultiplier > 0.6f) {
                    timerMultiplier -= 0.1f;
                    MsgSender.SendMsg(client.player, "区块加载很慢,适当降低timer至" + timerMultiplier, MsgLevel.warning);
                }
            }
        } else {
            int tick2 = currentTick - LastWaitTick;
            if (tick2 > 4800) {
                LastWaitTick = currentTick;
                // 四分钟内没有出现加载过慢
                if (timerMultiplier < 1f) {
                    timerMultiplier += 0.1f;
                    MsgSender.SendMsg(client.player, "区块加载速度恢复,适当提升timer至" + timerMultiplier, MsgLevel.warning);
                }

            }

        }
    }

    /**
     * 用于检测是否需要修复鞘翅，并在必要时修复（仅附魔之瓶模式）
     *
     * @param client 客户端对象
     * @param x      目的地X坐标
     * @param z      目的地Z坐标
     */
    private static void RepairElytra(@NotNull MinecraftClient client, int x, int z) {
        if (client.player == null || client.world == null || client.interactionManager == null || client.getNetworkHandler() == null)
            throw new TaskException("null!");
        if (noElytra) return;
        ItemStack ElytraStack = client.player.getInventory().getArmorStack(2);
        if (ElytraStack.getItem() != Items.ELYTRA) throw new TaskException("未穿戴鞘翅!");
        if (ElytraStack.getDamage() > ElytraStack.getMaxDamage() - 40) {
            if (Objects.equals(client.world.getBiome(client.player.getBlockPos()).getKey().map(RegistryKey::getValue).orElse(null), Identifier.of("minecraft", "nether_wastes"))) {
                MsgSender.SendMsg(client.player, "准备修复鞘翅", MsgLevel.info);
                status = TaskStatus.LANDING;
                // 先中止baritone 飞行任务
                RunAsMainThread(() -> BaritoneAPI.getProvider().getPrimaryBaritone().getElytraProcess().pathTo(client.player.getBlockPos()));
                for (int i = 0; i < 600; i++) {
                    if (!BaritoneAPI.getProvider().getPrimaryBaritone().getElytraProcess().isActive()) break;
                    if (Objects.equals(client.world.getBiome(client.player.getBlockPos()).getKey().map(RegistryKey::getValue).orElse(null), Identifier.of("minecraft", "nether_wastes")) && client.player.isOnGround() && !BaritoneControlChecker.isControlPlayer()) {
                        RunAsMainThread(() -> BaritoneAPI.getProvider().getPrimaryBaritone().getCommandManager().execute("stop"));
                        TaskThread.delay(5);
                        break;
                    }
                    if (i == 599) throw new TaskException("无法从baritone鞘翅任务中结束！");
                    delay(1);
                }
                status = TaskStatus.REPAIR_ELYTRA;
                delay(1);
                extinguishFire(client);
                int m = 0;
                for (int i = 9; i < 36; i++) {
                    ItemStack s = client.player.getInventory().getStack(i);
                    if (s.getItem() == Items.EXPERIENCE_BOTTLE) m += s.getCount();
                }
                if (m < 32) {
                    MsgSender.SendMsg(client.player, "准备获取附魔之瓶补给", MsgLevel.info);
                    return;
                }
                // 找一个可以放附魔之瓶的快捷栏槽位
                int FireworkSlot = RustSupplyTask.findItemInHotBar(client.player, Items.FIREWORK_ROCKET);

                // 打开物品栏
                HandledScreen<?> handled = RunAsMainThread(() -> {
                    client.setScreen(new InventoryScreen(client.player));
                    if (!(client.currentScreen instanceof HandledScreen<?> handled2))
                        throw new TaskException("窗口异常");
                    return handled2;
                });


                // 取出附魔之瓶
                int BottleSlot = -1;
                ScreenHandler handler = handled.getScreenHandler();
                for (int i = 9; i < 36; i++) {
                    Slot s = handler.getSlot(i);
                    if (s == null) continue;
                    ItemStack stack = s.getStack();
                    if (stack == null || stack.isEmpty()) continue;
                    Item item = stack.getItem();
                    if (item == Items.EXPERIENCE_BOTTLE) {
                        int finalI = i;
                        if (BottleSlot == -1) BottleSlot = i;
                        RunAsMainThread(() -> {
                            client.interactionManager.clickSlot(handler.syncId, finalI, 0, SlotActionType.PICKUP, client.player);
                            client.interactionManager.clickSlot(handler.syncId, FireworkSlot + 36, 0, SlotActionType.PICKUP, client.player);
                            client.interactionManager.clickSlot(handler.syncId, finalI, 0, SlotActionType.PICKUP, client.player);
                        });
                    }
                }
                if (client.player.getInventory().getStack(FireworkSlot).getItem() != Items.EXPERIENCE_BOTTLE || client.player.getInventory().getStack(FireworkSlot).getCount() < 30)
                    throw new TaskException("没有足够的附魔之瓶");
                RunAsMainThread(handled::close);

                // 低头
                client.player.setPitch(90);
                // 切换槽位
                RunAsMainThread(() -> {
                    client.player.getInventory().selectedSlot = FireworkSlot;
                    client.getNetworkHandler().sendPacket(new UpdateSelectedSlotC2SPacket(FireworkSlot));
                });
                // 火球保护
                RSTTask FireballTask = scheduleTask((self, args) -> {
                    if (!FireballProtector(client)) {
                        ModStatus = ModStatuses.canceled;
                        self.repeatTimes = 0;
                        MsgSender.SendMsg(client.player, "无法拦截火球", MsgLevel.fatal);
                        Objects.requireNonNull(getModThread()).taskFailed(client, "补给任务失败！自动退出！", 1);
                    }
                }, 1, -1, 1, 100);
                delay(2);
                MsgSender.SendMsg(client.player, "开始修复", MsgLevel.tip);
                // 至多40个附魔之瓶修复
                for (int i = 0; i < 40; i++) {
                    if (client.player.getInventory().getArmorStack(2).getDamage() < 25) break;
                    if (i == 39) {
                        FireballTask.repeatTimes = 0;
                        throw new TaskException("修补鞘翅异常");
                    }
                    if (client.player.getPitch() < 85) {
                        scheduleTask((s, a) -> client.player.setPitch(90), 1, 0, 5, 10);
                        delay(6);
                    }
                    RunAsMainThread(() -> client.interactionManager.interactItem(client.player, Hand.MAIN_HAND));
                    delay(4);
                }

                MsgSender.SendMsg(client.player, "修复完毕", MsgLevel.tip);
                FireballTask.repeatTimes = 0;
                // 再次打开物品栏
                HandledScreen<?> handled2 = RunAsMainThread(() -> {
                    client.setScreen(new InventoryScreen(client.player));
                    if (!(client.currentScreen instanceof HandledScreen<?> handled3))
                        throw new TaskException("窗口异常");
                    return handled3;
                });
                // 放回附魔之瓶
                ScreenHandler handler2 = handled2.getScreenHandler();
                int finalBottleSlot = BottleSlot;
                RunAsMainThread(() -> {
                    client.interactionManager.clickSlot(handler2.syncId, finalBottleSlot, 0, SlotActionType.PICKUP, client.player);
                    client.interactionManager.clickSlot(handler2.syncId, FireworkSlot + 36, 0, SlotActionType.PICKUP, client.player);
                    client.interactionManager.clickSlot(handler2.syncId, finalBottleSlot, 0, SlotActionType.PICKUP, client.player);
                    handled2.close();
                });
                BaritoneAPI.getProvider().getPrimaryBaritone().getElytraProcess().pathTo(new BlockPos(x, 0, z));
                delay(15);
                status = TaskStatus.FLYING;
                return;
            }
            if (ElytraStack.getDamage() > ElytraStack.getMaxDamage() - 8) throw new TaskException("鞘翅耐久过低！");
        }
    }

    private static void ElytraChecker(@NotNull MinecraftClient client) {
        if (client.player == null) throw new TaskException("null");
        if (noElytra) return;
        int m = 0;
        for (int i = 0; i < 41; i++) {
            ItemStack s = client.player.getInventory().getStack(i);
            if (s.getItem() == Items.ELYTRA) m += s.getMaxDamage() - s.getDamage();
        }
        if (m < 40) {
            MsgSender.SendMsg(client.player, "鞘翅耐久低，准备降落", MsgLevel.info);
            noElytra = true;
        }

    }

    /**
     * 鞘翅主函数
     *
     * @param client 客户端对象
     * @param x      目的地X坐标
     * @param z      目的地Z坐标
     * @return 是否到达目的地
     * @throws TaskException 任务异常
     * @throws TaskCanceled  任务中止
     */
    static boolean ElytraTask(@NotNull MinecraftClient client, int x, int z, TaskType type) throws TaskException, TaskCanceled {
        boolean verboseDisplayDebug = getBoolean("verboseDisplayDebug", false);
        Food = FoodList[getInt("FoodIndex", 0)];
        // 重置各个状态
        timerMultiplier = 1;
        resetStatus();
        // 设置baritone
        BaritoneAPI.getSettings().elytraAutoJump.value = false;
        BaritoneAPI.getSettings().elytraFireworkSpeed.value = 0.5;
        if (type == INFINITY_ELYTRA) BaritoneAPI.getSettings().elytraAllowEmergencyLand.value = false;
        BaritoneAPI.getSettings().logger.value = (var1x -> {
            try {
                MessageIndicator var2 = BaritoneAPI.getSettings().useMessageTag.value ? Helper.MESSAGE_TAG : null;
                MinecraftClient.getInstance().inGameHud.getChatHud().addMessage(var1x, null, var2);
                // 检测是否提示分段错误
                if (MinecraftClient.getInstance().player != null && (var1x.getString().contains("Failed to compute path to destination") || var1x.getString().contains("Failed to recompute segment") || var1x.getString().contains("Failed to compute next segment"))) {
                    SegFailedCounter.accumulate();
                    waitReset = true;
                }
            } catch (Throwable var3) {
                LOGGER.warn("Failed to log message to chat: {}", var1x.getString(), var3);
            }
        });

        if (client.player == null) throw new TaskException("player为null");

        TaskThread.status = TaskStatus.FLYING;

        oldPos = client.player.getBlockPos();
        BlockPos segPos = oldPos;
        client.player.setPitch(-30);
        // 调用baritoneAPI,准备开始寻路
        RunAsMainThread(() -> BaritoneAPI.getProvider().getPrimaryBaritone().getElytraProcess().pathTo(new BlockPos(x, 0, z)));
        delay(15);

        // 跳起
        elytraTakeoff(client);

        if (client.player == null || client.getNetworkHandler() == null || client.interactionManager == null || client.world == null)
            throw new TaskException("飞行任务失败！null异常！");
        // 鞘翅守护任务
        while (true) {
            if (client.player == null) throw new TaskException("飞行任务失败！null异常！");
            boolean result = RunAsMainThread2(() -> BaritoneAPI.getProvider().getPrimaryBaritone().getElytraProcess().isActive());
            if (!result) {
                // 此时，到达阶段目的地，准备获取补给
                arrivedTarget(client, segPos);
                RunAsMainThread(() -> BaritoneAPI.getSettings().logger.value = BaritoneAPI.getSettings().logger.defaultValue);
                return client.player.getBlockPos().isWithinDistance(new BlockPos(x, 0, z), 3501);
            } else {
                AutoEscapeLava(client);
                FlightStatusCheck(client, x, z);
                baritoneChecker(client, x, z);
                AutoEating(client);
                if (currentTick % 10 == 0) WaitForLoadChunks(client, verboseDisplayDebug);
                if (currentTick % 5 == 0) FireworkChecker(client, segPos);
                if (type == EXP_BOTTLE && currentTick % 5 == 0) RepairElytra(client, x, z);
                if (type == ELYTRA && currentTick % 5 == 0) ElytraChecker(client);
                if (!arrived && ((noFirework || noElytra) && Objects.equals(client.world.getBiome(client.player.getBlockPos()).getKey().map(RegistryKey::getValue).orElse(null), Identifier.of("minecraft", "nether_wastes")) && !client.player.isOnFire()) || client.player.getBlockPos().isWithinDistance(new BlockPos(x, 0, z), 300)) {
                    scheduleTask((s6, a6) -> {
                        if (client.player != null)
                            BaritoneAPI.getProvider().getPrimaryBaritone().getElytraProcess().pathTo(client.player.getBlockPos());
                    }, 1, 0, 15, 1000);
                    MsgSender.SendMsg(client.player, "到达降落地点，提前降落！", MsgLevel.tip);
                    arrived = true;
                    status = TaskStatus.LANDING;
                }
            }
            delay(1);
        }
    }
}
