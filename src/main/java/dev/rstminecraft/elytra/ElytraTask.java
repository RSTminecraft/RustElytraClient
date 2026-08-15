package dev.rstminecraft.elytra;

import dev.rstminecraft.RustClientCore.MinecraftContext;
import dev.rstminecraft.RustClientCore.eventListener.PacketListener;
import dev.rstminecraft.RustClientCore.messenger.MsgLevel;
import dev.rstminecraft.RustClientCore.renderer.BoxRenderer;
import dev.rstminecraft.RustClientCore.renderer.TrajectoryRenderer;
import dev.rstminecraft.RustClientCore.task.TaskManager;
import dev.rstminecraft.RustClientCore.task.TickPhase;
import dev.rstminecraft.RustClientCore.utils.BetterBlockPos;
import dev.rstminecraft.elytra.AngleSolver.FireworkBoost;
import dev.rstminecraft.takeoff.TakeoffTargetFinder;
import dev.rstminecraft.utils.SilentRotation;
import dev.rstminecraft.utils.TimelinessCounter;
import net.minecraft.client.gui.screen.ingame.HandledScreen;
import net.minecraft.client.gui.screen.ingame.InventoryScreen;
import net.minecraft.entity.Entity;
import net.minecraft.entity.LivingEntity;
import net.minecraft.entity.player.PlayerInventory;
import net.minecraft.entity.projectile.FireworkRocketEntity;
import net.minecraft.item.Item;
import net.minecraft.item.ItemStack;
import net.minecraft.item.Items;
import net.minecraft.network.packet.c2s.play.UpdateSelectedSlotC2SPacket;
import net.minecraft.network.packet.s2c.play.PlayerPositionLookS2CPacket;
import net.minecraft.screen.ScreenHandler;
import net.minecraft.screen.slot.Slot;
import net.minecraft.screen.slot.SlotActionType;
import net.minecraft.util.Hand;
import net.minecraft.util.math.BlockPos;
import net.minecraft.util.math.Vec3d;

import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.stream.StreamSupport;

import static dev.rstminecraft.RustClientCore.task.TaskManager.*;
import static dev.rstminecraft.RustElytraClient.*;
import static dev.rstminecraft.takeoff.WalkToTakeoff.walkPath;

public class ElytraTask {

    private final NetherPathfinderContext npf;
    private final BlockStateUtils bsu;
    private final PathManager pathManager;
    private final AngleSolver angleSolver;

    public ElytraTask(BlockPos destination) {
        npf = new NetherPathfinderContext(netherSeed.get());
        bsu = new BlockStateUtils(npf, MinecraftContext.world());
        pathManager = new PathManager(new BetterBlockPos(destination), npf, bsu);
        angleSolver = new AngleSolver(npf, pathManager, bsu);
    }

    public static LivingEntity getBoostedEntity(FireworkRocketEntity rocket) {
        if (rocket.wasShotByEntity() && rocket.shooter == null) {
            Entity e = rocket.getEntityWorld().getEntityById(
                    rocket.getDataTracker().get(FireworkRocketEntity.SHOOTER_ENTITY_ID).getAsInt()
            );
            if (e instanceof LivingEntity le) {
                rocket.shooter = le;
            }
        }
        return rocket.shooter;
    }

    private void jump() {
        MinecraftContext.client().options.jumpKey.setPressed(true);
        TaskManager.runDeferred(() -> MinecraftContext.client().options.jumpKey.setPressed(false), TickPhase.POST, 0);
    }

    private void takeoff() {
        double preY = MinecraftContext.player().getY();
        jump();
        for (int i = 0; MinecraftContext.player().getY() > preY + 1; i++) {
            if (i > 20)
                throw new RuntimeException("起跳高度错误");
            delay(1);
        }
        jump();
        delay(1);
    }

    private void gotoTakeoff() {
        TakeoffTargetFinder tff = new TakeoffTargetFinder(angleSolver, npf, bsu, pathManager);
        CompletableFuture<List<BetterBlockPos>> pathFuture =
                CompletableFuture.supplyAsync(() -> tff.findPath(new BetterBlockPos(MinecraftContext.player().getBlockPos())));
        for (int i = 0; !pathFuture.isDone(); i++) {
            if (i > 50)
                throw new RuntimeException("计算起跳路径超时");
            delay(1);
        }
        List<BetterBlockPos> path = pathFuture.join();
        if (path == null || path.isEmpty())
            throw new RuntimeException("没有合适的起跳点");
        TrajectoryRenderer pathRenderer = TrajectoryRenderer.create(path.stream().map(BetterBlockPos::toCenterPos).toList());

        try {
            walkPath(path);
        } finally {
            pathRenderer.remove();
        }
    }

