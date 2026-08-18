package dev.rstminecraft;

import dev.rstminecraft.RustClientCore.MinecraftContext;
import dev.rstminecraft.RustClientCore.messenger.MsgLevel;
import dev.rstminecraft.RustClientCore.utils.BetterBlockPos;
import dev.rstminecraft.RustClientCore.utils.Pair;
import dev.rstminecraft.elytra.takeoff.WalkToTakeoff;
import dev.rstminecraft.utils.Rotation;
import net.minecraft.block.Block;
import net.minecraft.block.Blocks;
import net.minecraft.block.ShapeContext;
import net.minecraft.block.ShulkerBoxBlock;
import net.minecraft.client.gui.screen.Screen;
import net.minecraft.client.gui.screen.ingame.HandledScreen;
import net.minecraft.client.gui.screen.ingame.InventoryScreen;
import net.minecraft.component.DataComponentTypes;
import net.minecraft.component.type.ContainerComponent;
import net.minecraft.enchantment.Enchantment;
import net.minecraft.enchantment.EnchantmentHelper;
import net.minecraft.enchantment.Enchantments;
import net.minecraft.entity.player.PlayerInventory;
import net.minecraft.item.BlockItem;
import net.minecraft.item.Item;
import net.minecraft.item.ItemStack;
import net.minecraft.item.Items;
import net.minecraft.network.packet.c2s.play.UpdateSelectedSlotC2SPacket;
import net.minecraft.registry.RegistryKey;
import net.minecraft.registry.entry.RegistryEntry;
import net.minecraft.screen.ScreenHandler;
import net.minecraft.screen.slot.Slot;
import net.minecraft.screen.slot.SlotActionType;
import net.minecraft.util.ActionResult;
import net.minecraft.util.Hand;
import net.minecraft.util.collection.DefaultedList;
import net.minecraft.util.hit.BlockHitResult;
import net.minecraft.util.hit.HitResult;
import net.minecraft.util.math.BlockPos;
import net.minecraft.util.math.Direction;
import net.minecraft.util.math.Vec3d;
import net.minecraft.world.RaycastContext;
import net.minecraft.world.World;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import java.util.*;
import java.util.function.Function;
import java.util.stream.Collectors;
import java.util.stream.IntStream;

import static dev.rstminecraft.FireballProtect.isHittingFireball;
import static dev.rstminecraft.ModTask.*;
import static dev.rstminecraft.RustClientCore.task.TaskManager.*;
import static dev.rstminecraft.RustElytraClient.*;


public class SupplyTask {

    private static Item Food = FoodList[0];

    /**
     * 走到方块中央
     */
    private static void WalkingToCenter() {
        while (true) {
            BlockPos footBlock = MinecraftContext.player().getBlockPos();
            Vec3d CenterPos = new Vec3d(footBlock.getX() + 0.5, MinecraftContext.player().getY(), footBlock.getZ() + 0.5);
            Vec3d current = MinecraftContext.player().getEntityPos();
            Vec3d delta = CenterPos.subtract(current);
            // 到达方块中心则停止
            if (Math.abs(delta.x) < 0.2 && Math.abs(delta.z) < 0.2) {
                MinecraftContext.client().options.forwardKey.setPressed(false);
                msg.SendMsg("行走完成", MsgLevel.tip);
                return;
            }
            // 调整朝向
            double yaw = Math.toDegrees(Math.atan2(-delta.x, delta.z));
            MinecraftContext.player().setYaw((float) yaw);

            // 模拟按下 W
            MinecraftContext.client().options.forwardKey.setPressed(true);
            delay(1);
        }
    }

    public static int getFireworkLevel(@NotNull ItemStack stack) {
        if (stack.isEmpty() || !stack.isOf(Items.FIREWORK_ROCKET)) {
            return 0;
        }


        return Objects.requireNonNull(stack.get(DataComponentTypes.FIREWORKS)).flightDuration();
    }

    public static void extinguishFire() {
        List<BlockPos> fire = new ArrayList<>();
        runOnMain(() -> {
            int radius = 3;
            for (int i = -radius; i <= radius; i++) {
                for (int j = -radius; j <= radius; j++) {
                    for (int k = -radius; k <= radius; k++) {
                        BlockPos target = MinecraftContext.player().getBlockPos().add(i, j, k);
                        if (MinecraftContext.world().getBlockState(target).getBlock() == Blocks.FIRE)
                            fire.add(target);
                    }
                }
            }
        });
        for (BlockPos bp : fire) {
            runOnMain(() -> {
                lookAt(Vec3d.ofCenter(bp));
                MinecraftContext.interactionManager().attackBlock(bp, Direction.UP);
            });
            delay(1);
        }
    }

    private static void mergeItemInInv(@NotNull Function<ItemStack, Boolean> c, @NotNull ScreenHandler handler, int slotMin, int slotMax) {
        while (true) {
            List<Integer> l = new ArrayList<>();
            for (int i = slotMin; i < slotMax; i++) {
                ItemStack stack = handler.getSlot(i).getStack();
                if (c.apply(stack))
                    l.add(i);
            }
            l.sort(Comparator.comparingInt(i -> handler.getSlot(i).getStack().getCount()));
            if (l.size() < 2 || handler.getSlot(l.get(1)).getStack().getCount() == handler.getSlot(l.get(1)).getStack().getMaxCount())
                break;
            MinecraftContext.interactionManager().clickSlot(handler.syncId, l.getFirst(), 0, SlotActionType.PICKUP, MinecraftContext.player());
            MinecraftContext.interactionManager().clickSlot(handler.syncId, l.getFirst(), 0, SlotActionType.PICKUP_ALL, MinecraftContext.player());
            MinecraftContext.interactionManager().clickSlot(handler.syncId, l.getFirst(), 0, SlotActionType.PICKUP, MinecraftContext.player());
        }
    }

