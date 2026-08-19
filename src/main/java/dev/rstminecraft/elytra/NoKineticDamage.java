package dev.rstminecraft.elytra;

import net.minecraft.client.network.ClientPlayerEntity;
import net.minecraft.entity.Entity;
import net.minecraft.entity.projectile.FireworkRocketEntity;
import net.minecraft.util.math.MathHelper;
import net.minecraft.util.math.Vec3d;

public final class NoKineticDamage {
    public static boolean enabled = true;

    // 翻转持续计数（对应原 triggerKinetic）
    private static int triggerKinetic = 0;
    // 最近一次玩家发射的烟花（用于判断 canControl）
    private static FireworkRocketEntity lastFireworkRocket;

    // 本 tick 是否翻转了朝向、以及原始朝向（用于 tick 末恢复）
    private static boolean resetRot = false;
    private static float savedPitch;
    private static float savedYaw;

    private NoKineticDamage() {}

    /** 在 PlayerEntity#travel 的 HEAD 调用 */
    public static void onTravelHead(ClientPlayerEntity player) {
        if (!enabled) {
            return;
        }
        if (!player.isGliding()) {
            return;
        }

        // 烟花加速进行中 => 服务端按朝向向量驱动运动（可控制 pitch）
        boolean canControl = lastFireworkRocket != null && lastFireworkRocket.isAlive();

        // BYPASS_GRIM：用速度 + 碰撞模拟预测是否会撞墙
        Vec3d vec = player.getVelocity().multiply(4.0);
        Vec3d sim = simulateServerMovement(player, vec);

        // 注意：这里保持原版的运算符优先级 (len>0.3 && x不同) || z不同
        if (vec.horizontalLength() > 0.3 && !MathHelper.approximatelyEquals(sim.x, vec.x)
            || !MathHelper.approximatelyEquals(sim.z, vec.z)) {
            flipRotation(player, canControl);
            triggerKinetic = 2;
        } else if (triggerKinetic > 0) {
            triggerKinetic--;
            flipRotation(player, canControl);
        }
    }

    /** 在 ClientPlayerEntity#tick 的 TAIL（移动包已发出后）调用，恢复朝向 */
    public static void onTickTail(ClientPlayerEntity player) {
        if (resetRot) {
            resetRot = false;
            player.setPitch(savedPitch);
            player.setYaw(savedYaw);
        }
    }

    /** 玩家发射的烟花实体进入追踪 */
    public static void onFireworkSpawn(FireworkRocketEntity firework) {
        lastFireworkRocket = firework;
    }

    /** 切换世界/断开连接时清理 */
    public static void onWorldChanged() {
        lastFireworkRocket = null;
        triggerKinetic = 0;
        resetRot = false;
    }

    private static void flipRotation(ClientPlayerEntity player, boolean canControl) {
        if (!resetRot) {
            savedPitch = player.getPitch();
            savedYaw = player.getYaw();
            resetRot = true;
        }
        if (canControl) {
            setPitchSafe(player, -90f + 1e-3f);
            setYawSafe(player, player.getYaw() + 180f);
        } else {
            setYawSafe(player, player.getYaw() + 180f);
        }
    }

    // ---- 服务端移动/碰撞模拟（非破坏性，等价于原 collide 的水平判定）----
    // 动能伤害只来自撞墙（FLY_INTO_WALL），所以只做方块碰撞即可。
    // 如需同时考虑实体碰撞，可改用三参重载：
    //   entity.adjustMovementForCollisions(delta, box, world.getEntityCollisions(entity, box.stretch(delta)))
    private static Vec3d simulateServerMovement(Entity entity, Vec3d delta) {
        return entity.adjustMovementForCollisions(delta);
    }

    // ---- 朝向安全写入（避免越过 ±180 / ±90 的跳变）----
    private static float yawDiff(float oldYaw, float newYaw) {
        float diff = newYaw - oldYaw;
        return (diff % 360f + 720f + 180f) % 360f - 180f;
    }

    private static float safeYaw(float oldYaw, float newYaw) {
        return oldYaw + yawDiff(oldYaw, newYaw);
    }

    private static float safePitch(float pitch) {
        if (pitch < 90f + 1e-5f && pitch > -90f - 1e-5f) {
            return pitch;
        }
        return (pitch % 180f + 720f + 90f) % 180f - 90f;
    }

    private static void setYawSafe(Entity e, float newYaw) {
        e.setYaw(safeYaw(e.getYaw(), newYaw));
    }

    private static void setPitchSafe(Entity e, float newPitch) {
        e.setPitch(safePitch(newPitch));
    }
}