    // 准备起飞
    private void tryToTakeoff() {
        if (!MinecraftContext.player().isOnGround()) {
            jump();
            return;
        }

        if (Math.abs(MinecraftContext.player().getVelocity().getX()) > 0.01 || Math.abs(MinecraftContext.player().getVelocity().getZ()) > 0.01)
            return;

        if (!MinecraftContext.world().getBlockState(MinecraftContext.player().getBlockPos().up(2)).isAir()) {
            gotoTakeoff();
            return;
        }

        CompletableFuture<AngleSolution> solutionFuture = new CompletableFuture<>();
        pathManager.updatePlayerNear();


        TaskManager.runDeferred(() -> CompletableFuture.runAsync(() -> solutionFuture.complete(angleSolver.solveAngle(MinecraftContext.player()
                .getEntityPos().add(0, 1, 0), Vec3d.ZERO, MinecraftContext.player()
                .isInLava(), new FireworkBoost(null, 10)))), TickPhase.POST, 0);

        delay(1);
        for (int i = 0; i < 100 && !solutionFuture.isDone(); i++)
            delay(1);
        if (!solutionFuture.isDone()) {
            msg.SendMsg("solve超时!", MsgLevel.error);
            return;
        }
        if (solutionFuture.join() == null) {
            gotoTakeoff();
        } else {
            takeoff();
        }
    }

    public void run() {
        TaskManager.build(pathManager::run).setName("elytra路径管理器").async().daemon().async().setPhase(TickPhase.POST).start();
        while (pathManager.path.isEmpty()) {delay(1);}

        TimelinessCounter fireworkCoolDown = new TimelinessCounter(10);
        AtomicInteger minFireworkTicks = new AtomicInteger(10);
        BoxRenderer GoingToRenderer = BoxRenderer.create(BetterBlockPos.ORIGIN, 0xFF55FF55);

        PacketListener SetbackListener = PacketListener.create(packet -> {
            if (packet instanceof PlayerPositionLookS2CPacket)
                runDeferred(fireworkCoolDown::accumulate, TickPhase.PRE, 0);
        });

        try {
            while (true) {
                if (paused) {
                    delay(1);
                    continue;
                }

                if (!MinecraftContext.player().isGliding()) {
                    tryToTakeoff();
                    delay(1);
                    continue;
                }

                CompletableFuture<AngleSolution> solutionFuture = new CompletableFuture<>();
                TaskManager.runDeferred(() -> CompletableFuture.runAsync(() -> solutionFuture.complete(angleSolver.solveAngle(MinecraftContext.player()
                        .getEntityPos(), MinecraftContext.player().getVelocity(), MinecraftContext.player()
                        .isInLava(), new FireworkBoost(getFireworkTicksExisted(), minFireworkTicks.get())))), TickPhase.POST, 0);

                delay(1);
                for (int i = 0; i < 10 && !solutionFuture.isDone(); i++)
                    delay(1);
                if (!solutionFuture.isDone()) {
                    msg.SendMsg("solve超时!", MsgLevel.error);
                    continue;
                }

                AngleSolution solution = solutionFuture.join();
                if (solution == null) {
                    msg.SendMsg("解算失败!", MsgLevel.info);
                    continue;
                }
                if (!solution.solvedPitch())
                    msg.SendMsg("未解算出pitch", MsgLevel.debug);
                SilentRotation.setRotation(solution.rotation().getYaw(), solution.rotation().getPitch());

                fireworkTick(solution, minFireworkTicks, fireworkCoolDown);

                if (solution.goingTo() != null)
                    GoingToRenderer.update(new BetterBlockPos(BlockPos.ofFloored(solution.goingTo())), 0xFF55FF55);
            }
        } finally {
            SetbackListener.remove();
            GoingToRenderer.remove();
        }
    }