    /**
     * 整理物品栏，并检查玩家是否有足够的物资
     */
    private static void SortAndCheckInv() {
        runOnMain(() -> {
            MinecraftContext.client().setScreen(new InventoryScreen(MinecraftContext.player()));
            Screen screen2 = MinecraftContext.client().currentScreen;
            if (!(screen2 instanceof HandledScreen<?> handled2))
                throw new TaskException("窗口异常！");

            // 整理物品栏
            ScreenHandler handler2 = handled2.getScreenHandler();

            for (int i = 36; i < 45; i++) {
                Item item = handler2.getSlot(i).getStack().getItem();
                if (item != Items.NETHERITE_PICKAXE && item != Items.DIAMOND_PICKAXE && item != Items.NETHERITE_SWORD &&
                    item != Items.DIAMOND_SWORD && item != Items.ENDER_CHEST && item != Food && item != Items.TOTEM_OF_UNDYING)
                    continue;
                for (int j = 9; j < 36; j++) {
                    Item item2 = handler2.getSlot(j).getStack().getItem();
                    if (item2 != Items.NETHERITE_PICKAXE && item2 != Items.DIAMOND_PICKAXE && item2 != Items.NETHERITE_SWORD &&
                        item2 != Items.DIAMOND_SWORD && item2 != Items.ENDER_CHEST && item2 != Food && item2 != Items.TOTEM_OF_UNDYING) {
                        MinecraftContext.interactionManager().clickSlot(handler2.syncId, i, 0, SlotActionType.PICKUP, MinecraftContext.player());
                        MinecraftContext.interactionManager().clickSlot(handler2.syncId, j, 0, SlotActionType.PICKUP, MinecraftContext.player());
                        MinecraftContext.interactionManager().clickSlot(handler2.syncId, i, 0, SlotActionType.PICKUP, MinecraftContext.player());
                        break;
                    }
                }
            }

            for (int i = 9; i < 36; i++) {
                Item item = handler2.getSlot(i).getStack().getItem();
                while (!(item != Items.NETHERITE_PICKAXE && item != Items.DIAMOND_PICKAXE && item != Items.NETHERITE_SWORD &&
                         item != Items.DIAMOND_SWORD && item != Items.ENDER_CHEST && item != Food && item != Items.TOTEM_OF_UNDYING)) {
                    if (item == Items.NETHERITE_PICKAXE || item == Items.DIAMOND_PICKAXE) {
                        // 镐放到快捷栏第一格
                        if (MinecraftContext.player().getInventory().getStack(0).getItem() == Items.DIAMOND_PICKAXE ||
                            MinecraftContext.player().getInventory().getStack(0).getItem() == Items.NETHERITE_PICKAXE) {
                            break;
                        }
                        MinecraftContext.interactionManager().clickSlot(handler2.syncId, i, 0, SlotActionType.PICKUP, MinecraftContext.player());
                        MinecraftContext.interactionManager().clickSlot(handler2.syncId, 36, 0, SlotActionType.PICKUP, MinecraftContext.player());
                        MinecraftContext.interactionManager().clickSlot(handler2.syncId, i, 0, SlotActionType.PICKUP, MinecraftContext.player());

                    } else if (item == Items.NETHERITE_SWORD || item == Items.DIAMOND_SWORD) {
                        // 剑放到第二格
                        if (MinecraftContext.player().getInventory().getStack(1).getItem() == Items.DIAMOND_SWORD ||
                            MinecraftContext.player().getInventory().getStack(1).getItem() == Items.NETHERITE_SWORD) {
                            break;
                        }
                        MinecraftContext.interactionManager().clickSlot(handler2.syncId, i, 0, SlotActionType.PICKUP, MinecraftContext.player());
                        MinecraftContext.interactionManager().clickSlot(handler2.syncId, 37, 0, SlotActionType.PICKUP, MinecraftContext.player());
                        MinecraftContext.interactionManager().clickSlot(handler2.syncId, i, 0, SlotActionType.PICKUP, MinecraftContext.player());
                    } else if (item == Items.ENDER_CHEST) {
                        // 末影箱放到第三格
                        MinecraftContext.interactionManager().clickSlot(handler2.syncId, i, 0, SlotActionType.PICKUP, MinecraftContext.player());
                        MinecraftContext.interactionManager().clickSlot(handler2.syncId, 38, 0, SlotActionType.PICKUP, MinecraftContext.player());
                        MinecraftContext.interactionManager().clickSlot(handler2.syncId, i, 0, SlotActionType.PICKUP, MinecraftContext.player());

                        if (handler2.getSlot(i).getStack().getItem() == Items.ENDER_CHEST)
                            break;
                    } else if (item == Items.TOTEM_OF_UNDYING) {
                        // 图腾放到第四和第五格
                        if (MinecraftContext.player().getInventory().getStack(3).getItem() == Items.TOTEM_OF_UNDYING) {
                            if (MinecraftContext.player().getInventory().getStack(4).getItem() == Items.TOTEM_OF_UNDYING)
                                break;
                            MinecraftContext.interactionManager().clickSlot(handler2.syncId, i, 0, SlotActionType.PICKUP, MinecraftContext.player());
                            MinecraftContext.interactionManager().clickSlot(handler2.syncId, 40, 0, SlotActionType.PICKUP, MinecraftContext.player());
                            MinecraftContext.interactionManager().clickSlot(handler2.syncId, i, 0, SlotActionType.PICKUP, MinecraftContext.player());
                        } else {
                            MinecraftContext.interactionManager().clickSlot(handler2.syncId, i, 0, SlotActionType.PICKUP, MinecraftContext.player());
                            MinecraftContext.interactionManager().clickSlot(handler2.syncId, 39, 0, SlotActionType.PICKUP, MinecraftContext.player());
                            MinecraftContext.interactionManager().clickSlot(handler2.syncId, i, 0, SlotActionType.PICKUP, MinecraftContext.player());
                        }
                    } else {
                        // 食物放到第六格
                        MinecraftContext.interactionManager().clickSlot(handler2.syncId, i, 0, SlotActionType.PICKUP, MinecraftContext.player());
                        MinecraftContext.interactionManager().clickSlot(handler2.syncId, 41, 0, SlotActionType.PICKUP, MinecraftContext.player());
                        MinecraftContext.interactionManager().clickSlot(handler2.syncId, i, 0, SlotActionType.PICKUP, MinecraftContext.player());
                        if (handler2.getSlot(i).getStack().getItem() == Food)
                            break;

                    }
                    item = handler2.getSlot(i).getStack().getItem();
                }
            }

            // 检查物品栏
            int enderChestCount = 0;
            boolean pickaxe = false;
            boolean sword = false;
            int FoodCount = 0;
            for (int i = 0; i < 9; i++) {
                ItemStack s = MinecraftContext.player().getInventory().getStack(i);
                if (s.getItem() == Items.NETHERITE_PICKAXE ||
                    s.getItem() == Items.DIAMOND_PICKAXE && isStackHasEnchantment(s, Enchantments.EFFICIENCY, 4) &&
                    isStackHasEnchantment(s, Enchantments.SILK_TOUCH, 1))
                    pickaxe = true;
                else if (s.getItem() == Items.NETHERITE_SWORD || s.getItem() == Items.DIAMOND_SWORD)
                    sword = true;
                else if (s.getItem() == Items.ENDER_CHEST)
                    enderChestCount += s.getCount();
                else if (s.getItem() == Food)
                    FoodCount += s.getCount();
            }
            int diamondArmor = 0;
            int goldenArmor = 0;
            boolean elytra;
            ItemStack s = MinecraftContext.player().getInventory().getStack(38);
            elytra = s.getItem() == Items.ELYTRA && isStackHasEnchantment(s, Enchantments.UNBREAKING, 3);
            s = MinecraftContext.player().getInventory().getStack(36);
            if ((s.getItem() == Items.DIAMOND_BOOTS || s.getItem() == Items.NETHERITE_BOOTS) && isStackHasEnchantment(s, Enchantments.PROTECTION, 4))
                diamondArmor++;
            if (s.getItem() == Items.GOLDEN_BOOTS && isStackHasEnchantment(s, Enchantments.PROTECTION, 4))
                goldenArmor++;
            s = MinecraftContext.player().getInventory().getStack(37);
            if ((s.getItem() == Items.DIAMOND_LEGGINGS || s.getItem() == Items.NETHERITE_LEGGINGS) &&
                isStackHasEnchantment(s, Enchantments.PROTECTION, 4))
                diamondArmor++;
            if (s.getItem() == Items.GOLDEN_LEGGINGS && isStackHasEnchantment(s, Enchantments.PROTECTION, 4))
                goldenArmor++;
            s = MinecraftContext.player().getInventory().getStack(39);
            if ((s.getItem() == Items.DIAMOND_HELMET || s.getItem() == Items.NETHERITE_HELMET) &&
                isStackHasEnchantment(s, Enchantments.PROTECTION, 4))
                diamondArmor++;
            if (s.getItem() == Items.GOLDEN_HELMET && isStackHasEnchantment(s, Enchantments.PROTECTION, 4))
                goldenArmor++;


            if (enderChestCount <= 2)
                throw new TaskException("物资不足：至少需要3个末影箱！");
            if (!pickaxe)
                throw new TaskException("物资不足：需要有一把 经验修补吧 耐久3 效率4或效率5 的钻石或合金镐！");
            if (!sword)
                throw new TaskException("物资不足：需要有一把的钻石或合金剑（不要求附魔）！");
            if (!elytra)
                throw new TaskException("物资不足：需要穿戴 耐久3 经验修补的鞘翅！");
            if (FoodCount <= 15)
                throw new TaskException("物资不足：需要至少16个" + Food.getName().getString() + "!");

            if (inspectArmor.get() && (goldenArmor != 1 || diamondArmor != 2))
                throw new TaskException("物资不足：需要穿戴有 保护4 推荐含有经验修补和耐久3 的一件金质盔甲和2件合金或钻石盔甲！");

            mergeItemInInv(s2 -> s2.getItem() == Items.FIREWORK_ROCKET && getFireworkLevel(s2) == 1, handler2, 9, 36);
            mergeItemInInv(s2 -> s2.getItem() == Items.FIREWORK_ROCKET && getFireworkLevel(s2) == 2, handler2, 9, 36);
            mergeItemInInv(s2 -> s2.getItem() == Items.FIREWORK_ROCKET && getFireworkLevel(s2) == 3, handler2, 9, 36);
            mergeItemInInv(s2 -> s2.getItem() == Items.EXPERIENCE_BOTTLE, handler2, 9, 36);

            handled2.close();
        });
    }

