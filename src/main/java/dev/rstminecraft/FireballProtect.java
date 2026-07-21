package dev.rstminecraft;

import baritone.api.BaritoneAPI;
import dev.rstminecraft.RustClientTemplate.ModTaskManager;
import dev.rstminecraft.RustClientTemplate.MsgLevel;
import net.minecraft.client.MinecraftClient;
import net.minecraft.entity.EntityType;
import net.minecraft.entity.attribute.EntityAttributes;
import net.minecraft.entity.projectile.FireballEntity;
import net.minecraft.network.packet.c2s.play.PlayerInteractEntityC2SPacket;
import net.minecraft.util.Hand;
import net.minecraft.util.math.Box;
import net.minecraft.util.math.Vec3d;
import org.jetbrains.annotations.NotNull;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.UUID;

import static dev.rstminecraft.RustElytraClient.msg;

class FireballProtect {
    private static final HashSet<UUID> ignoreFireball = new HashSet<>();
    private static boolean flag = false;

    /**
     * 返回是否正在应对恶魂火球
     *
     * @return 是否正在应对恶魂火球
     */
    static boolean isHittingFireball() {
        return flag;
    }

    /**
     * 获取玩家附近的恶魂火球
     *
     * @param client 客户端对象
     * @return 火球列表
     */
    private static List<FireballEntity> getNearbyFireball(@NotNull MinecraftClient client) {
        if (client.player == null || client.world == null || client.interactionManager == null) {
            return new ArrayList<>();
        }
        Vec3d playerPos = client.player.getEntityPos();
        double Range = client.player.getAttributeValue(EntityAttributes.ENTITY_INTERACTION_RANGE) + 0.8;
        Box detectionBox = new Box(playerPos.x - Range, playerPos.y - Range, playerPos.z - Range, playerPos.x + Range,
                                   playerPos.y + Range, playerPos.z + Range);
        return client.world.getEntitiesByType(EntityType.FIREBALL, detectionBox, entity -> true);
    }

    /**
     * 用于保护客户端，自动打回恶魂火球
     *
     * @param client 客户端实体
     * @return 保护是否成功
     */
    static boolean FireballProtector(@NotNull MinecraftClient client) {
        List<FireballEntity> l = getNearbyFireball(client);
        if (l.isEmpty()) {
            if (flag) {
                flag = false;
                ignoreFireball.clear();
                BaritoneAPI.getProvider().getPrimaryBaritone().getCommandManager().execute("r");
            }
            return true;
        }
        if (client.player != null && client.interactionManager != null && client.getNetworkHandler() != null) {
            if (l.size() > 1)
                return false;
            FireballEntity fireball = l.getFirst();
            if (ignoreFireball.contains(fireball.getUuid()))
                return true;
            BaritoneAPI.getProvider().getPrimaryBaritone().getCommandManager().execute("p");
            flag = true;
            ignoreFireball.add(fireball.getUuid());
            Vec3d target = fireball.getEntityPos().add(0, 0.5, 0);
            Vec3d eyePos = client.player.getEyePos();
            double dx = target.x - eyePos.x;
            double dy = target.y - eyePos.y;
            double dz = target.z - eyePos.z;
            double horizontalDistance = Math.sqrt(dx * dx + dz * dz);
            float yaw = (float) (Math.atan2(dz, dx) * 180 / Math.PI) - 90;
            float pitch = (float) (-Math.atan2(dy, horizontalDistance) * 180 / Math.PI);
            client.player.setYaw(yaw);
            client.player.setPitch(pitch);
            msg.SendMsg(client.player, "准备拦截火球！", MsgLevel.warning);
            ModTaskManager.startThread(() -> {
                ModTaskManager.delay(1);
                ModTaskManager.runOnMainSync(() -> {
                    client.interactionManager.attackEntity(client.player, fireball);
                    PlayerInteractEntityC2SPacket attackPacket = PlayerInteractEntityC2SPacket.attack(fireball,
                                                                                                      client.player.isSneaking());
                    client.getNetworkHandler().sendPacket(attackPacket);
                    client.player.swingHand(Hand.MAIN_HAND);
                });
            });
            return true;
        }
        return false;
    }
}
