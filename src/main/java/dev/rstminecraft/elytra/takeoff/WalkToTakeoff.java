package dev.rstminecraft.elytra.takeoff;

import dev.rstminecraft.RustClientCore.MinecraftContext;
import dev.rstminecraft.RustClientCore.messenger.MsgLevel;
import dev.rstminecraft.RustClientCore.task.TaskManager;
import dev.rstminecraft.RustClientCore.task.TickPhase;
import dev.rstminecraft.RustClientCore.utils.BetterBlockPos;
import dev.rstminecraft.utils.SilentRotation;
import net.minecraft.util.math.Vec3d;

import java.util.List;

import static dev.rstminecraft.FireballProtect.isHittingFireball;
import static dev.rstminecraft.RustClientCore.task.TaskManager.delay;
import static dev.rstminecraft.RustElytraClient.msg;
import static dev.rstminecraft.RustElytraClient.paused;

public class WalkToTakeoff {

    public static double getChebyshevDistance(Vec3d pos1, Vec3d pos2) {
        double dx = Math.abs(pos1.getX() - pos2.getX());
        double dy = Math.abs(pos1.getY() - pos2.getY());
        double dz = Math.abs(pos1.getZ() - pos2.getZ());

        // 核心：求三个轴向差绝对值的最大值
        return Math.max(dx, Math.max(dy, dz));
    }

    public static void walkPath(List<BetterBlockPos> path) {
        if (path == null || path.size() < 2)
            return;

        try {

            float preHealth = MinecraftContext.player().getHealth();

            for (int i = 0; i < path.size() - 1; i++) {
                BetterBlockPos current = path.get(i);
                BetterBlockPos next = path.get(i + 1);

                // 计算面朝下一个点的偏航角
                double dx = next.toBottomCenterPos().getX() - MinecraftContext.player().getX();
                double dz = next.toBottomCenterPos().getZ() - MinecraftContext.player().getZ();
                float yaw = (float) Math.toDegrees(Math.atan2(-dx, dz));

                SilentRotation.setRotation(yaw, 0);

                // 判断是否需要跳跃（向上走）
                boolean needJump = next.y > current.y;

                boolean jumped = false;
                // 按住前进键
                MinecraftContext.client().options.forwardKey.setPressed(true);

                boolean falling = next.y < current.y - 1;


                // 等待玩家到达下一个点
                int ticksWaited = 0;
                int maxTicks = 80;
                while (getChebyshevDistance(MinecraftContext.player().getEntityPos(), next.toBottomCenterPos()) >
                       (falling ? 1 : 0.2) && ticksWaited < maxTicks) {
                    delay(1);
                    if(paused||isHittingFireball())
                        continue;
                    ticksWaited++;
                    msg.SendMsg(next.toShortString()
                                + " " + getChebyshevDistance(MinecraftContext.player().getEntityPos(), next.toBottomCenterPos()), MsgLevel.debug);

                    dx = next.toBottomCenterPos().getX() - MinecraftContext.player().getX();
                    dz = next.toBottomCenterPos().getZ() - MinecraftContext.player().getZ();
                    yaw = (float) Math.toDegrees(Math.atan2(-dx, dz));
                    SilentRotation.setRotation(yaw, 0);

                    MinecraftContext.client().options.forwardKey.setPressed(MinecraftContext.player().isOnGround() || needJump);

                    if (needJump && !jumped && next.toBottomCenterPos().isInRange(MinecraftContext.player().getEntityPos().add(0, 1, 0), 0.85)) {
                        MinecraftContext.client().options.jumpKey.setPressed(true);
                        TaskManager.runDeferred(() -> MinecraftContext.client().options.jumpKey.setPressed(false), TickPhase.POST, 0);
                        jumped = true;
                    }

                    if(MinecraftContext.player().getHealth() < preHealth)
                        throw new RuntimeException("WalkToTakeoff: 移动到 " + next + " 时掉血");
                }

                if (ticksWaited >= maxTicks) {
                    MinecraftContext.client().options.forwardKey.setPressed(false);
                    throw new RuntimeException("WalkToTakeoff: 移动到 " + next + " 超时");
                }
            }
        } finally {
            // 确保释放所有移动键
            MinecraftContext.client().options.forwardKey.setPressed(false);
            MinecraftContext.client().options.jumpKey.setPressed(false);
        }
    }
}