    /**
     * 在玩家快捷栏寻找物品
     *
     * @param item 寻找的物品
     * @return 物品位置（-1 代表没有）
     */
    static int findItemInHotBar(Item item) {
        int slot = -1;
        for (int i = 0; i < 9; i++) {
            ItemStack stack = MinecraftContext.player().getInventory().getStack(i);
            if (!stack.isEmpty() && stack.getItem() == item) {
                slot = i;
                break;
            }
        }
        return slot;
    }

    /**
     * 寻找可放置末影箱或潜影盒的位置
     *
     * @return 可以放置目标方块的坐标
     */
    private static @Nullable BlockPos findPlaceTarget() {
        BlockPos origin = MinecraftContext.player().getBlockPos();
        World world = MinecraftContext.player().getEntityWorld();

        int[] dx = {1, -1, 0, 0};
        int[] dz = {0, 0, -1, 1};

        for (int i = 0; i < 4; i++) {
            // 不能与玩家重合
            BlockPos target = origin.add(dx[i], 0, dz[i]);

            // 目标必须是空气或可替换方块
            if (!world.getBlockState(target).isAir() && !world.getBlockState(target).isReplaceable())
                continue;

            // 下方必须是实心方块
            BlockPos below = target.down();
            if (!world.getBlockState(below).isSolidBlock(world, below))
                continue;
            if (world.getBlockState(below).getBlock() == Blocks.SOUL_SAND)
                continue;
            // 上方2格必须是空气
            if (!world.getBlockState(target.up()).isAir() || !world.getBlockState(target.up(2)).isAir())
                continue;

            return target;
        }
        return null;
    }

