package dev.rstminecraft.elytra;

import net.minecraft.client.MinecraftClient;
import net.minecraft.client.network.ClientPlayerEntity;
import net.minecraft.component.DataComponentTypes;
import net.minecraft.entity.EquipmentSlot;
import net.minecraft.entity.effect.StatusEffects;
import net.minecraft.item.Item;
import net.minecraft.item.ItemStack;
import net.minecraft.item.Items;
import net.minecraft.network.packet.c2s.play.ClientCommandC2SPacket;
import net.minecraft.screen.PlayerScreenHandler;
import net.minecraft.screen.slot.SlotActionType;

/**
 * 像SlimeClient开发团队致谢!这部分代码是DeepSeek参考SlimeClient写出的
 */
public class infinityElytra {

    // ========== 配置 ==========
    public volatile static boolean enabled = true;
    public final static int period = 16;
    public final static boolean resetVanilla = true;

    // ========== 状态机 ==========
    public volatile static int durabilityCounter = 0;
    public volatile static boolean transactionPending = false;
    public volatile static int swapSlotId = -1;

    /**
     * POSE 宽限期（tick）。
     * 事务完成后，服务端可能延迟下发 POSE 更新包，
     * 在宽限期内继续强制 POSE = FALL_FLYING。
     * 等价于原项目的 nextPacketResetFallFlying 队列机制。
     */
    public static int poseLockTicks = 0;


    // ========== 条件判断 ==========

    public static boolean isResettingElytra(){
        return swapSlotId != -1;
    }

    public static boolean shouldUnbreakable(ClientPlayerEntity player) {
        if (!enabled || player == null)
            return false;
        ItemStack chestStack = player.getEquippedStack(EquipmentSlot.CHEST);
        if (chestStack.get(DataComponentTypes.UNBREAKABLE) != null)
            return false;
        return chestStack.isOf(Items.ELYTRA);
    }

    public static boolean canContinueGliding(ClientPlayerEntity player) {
        return !player.isTouchingWater()
               && !player.getAbilities().flying
               && !player.isOnGround()
               && !player.hasVehicle()
               && !player.hasStatusEffect(StatusEffects.LEVITATION);
    }

    // ========== 物品栏交换 ==========

    public static int findEmptySlotForElytra(ClientPlayerEntity player) {
        PlayerScreenHandler handler = player.playerScreenHandler;
        for (int i = 44; i >= 9; i--) {
            if (handler.getSlot(i).getStack().isEmpty())
                return i;
        }
        for (int i = 44; i >= 9; i--) {
            ItemStack stack = handler.getSlot(i).getStack();
            if (!stack.isOf(Items.ELYTRA) && player.canEquip(stack, EquipmentSlot.CHEST)) {
                return i;
            }
        }
        return 44;
    }

    public static void swapChestWithSlot(ClientPlayerEntity player, int targetSlot) {
        MinecraftClient client = MinecraftClient.getInstance();
        if (client.interactionManager == null)
            return;
        PlayerScreenHandler handler = player.playerScreenHandler;
        client.interactionManager.clickSlot(handler.syncId, 6, 0, SlotActionType.PICKUP, player);
        client.interactionManager.clickSlot(handler.syncId, targetSlot, 0, SlotActionType.PICKUP, player);
        if (!player.playerScreenHandler.getCursorStack().isEmpty()) {
            client.interactionManager.clickSlot(handler.syncId, 6, 0, SlotActionType.PICKUP, player);
        }
    }

    // ========== 核心事务 ==========

    public static void startTransaction(ClientPlayerEntity player) {
        swapSlotId = findEmptySlotForElytra(player);
        if (swapSlotId != -1) {
            swapChestWithSlot(player, swapSlotId);
        } else {
            player.networkHandler.sendPacket(
                    new ClientCommandC2SPacket(player, ClientCommandC2SPacket.Mode.START_FALL_FLYING));
        }
        transactionPending = true;
        poseLockTicks = 5;  // 开启 5 tick 宽限期
        durabilityCounter = 0;
    }

    public static void finishTransaction(ClientPlayerEntity player) {
        if (swapSlotId != -1) {
            swapChestWithSlot(player, swapSlotId);
            swapSlotId = -1;
        }
        player.networkHandler.sendPacket(
                new ClientCommandC2SPacket(player, ClientCommandC2SPacket.Mode.START_FALL_FLYING));
        transactionPending = false;
        // poseLockTicks 不清零，继续保护后续 POSE 包
    }

    /**
     * 每 tick 调用，递减宽限期
     */
    public static void tickPoseLock() {
        if (poseLockTicks > 0) {
            poseLockTicks--;
        }
    }
}