    private void fireworkTick(AngleSolution solution, AtomicInteger minFireworkTicks, TimelinessCounter fireworkCoolDown) {

        if (fireworkCoolDown.getCount() == 0) {
            if (solution.goingTo() == null)
                return;
            Vec3d start = MinecraftContext.player().getEntityPos();
            boolean isNotOnDescend = MinecraftContext.player().getY() < solution.goingTo().y + 5;
            double currentSpeed = new Vec3d(MinecraftContext.player().getVelocity().x,
                    MinecraftContext.player().getY() < solution.goingTo().y ? Math.max(0, MinecraftContext.player()
                            .getVelocity().y) : MinecraftContext.player().getVelocity().y, MinecraftContext.player().getVelocity().z).lengthSquared();

            if (solution.forceUseFirework() || getAttachedFirework().isEmpty() && isNotOnDescend &&
                                               (MinecraftContext.player().getY() < solution.goingTo().y - 5 || start.distanceTo(new Vec3d(
                                                       solution.goingTo().x + 0.5, MinecraftContext.player().getY(), solution.goingTo().z + 0.5)) >
                                                                                                               5) &&
                                               currentSpeed < elytraFireworkSpeed.get() * elytraFireworkSpeed.get()) {
                fireworkCoolDown.accumulate();
                int fireworkSlot = findItemInHotBar(Items.FIREWORK_ROCKET);
                if (fireworkSlot == -1) {
                    takeOutFirework();
                    fireworkSlot = findItemInHotBar(Items.FIREWORK_ROCKET);
                    if (fireworkSlot == -1)
                        throw new IllegalStateException("缺少烟花");
                }
                int finalSlot = fireworkSlot;
                int level = 1;
                runOnMain(() -> {
                    MinecraftContext.player().getInventory().setSelectedSlot(finalSlot);
                    MinecraftContext.networkHandler().sendPacket(new UpdateSelectedSlotC2SPacket(finalSlot));
                    MinecraftContext.interactionManager().interactItem(MinecraftContext.player(), Hand.MAIN_HAND);
                });
                minFireworkTicks.set(10 * (1 + level));
            }
        }
    }

    private Optional<FireworkRocketEntity> getAttachedFirework() {
        return StreamSupport.stream(MinecraftContext.world().getEntities().spliterator(), false).filter(x -> x instanceof FireworkRocketEntity)
                .filter(x -> Objects.equals(getBoostedEntity((FireworkRocketEntity) x), MinecraftContext.player()))
                .map(x -> (FireworkRocketEntity) x).findFirst();
    }

    private Integer getFireworkTicksExisted() {
        return getAttachedFirework().map(e -> e.age).orElse(null);
    }

    private int findItemInHotBar(Item item) {
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

    private void takeOutFirework() {
        HandledScreen<?> handled = computeOnMain(() -> {
            MinecraftContext.client().setScreen(new InventoryScreen(MinecraftContext.player()));
            if (!(MinecraftContext.client().currentScreen instanceof HandledScreen<?> handled2))
                throw new IllegalStateException("窗口异常");
            return handled2;
        });

        List<Integer> replaceList = new ArrayList<>();

        PlayerInventory inv = MinecraftContext.player().getInventory();
        for (int i = 0; i < 9; i++) {
            ItemStack s = inv.getStack(i);
            if (s.isEmpty() || s.getItem() != Items.ENDER_CHEST && s.getItem() != Items.DIAMOND_PICKAXE && s.getItem() != Items.NETHERITE_PICKAXE &&
                               s.getItem() != Items.DIAMOND_SWORD && s.getItem() != Items.NETHERITE_SWORD &&
                               s.getItem() != FoodList[FoodIndex.get()] && s.getItem() != Items.TOTEM_OF_UNDYING)
                replaceList.add(i);
        }
        if (replaceList.isEmpty())
            throw new IllegalStateException("无槽位放置烟花");


        int c = 0;
        ScreenHandler handler = handled.getScreenHandler();
        for (int i = 9; i < 36; i++) {
            Slot s = handler.getSlot(i);
            if (s == null)
                continue;
            ItemStack stack = s.getStack();
            if (stack == null || stack.isEmpty())
                continue;
            Item item = stack.getItem();
            if (item == Items.FIREWORK_ROCKET) {
                c += stack.getCount();
                if (replaceList.isEmpty())
                    break;
                int slot = replaceList.removeFirst();
                int finalI = i;
                runOnMain(() -> {
                    MinecraftContext.interactionManager().clickSlot(handler.syncId, finalI, 0, SlotActionType.PICKUP, MinecraftContext.player());
                    MinecraftContext.interactionManager().clickSlot(handler.syncId, slot + 36, 0, SlotActionType.PICKUP, MinecraftContext.player());
                    MinecraftContext.interactionManager().clickSlot(handler.syncId, finalI, 0, SlotActionType.PICKUP, MinecraftContext.player());
                });
            }
        }
        runOnMain(handled::close);

        if (c == 0)
            throw new IllegalStateException("没有烟花了");

    }
}