    /**
     * 让玩家看向某一个坐标
     *
     * @param target 需要看向的目标方块坐标
     */
    private static void lookAt(@NotNull Vec3d target) {
        Vec3d eyes = MinecraftContext.player().getEyePos();
        Vec3d dir = target.subtract(eyes);

        double distXZ = Math.sqrt(dir.x * dir.x + dir.z * dir.z);
        float yaw = (float) (Math.toDegrees(Math.atan2(dir.z, dir.x)) - 90.0);
        float pitch = (float) -Math.toDegrees(Math.atan2(dir.y, distXZ));

        MinecraftContext.player().setYaw(yaw);
        MinecraftContext.player().setPitch(pitch);
    }

    /**
     * 尝试放置并打开某个容器
     *
     * @param targetPos  目标放置位置
     * @param HotBarSlot 容器在快捷栏的位置
     */
    private static void PlaceAndOpenContainer(@NotNull BlockPos targetPos, int HotBarSlot) {
        runOnMain(() -> {
            // 切换槽位
            MinecraftContext.player().getInventory().setSelectedSlot(HotBarSlot);
            MinecraftContext.networkHandler().sendPacket(new UpdateSelectedSlotC2SPacket(HotBarSlot));
            // 看向目标
            lookAt(Vec3d.ofCenter(targetPos));
        });
        delay(3);
        // 点击数据
        BlockPos support = targetPos.down();
        Vec3d hitPos = Vec3d.ofCenter(support).add(0, 0.5, 0);
        BlockHitResult hitResult = new BlockHitResult(hitPos, Direction.UP, support, false);

        // 尝试放置
        ActionResult result = computeOnMain(() -> {
            MinecraftContext.player().swingHand(Hand.MAIN_HAND);
            return MinecraftContext.interactionManager().interactBlock(MinecraftContext.player(), Hand.MAIN_HAND, hitResult);
        });
        // 检查结果
        if (!result.isAccepted())
            throw new TaskException("放置失败");

        delay(5);
        OpenContainer(targetPos);
    }

    /**
     * 打开某个容器
     *
     * @param targetPos 目标放置位置
     */
    private static void OpenContainer(@NotNull BlockPos targetPos) {
        // 准备打开
        msg.SendMsg("尝试放置末影箱成功，现在打开末影箱", MsgLevel.tip);
        BlockHitResult hitResult2 = new BlockHitResult(Vec3d.ofCenter(targetPos), Direction.UP, targetPos, false);
        ActionResult result = computeOnMain(() -> {
            MinecraftContext.player().swingHand(Hand.MAIN_HAND);
            return MinecraftContext.interactionManager().interactBlock(MinecraftContext.player(), Hand.MAIN_HAND, hitResult2);
        });
        MinecraftContext.player().swingHand(Hand.MAIN_HAND);
        // 检查结果
        if (!result.isAccepted())
            throw new TaskException("打开失败");
    }

    /**
     * 检查当前玩家屏幕是不是容器屏幕
     *
     * @param ContainerName 目标容器名
     * @return 返回目标屏幕信息(handled, handler, screen)
     */
    private static @Nullable HandledScreen<?> ContainerScreenChecker(@NotNull String ContainerName) {
        Screen screen = MinecraftContext.client().currentScreen;
        // 不是容器界面
        if (!(screen instanceof HandledScreen<?> handled))
            return null;

        // 不是目标容器
        if (!ContainerName.equalsIgnoreCase(handled.getTitle().getString()))
            return null;

        ScreenHandler handler = handled.getScreenHandler();
        int totalSlots = handler.slots.size();
        int containerSlots = totalSlots - 36;
        if (containerSlots <= 0)
            containerSlots = 27;
        boolean anyNonEmpty = false;
        for (int i = 0; i < containerSlots; i++) {
            Slot s = handler.getSlot(i);
            if (s != null) {
                ItemStack st = s.getStack();
                if (st != null && !st.isEmpty()) {
                    anyNonEmpty = true;
                    break;
                }
            }
        }
        if (!anyNonEmpty) {
            return null;
        }

        return handled;

    }

    /**
     * 打印末影箱中潜影盒的内容物，并判断是否满足条件
     *
     * @param handled 已经打开的末影箱窗口的handled
     * @return 潜影盒拿取列表。
     */
    private static int[][] SupplyShulkerFinder(@NotNull HandledScreen<?> handled) {
        StringBuilder sb = new StringBuilder();
        int totalSlots = handled.getScreenHandler().slots.size();
        int containerSlots = totalSlots - 36;
        if (containerSlots <= 0)
            containerSlots = 27;
        int[][] data = new int[27][3];

        for (int i = 0; i < containerSlots; i++) {
            Slot s = handled.getScreenHandler().getSlot(i);
            if (s == null)
                continue;
            ItemStack stack = s.getStack();
            if (stack == null || stack.isEmpty())
                continue;
            // 判断是否为潜影盒
            if (stack.getItem() instanceof BlockItem bi) {
                if (bi.getBlock() instanceof ShulkerBoxBlock) {
                    ContainerComponent container = stack.get(DataComponentTypes.CONTAINER);
                    sb.append("  (slot ").append(i).append(") - ").append(bi.getName().getString()).append("\n");
                    sb.append("\n");
                    if (container != null) {
                        DefaultedList<ItemStack> inner = DefaultedList.ofSize(27, ItemStack.EMPTY);
                        container.copyTo(inner); // 把 component 内容拷贝到列表

                        data[i][0] = ShulkerInnerFinder(Items.FIREWORK_ROCKET, inner);
                        data[i][1] = ShulkerInnerFinder(Food, inner);
                        data[i][2] = ShulkerInnerFinder(Items.TOTEM_OF_UNDYING, inner);
                    } else {
                        sb.append("  (shulker is null...warning...)").append("\n");
                    }
                }
            }
        }

        // 没找到任何目标物品
        if (sb.isEmpty()) {
            msg.SendMsg("没有目标物品。", MsgLevel.debug);
        } else {
            String[] lines = sb.toString().split("\n");
            for (String line : lines) {
                if (line == null || line.isEmpty())
                    continue;
                msg.SendMsg(line, MsgLevel.debug);
            }
        }

        return data;
    }

    /**
     * 在潜影盒内容物列表中寻找目标物品数量
     *
     * @param item  目标物品
     * @param inner 潜影盒内容物列表
     * @return 物品数量
     */
    private static int ShulkerInnerFinder(Item item, @NotNull DefaultedList<ItemStack> inner) {
        int num = 0;
        // 遍历内存储的每个物品堆栈
        for (ItemStack stack : inner) {
            if (stack.isEmpty()) {
                continue;  // 跳过空的物品堆栈
            }
            // 判断是否为查找物品
            if (stack.getItem() == item) {
                num += stack.getCount();
            }
        }
        return num;
    }

    /**
     * 检测某个stack是否有某个附魔(且等级大于要求)
     *
     * @param stack       ItemStack
     * @param enchantment 附魔名称。如Enchantments.UNBREAKING
     * @param minLevel    最小等级
     * @return 是否有符合要求的附魔
     */
    private static boolean isStackHasEnchantment(@NotNull ItemStack stack, RegistryKey<Enchantment> enchantment, int minLevel) {
        var enchantments = stack.get(DataComponentTypes.ENCHANTMENTS);
        if (enchantments != null) {
            var enc = enchantments.getEnchantments();
            for (RegistryEntry<Enchantment> entry : enc) {
                if (entry.getKey().isPresent() && entry.getKey().get() == enchantment && EnchantmentHelper.getLevel(entry, stack) >= minLevel) {
                    return true;
                }
            }
        }
        return false;
    }

    /**
     * 在玩家物品栏搜索物品
     *
     * @param SearchingItem 寻找的物品
     * @return 目标物品数量
     */
    public static int countItemInInventory(@NotNull Item SearchingItem) {
        int count = 0;
        PlayerInventory inventory = MinecraftContext.player().getInventory();
        for (int i = 0; i < inventory.getMainStacks().size(); i++) {
            ItemStack stack = inventory.getMainStacks().get(i);
            if (stack.getItem() == SearchingItem.asItem()) {
                count += stack.getCount();
            }
        }
        return count;
    }

    /**
     * 从潜影盒窗口中取出补给，特别处理食物
     *
     * @param handler 潜影盒窗口handler
     */
    private static void PutOutSupply(@NotNull ScreenHandler handler, @NotNull List<Integer> replaceList, int[] supplyList) {
        runOnMain(() -> {
            msg.SendMsg(String.format("本盒需要取出%d个烟花,%d个%s,%d个图腾",
                    supplyList[0], supplyList[1], Food.getName().getString(), supplyList[2]), MsgLevel.debug);


            mergeItemInInv(s2 -> s2.getItem() == Items.FIREWORK_ROCKET && getFireworkLevel(s2) == 1, handler, 0, 27);
            mergeItemInInv(s2 -> s2.getItem() == Items.FIREWORK_ROCKET && getFireworkLevel(s2) == 2, handler, 0, 27);
            mergeItemInInv(s2 -> s2.getItem() == Items.FIREWORK_ROCKET && getFireworkLevel(s2) == 3, handler, 0, 27);


            int slot = 0;
            if (replaceList.isEmpty()) {
                if (supplyList[0] > 0)
                    throw new RuntimeException("没多余槽位了,你遇到了不可能发生的错误,请联系开发者");
            } else {
                slot = replaceList.removeFirst();
            }
            int[] exist = new int[3];
            for (int i = 0; i < 27; i++) {
                ItemStack stack = handler.getSlot(i).getStack();
                if (stack.getItem() == Food && exist[1] < supplyList[1]) {
                    int foodCount = stack.getCount();
                    for (int j = 0; j < 9; j++) {
                        ItemStack s = MinecraftContext.player().getInventory().getStack(j);
                        if (s.getItem() == Food) {
                            MinecraftContext.interactionManager().clickSlot(handler.syncId, i, 0, SlotActionType.PICKUP, MinecraftContext.player());
                            MinecraftContext.interactionManager()
                                    .clickSlot(handler.syncId, 54 + j, 0, SlotActionType.PICKUP, MinecraftContext.player());
                            MinecraftContext.interactionManager().clickSlot(handler.syncId, i, 0, SlotActionType.PICKUP, MinecraftContext.player());
                            exist[1] += foodCount;
                            break;
                        }
                    }
                } else if (stack.getItem() == Items.TOTEM_OF_UNDYING && exist[2] < supplyList[2]) {
                    if (MinecraftContext.player().getInventory().getStack(3).getItem() == Items.TOTEM_OF_UNDYING) {
                        if (MinecraftContext.player().getInventory().getStack(4).getItem() == Items.TOTEM_OF_UNDYING)
                            continue;
                        MinecraftContext.interactionManager().clickSlot(handler.syncId, i, 0, SlotActionType.PICKUP, MinecraftContext.player());
                        MinecraftContext.interactionManager().clickSlot(handler.syncId, 58, 0, SlotActionType.PICKUP, MinecraftContext.player());
                        MinecraftContext.interactionManager().clickSlot(handler.syncId, i, 0, SlotActionType.PICKUP, MinecraftContext.player());
                    } else {
                        MinecraftContext.interactionManager().clickSlot(handler.syncId, i, 0, SlotActionType.PICKUP, MinecraftContext.player());
                        MinecraftContext.interactionManager().clickSlot(handler.syncId, 57, 0, SlotActionType.PICKUP, MinecraftContext.player());
                        MinecraftContext.interactionManager().clickSlot(handler.syncId, i, 0, SlotActionType.PICKUP, MinecraftContext.player());
                    }
                    exist[2]++;
                } else if (stack.getItem() == Items.FIREWORK_ROCKET && exist[0] < supplyList[0]) {
                    int oldCount = stack.getCount();
                    if (MinecraftContext.player().getInventory().getStack(slot).getItem() != Items.FIREWORK_ROCKET ||
                        MinecraftContext.player().getInventory().getStack(slot).getCount() == 64) {
                        if (replaceList.isEmpty())
                            return;
                        slot = replaceList.removeFirst();
                    }
                    MinecraftContext.interactionManager().clickSlot(handler.syncId, i, 0, SlotActionType.PICKUP, MinecraftContext.player());
                    MinecraftContext.interactionManager().clickSlot(handler.syncId, 18 + slot, 0, SlotActionType.PICKUP, MinecraftContext.player());
                    MinecraftContext.interactionManager().clickSlot(handler.syncId, i, 0, SlotActionType.PICKUP, MinecraftContext.player());

                    ItemStack newStack = handler.getSlot(i).getStack();
                    if (newStack.getItem() == Items.FIREWORK_ROCKET) {
                        if (oldCount == newStack.getCount())
                            throw new RuntimeException("拿取烟花失败");
                        exist[0] += Math.min(oldCount - newStack.getCount(), 0);
                        i--;
                    } else {
                        exist[0] += oldCount;
                    }
                }
            }
        });
    }

    private static void mineBlock(BlockPos pos, int maxTicks) {
        Block oldBlock = MinecraftContext.world().getBlockState(pos).getBlock();
        Rotation rotation = Rotation.calcRotationFromVec3d(MinecraftContext.player().getEyePos(), pos.toCenterPos());

        RaycastContext context = new RaycastContext(
                MinecraftContext.player().getEyePos(),
                pos.toCenterPos(),
                RaycastContext.ShapeType.OUTLINE,
                RaycastContext.FluidHandling.NONE, ShapeContext.absent()
        );

        BlockHitResult hitResult = MinecraftContext.world().raycast(context);
        Direction side;
        if (hitResult.getType() == HitResult.Type.BLOCK && hitResult.getBlockPos().equals(pos)) {
            side = hitResult.getSide();
        } else {
            throw new RuntimeException("无法触碰到目标方块");
        }

        int slot = findItemInHotBar(Items.DIAMOND_PICKAXE);
        if (slot == -1) {
            slot = findItemInHotBar(Items.NETHERITE_PICKAXE);
            if (slot == -1)
                throw new RuntimeException("没有找到镐子");
        }
        int finalSlot = slot;
        int ticks = 0;
        while (MinecraftContext.world().getBlockState(pos).getBlock() == oldBlock) {
            delay(1);
            if (isHittingFireball()) {
                ticks = 0;
                continue;
            }
            runOnMain(() -> {
                MinecraftContext.player().setPitch(rotation.getPitch());
                MinecraftContext.player().setYaw(rotation.getYaw());
                MinecraftContext.player().getInventory().setSelectedSlot(finalSlot);
                MinecraftContext.networkHandler().sendPacket(new UpdateSelectedSlotC2SPacket(finalSlot));
                MinecraftContext.interactionManager().updateBlockBreakingProgress(pos, side);
            });
            if (ticks++ > maxTicks)
                throw new RuntimeException("挖掘失败");
        }
    }

    /**
     * 挖掘用过的潜影盒
     *
     * @param ShulkerPos 潜影盒位置
     */
    private static int mineSupplyShulker(BlockPos ShulkerPos, HandledScreen<?> shulkerHandled) {
        PlayerInventory inventory = MinecraftContext.player().getInventory();
        runOnMain(shulkerHandled::close);
        delay(12);
        WalkToTakeoff.walkPath(List.of(new BetterBlockPos(MinecraftContext.player().getBlockPos()), new BetterBlockPos(ShulkerPos).up()));
        // 等待baritone挖掘
        mineBlock(ShulkerPos, 40);

        int[] inv = new int[36];
        for (int i = 0; i < 36; i++) {
            Item item = inventory.getStack(i).getItem();
            if (item instanceof BlockItem blockItem && blockItem.getBlock() instanceof ShulkerBoxBlock ||
                item == Items.ENDER_CHEST || item == Items.DIAMOND_PICKAXE ||
                item == Items.NETHERITE_PICKAXE || item == Items.DIAMOND_SWORD ||
                item == Items.NETHERITE_SWORD || item == Food ||
                item == Items.TOTEM_OF_UNDYING) {
                inv[i] = 1;
            } else {
                inv[i] = 0;
            }
        }
        // 等待捡起潜影盒

        for (int ticks = 0; ; ticks++) {
            if (ticks > 29)
                throw new TaskException("挖掘补给箱失败!");
            boolean hasEmpty = false;
            for (int i = 0; i < 36; i++) {
                if (inventory.getStack(i).getItem() instanceof BlockItem blockItem && blockItem.getBlock() instanceof ShulkerBoxBlock &&
                    inv[i] == 0) {
                    if (i < 9)
                        return 36 + i;
                    else
                        return i;
                } else if (inventory.getStack(i).isEmpty()) {
                    hasEmpty = true;
                }
            }

            if (!hasEmpty) {
                for (int j = 0; j < 36; j++) {
                    if (!inventory.getStack(j).isEmpty() && inv[j] == 0) {
                        if (j < 9)
                            MinecraftContext.interactionManager().clickSlot(0, 36 + j, 1, SlotActionType.THROW, MinecraftContext.player());
                        else
                            MinecraftContext.interactionManager().clickSlot(0, j, 1, SlotActionType.THROW, MinecraftContext.player());
                        break;
                    }
                }
            }
            delay(1);
        }
    }

    /**
     * 挖掘末影箱
     *
     * @param EnderChestPos 末影箱位置
     */
    private static void mineEnderChest(BlockPos EnderChestPos) {
        int enderCount = countItemInInventory(Items.ENDER_CHEST);

        runOnMain(() -> MinecraftContext.client().setScreen(null));
        delay(5);
        WalkToTakeoff.walkPath(List.of(new BetterBlockPos(MinecraftContext.player().getBlockPos()), new BetterBlockPos(EnderChestPos).up()));
        mineBlock(EnderChestPos, 100);
        delay(10);
        for (int ticks = 0; countItemInInventory(Items.ENDER_CHEST) <= enderCount; ticks++) {
            if (ticks > 30)
                throw new TaskException("挖掘末影箱失败!");
            delay(1);
        }
        msg.SendMsg("挖掘完毕", MsgLevel.tip);
    }

    /**
     * 等待当前界面变为正确容器界面
     *
     * @param ScreenName 容器名称
     * @return 正确的界面handled
     */
    private static @NotNull HandledScreen<?> WaitForScreen(@NotNull String ScreenName) {
        HandledScreen<?> handled = null;

        // 等待界面
        for (int i = 0; i < 20; i++) {
            HandledScreen<?> temp = ContainerScreenChecker(ScreenName);
            if (temp != null) {
                handled = temp;
                break;
            }
            delay(1);
        }
        if (handled == null)
            throw new TaskException(ScreenName + "疑似打开失败");
        return handled;
    }

    private static int FireworkSupplyChecker() {
        int num = 0;
        for (int i = 9; i < 36; i++) {
            ItemStack s = MinecraftContext.player().getInventory().getStack(i);
            if (s.getItem() == Items.FIREWORK_ROCKET)
                num += 1;
        }
        return num;
    }


    public static @NotNull List<Pair<Integer, int[]>> ComputeShulker(int FireworkCount, int FoodCount, int TotemCount, int[] @NotNull [] ShulkerData) {
        int totalBoxes = ShulkerData.length;
        int fireworkExist = 0;
        int foodExist = 0;
        int totemExist = 0;
        List<Pair<Integer, int[]>> fireworkList = new ArrayList<>();
        List<Pair<Integer, int[]>> result = new ArrayList<>();
        for (int i = 0; i < totalBoxes; i++) {
            fireworkList.add(new Pair<>(i, ShulkerData[i]));
        }
        fireworkList.sort((p1, p2) -> p2.second()[0] - p1.second()[0]);
        for (Pair<Integer, int[]> pair : fireworkList) {
            if (fireworkExist >= FireworkCount)
                break;
            int[] shulkerResult = new int[3];
            shulkerResult[0] = Math.min(pair.second()[0], FireworkCount - fireworkExist);
            shulkerResult[1] = Math.min(pair.second()[1], FoodCount - foodExist);
            shulkerResult[2] = Math.min(pair.second()[2], TotemCount - totemExist);
            fireworkExist += shulkerResult[0];
            foodExist += shulkerResult[1];
            totemExist += shulkerResult[2];
            result.add(new Pair<>(pair.first(), shulkerResult));
        }

        if (fireworkExist < FireworkCount)
            throw new RuntimeException("末影箱内烟花不足,请补充!");

        Set<Integer> usedIndex = result.stream().map(Pair::first).collect(Collectors.toSet());
        int maxFoodIndex = IntStream.range(0, totalBoxes)
                .filter(i -> !usedIndex.contains(i)).reduce((i, j) -> ShulkerData[i][1] > ShulkerData[j][1] ? i : j).orElse(-1);
        int maxTotemIndex = IntStream.range(0, totalBoxes)
                .filter(i -> !usedIndex.contains(i)).reduce((i, j) -> ShulkerData[i][2] > ShulkerData[j][2] ? i : j).orElse(-1);
        if ((maxFoodIndex == -1 || ShulkerData[maxFoodIndex][1] < FoodCount - foodExist) && FoodCount > 0)
            throw new RuntimeException("末影箱内" + Food.getName().getString() + "不足,或者摆放过于杂乱,请尽量集中到一个盒子内");
        if (maxTotemIndex == -1 || ShulkerData[maxTotemIndex][2] < TotemCount - totemExist && TotemCount > 0)
            throw new RuntimeException("末影箱内图腾不足,或者摆放过于杂乱,请尽量集中到一个盒子内");


        int m = Math.min(ShulkerData[maxFoodIndex][1], FoodCount - foodExist);
        int n = Math.min(ShulkerData[maxTotemIndex][2], TotemCount - totemExist);
        if (maxFoodIndex == maxTotemIndex) {
            if (m > 0 || n > 0)
                result.add(new Pair<>(maxFoodIndex, new int[]{0, m, n}));
        } else {
            if (m > 0)
                result.add(new Pair<>(maxFoodIndex, new int[]{0, m, 0}));
            if (n > 0)
                result.add(new Pair<>(maxTotemIndex, new int[]{0, 0, n}));
        }
        return result;
    }

    /**
     * 补给主函数
     *
     * @throws TaskException 通过抛出异常中断
     */
    public static void supplyTask() {
        status = TaskStatus.SUPPLY;
        Food = FoodList[FoodIndex.get()];

        timerMultiplier = 1;
        // 首先走到方块中央
        WalkingToCenter();
        delay(2);
        // 整理物品栏
        SortAndCheckInv();
        delay(2);

        if (findPlaceTarget() == null)
            throw new RuntimeException("附近有方块,请前往平坦地带");


        int FireworkInNeed = Math.max(26 * 64 - FireworkSupplyChecker() * 64, 0);

        int TotemInNeed = (MinecraftContext.player().getInventory().getStack(3).getItem() == Items.TOTEM_OF_UNDYING ? 0 : 1) +
                          (MinecraftContext.player().getInventory().getStack(4).getItem() == Items.TOTEM_OF_UNDYING ? 0 : 1);
        int FoodInNeed = MinecraftContext.player().getInventory().getStack(5).getItem() == Food ?
                Math.max(32 - MinecraftContext.player().getInventory().getStack(5).getCount(), 0) : 32;

        if (FireworkInNeed == 0 && FoodInNeed == 0 && TotemInNeed == 0)
            return;

        msg.SendMsg(String.format("所需补给: %d个烟花,%d个%s,%d个不死图腾", FireworkInNeed, FoodInNeed,
                Food.getName().getString(), TotemInNeed), MsgLevel.info);

        // 扑灭身边火焰
        extinguishFire();

        // 寻找末影箱
        int slot = findItemInHotBar(Items.ENDER_CHEST);
        if (slot == -1)
            throw new RuntimeException("无末影箱,请补充");
        String EnderChestName = MinecraftContext.player().getInventory().getStack(slot).getName().getString();

        // 寻找放置地点
        BlockPos EnderChestTargetPos = findPlaceTarget();
        if (EnderChestTargetPos == null)
            throw new RuntimeException("附近没有合适的位置放置末影箱");

        // 放置并打开末影箱
        PlaceAndOpenContainer(EnderChestTargetPos, slot);
        delay(1);

        // 等待末影箱界面
        HandledScreen<?> EnderChestHandled = WaitForScreen(EnderChestName);

        int[][] ShulkerData = SupplyShulkerFinder(EnderChestHandled);

        List<Pair<Integer, int[]>> ShulkerList = ComputeShulker(FireworkInNeed, FoodInNeed, TotemInNeed, ShulkerData);

        msg.SendMsg("所需的潜影盒槽位列表为：" + ShulkerList, MsgLevel.info);
        List<Integer> replaceSlot = new ArrayList<>();
        for (int i = 9; i < 36; i++) {
            ItemStack s = MinecraftContext.player().getInventory().getStack(i);
            if (s.getItem() != Items.FIREWORK_ROCKET &&
                !(s.getItem() instanceof BlockItem blockItem && blockItem.getBlock() instanceof ShulkerBoxBlock))
                replaceSlot.add(i);
        }
        msg.SendMsg("可替换列表为" + replaceSlot, MsgLevel.debug);
        if (replaceSlot.size() * 64 < FireworkInNeed)
            throw new RuntimeException("你发生了不可能发生的错误,请检查代码");
        delay(1);
        for (Pair<Integer, int[]> Supply : ShulkerList) {
            // 等待末影箱窗口
            EnderChestHandled = WaitForScreen(EnderChestName);


            if (Supply.first() > 26 || Supply.first() < 0)
                throw new RuntimeException("所需槽位异常,请联系开发者");
            else
                msg.SendMsg("准备拿出" + Supply.first(), MsgLevel.tip);
            // 找可以用来放潜影盒的槽位
            slot = -1;
            for (int j = 6; j < 9; j++) {
                ItemStack stack2 = MinecraftContext.player().getInventory().getStack(j);
                if (stack2.isEmpty() || stack2.getItem() != Items.ENDER_CHEST && stack2.getItem() != Items.DIAMOND_PICKAXE &&
                                        stack2.getItem() != Items.NETHERITE_PICKAXE && stack2.getItem() != Items.DIAMOND_SWORD &&
                                        stack2.getItem() != Items.NETHERITE_SWORD && stack2.getItem() != Food &&
                                        stack2.getItem() != Items.TOTEM_OF_UNDYING) {
                    slot = j;
                    break;
                }
            }
            if (slot == -1)
                throw new TaskException("没有快捷栏位置可以用于取出潜影盒");

            // 取出潜影盒
            int ShulkerSlot = slot;
            HandledScreen<?> finalEnderChestHandled = EnderChestHandled;
            runOnMain(() -> {
                MinecraftContext.interactionManager()
                        .clickSlot(finalEnderChestHandled.getScreenHandler().syncId, Supply.first(), 0, SlotActionType.PICKUP,
                                MinecraftContext.player());
                MinecraftContext.interactionManager()
                        .clickSlot(finalEnderChestHandled.getScreenHandler().syncId, 54 + ShulkerSlot, 0, SlotActionType.PICKUP,
                                MinecraftContext.player());
                MinecraftContext.interactionManager()
                        .clickSlot(finalEnderChestHandled.getScreenHandler().syncId, Supply.first(), 0, SlotActionType.PICKUP,
                                MinecraftContext.player());
                finalEnderChestHandled.close();
            });
            msg.SendMsg("取出成功！", MsgLevel.tip);

            delay(5);

            // 找潜影盒名称
            ItemStack ShulkerStack = MinecraftContext.player().getInventory().getStack(ShulkerSlot);
            String ShulkerName = ShulkerStack.getComponents().contains(DataComponentTypes.CUSTOM_NAME) ? // 潜影盒名称为“潜影盒”或自定义名称
                    Objects.requireNonNull(ShulkerStack.get(DataComponentTypes.CUSTOM_NAME)).getString() : // 自定义名称
                    Items.SHULKER_BOX.getName().getString(); // “潜影盒”

            // 找空位放置潜影盒
            BlockPos ShulkerTargetPos = findPlaceTarget();
            if (ShulkerTargetPos == null)
                throw new TaskException("附近没有合适的位置放置潜影盒");

            // 放置并打开潜影盒
            PlaceAndOpenContainer(ShulkerTargetPos, ShulkerSlot);
            delay(1);

            // 等待潜影盒窗口
            HandledScreen<?> ShulkerHandled = WaitForScreen(ShulkerName);
            // 拿出补给
            PutOutSupply(ShulkerHandled.getScreenHandler(), replaceSlot, Supply.second());
            msg.SendMsg("取出补给物品成功", MsgLevel.tip);
            // 取出成功，挖掉潜影盒
            int newShulkerSlot = mineSupplyShulker(ShulkerTargetPos, ShulkerHandled) + 18;

            msg.SendMsg("挖掘完毕，放回末影箱", MsgLevel.tip);

            // 重新打开末影箱
            runOnMain(() -> lookAt(Vec3d.ofCenter(EnderChestTargetPos)));
            delay(2);
            OpenContainer(EnderChestTargetPos);

            // 等待末影箱窗口
            EnderChestHandled = WaitForScreen(EnderChestName);

            // 放回潜影盒
            HandledScreen<?> finalEnderChestHandled1 = EnderChestHandled;
            runOnMain(() -> {
                msg.SendMsg(String.valueOf(newShulkerSlot), MsgLevel.warning);
                MinecraftContext.interactionManager()
                        .clickSlot(finalEnderChestHandled1.getScreenHandler().syncId, newShulkerSlot, 0, SlotActionType.PICKUP,
                                MinecraftContext.player());
                MinecraftContext.interactionManager()
                        .clickSlot(finalEnderChestHandled1.getScreenHandler().syncId, Supply.first(), 0, SlotActionType.PICKUP,
                                MinecraftContext.player());
                MinecraftContext.interactionManager()
                        .clickSlot(finalEnderChestHandled1.getScreenHandler().syncId, newShulkerSlot, 0, SlotActionType.PICKUP,
                                MinecraftContext.player());
            });
            msg.SendMsg("放回完毕", MsgLevel.tip);
            delay(1);
        }
        runOnMain(() -> MinecraftContext.client().setScreen(null));
        // 挖掘末影箱
        mineEnderChest(EnderChestTargetPos);
        BlockPos tmp = findPlaceTarget();
        if (tmp != null) {
            Rotation rotation = Rotation.calcRotationFromVec3d(MinecraftContext.player().getEyePos(), tmp.toCenterPos());
            MinecraftContext.player().setPitch(0);
            MinecraftContext.player().setYaw(rotation.getYaw());
        }
        msg.SendMsg("补给任务圆满完成！", MsgLevel.tip);
    }
}
