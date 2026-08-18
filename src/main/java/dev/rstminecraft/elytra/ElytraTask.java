package dev.rstminecraft.elytra;

import dev.rstminecraft.RustClientCore.MinecraftContext;
import dev.rstminecraft.RustClientCore.listener.PacketListener;
import dev.rstminecraft.RustClientCore.messenger.MsgLevel;
import dev.rstminecraft.RustClientCore.renderer.BoxRenderer;
import dev.rstminecraft.RustClientCore.renderer.TrajectoryRenderer;
import dev.rstminecraft.RustClientCore.task.TaskManager;
import dev.rstminecraft.RustClientCore.task.TickPhase;
import dev.rstminecraft.RustClientCore.utils.BetterBlockPos;
import dev.rstminecraft.SupplyTask;
import dev.rstminecraft.elytra.AngleSolver.FireworkBoost;
import dev.rstminecraft.elytra.path.NetherPathfinderContext;
import dev.rstminecraft.elytra.path.PathManager;
import dev.rstminecraft.elytra.takeoff.TakeoffTargetFinder;
import dev.rstminecraft.utils.Rotation;
import dev.rstminecraft.utils.SilentRotation;
import dev.rstminecraft.utils.TimelinessCounter;
import net.minecraft.block.BlockState;
import net.minecraft.block.Blocks;
import net.minecraft.client.gui.screen.ingame.HandledScreen;
import net.minecraft.client.gui.screen.ingame.InventoryScreen;
import net.minecraft.entity.Entity;
import net.minecraft.entity.EquipmentSlot;
import net.minecraft.entity.LivingEntity;
import net.minecraft.entity.mob.Monster;
import net.minecraft.entity.mob.ZombifiedPiglinEntity;
import net.minecraft.entity.player.PlayerInventory;
import net.minecraft.entity.projectile.FireworkRocketEntity;
import net.minecraft.item.Item;
import net.minecraft.item.ItemStack;
import net.minecraft.item.Items;
import net.minecraft.network.packet.c2s.play.ClientCommandC2SPacket;
import net.minecraft.network.packet.c2s.play.UpdateSelectedSlotC2SPacket;
import net.minecraft.network.packet.s2c.play.PlayerPositionLookS2CPacket;
import net.minecraft.registry.entry.RegistryEntry;
import net.minecraft.screen.ScreenHandler;
import net.minecraft.screen.slot.Slot;
import net.minecraft.screen.slot.SlotActionType;
import net.minecraft.util.Hand;
import net.minecraft.util.math.BlockPos;
import net.minecraft.util.math.Vec3d;
import net.minecraft.world.biome.Biome;
import net.minecraft.world.biome.BiomeKeys;

import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.stream.StreamSupport;

import static dev.rstminecraft.RustClientCore.task.TaskManager.*;
import static dev.rstminecraft.RustElytraClient.*;
import static dev.rstminecraft.elytra.ElytraLanding.LANDING_COLUMN_HEIGHT;
import static dev.rstminecraft.elytra.takeoff.WalkToTakeoff.walkPath;

public class ElytraTask {

    private final NetherPathfinderContext npf;
    private final BlockStateUtils bsu;
    private final AtomicBoolean resettingElytra = new AtomicBoolean(false);
    ExecutorService angleSolverRunner = Executors.newSingleThreadExecutor();
    private AngleSolver angleSolver;
    private PathManager pathManager;

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

    public static boolean isPlayerInNetherWastes() {
        BlockPos blockPos = MinecraftContext.player().getBlockPos();

        RegistryEntry<Biome> biomeEntry = MinecraftContext.world().getBiome(blockPos);

        return biomeEntry.matchesKey(BiomeKeys.NETHER_WASTES);
    }

    private void jump() {
        MinecraftContext.client().options.jumpKey.setPressed(true);
        runDeferred(() -> MinecraftContext.client().options.jumpKey.setPressed(false), TickPhase.POST, 0);
        delay(1);
    }

    private void checkElytra() {
        if (!resettingElytra.get() && MinecraftContext.player().getEquippedStack(EquipmentSlot.CHEST).getItem() != Items.ELYTRA) {
            for (int i = 0; i < 36; i++) {
                if (MinecraftContext.player().getInventory().getStack(i).getItem() == Items.ELYTRA) {
                    MinecraftContext.interactionManager().clickSlot(0, i < 9 ? i + 36 : i, 0, SlotActionType.PICKUP, MinecraftContext.player());
                    MinecraftContext.interactionManager().clickSlot(0, 6, 0, SlotActionType.PICKUP, MinecraftContext.player());
                    MinecraftContext.interactionManager().clickSlot(0, i < 9 ? i + 36 : i, 0, SlotActionType.PICKUP, MinecraftContext.player());
                    break;
                }
            }
        }
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
        pathManager.doPathManagerTick = false;
        pathManager.pathRecalculateSegment(Math.min(pathManager.path.size(), pathManager.playerNear + 20));
        delay(1);
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

        pathManager.doPathManagerTick = true;
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
        runDeferred(() -> CompletableFuture.runAsync(() -> solutionFuture.complete(angleSolver.solveAngle(MinecraftContext.player()
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

    private boolean checkInv() {
        if (SupplyTask.countItemInInventory(Items.FIREWORK_ROCKET) < 192)
            return true;
        if (SupplyTask.countItemInInventory(Items.TOTEM_OF_UNDYING) < 1)
            return true;
        if (SupplyTask.countItemInInventory(FoodList[FoodIndex.get()]) < 16)
            return true;
        if (!resettingElytra.get() && MinecraftContext.player().getEquippedStack(EquipmentSlot.CHEST).getItem() == Items.ELYTRA &&
            MinecraftContext.player().getEquippedStack(EquipmentSlot.CHEST).getDamage() >
            MinecraftContext.player().getEquippedStack(EquipmentSlot.CHEST).getMaxDamage() - 40) {
            throw new RuntimeException("鞘翅没耐久了!!!");
        }

        return false;
    }

    public void run() {

        Thread pathManagerThread = build(pathManager::run).setName("elytra路径管理器").daemon().setPhase(TickPhase.PRE).start();
        build(() -> {
            while (true) {
                if (currentTick % 12 == 0 && MinecraftContext.player().isGliding()) {
                    resettingElytra.set(true);
                    fixEyeHeight = true;
                    MinecraftContext.interactionManager().clickSlot(0, 6, 0, SlotActionType.PICKUP, MinecraftContext.player());
                    MinecraftContext.interactionManager().clickSlot(0, 44, 0, SlotActionType.PICKUP, MinecraftContext.player());
                    runDeferred(() -> {
                        MinecraftContext.interactionManager().clickSlot(0, 44, 0, SlotActionType.PICKUP, MinecraftContext.player());
                        MinecraftContext.interactionManager().clickSlot(0, 6, 0, SlotActionType.PICKUP, MinecraftContext.player());
                        MinecraftContext.player().startGliding();
                        MinecraftContext.networkHandler()
                                .sendPacket(new ClientCommandC2SPacket(MinecraftContext.player(), ClientCommandC2SPacket.Mode.START_FALL_FLYING));
                        resettingElytra.set(false);
                    }, TickPhase.PRE, 0);
                } else if (currentTick % 12 == 3) {
                    fixEyeHeight = false;
                }
                delay(1);
            }
        }).daemon().setPhase(TickPhase.POST).setName("无尽鞘翅").start();
        while (pathManager.path.isEmpty()) {delay(1);}

        TimelinessCounter fireworkCoolDown = new TimelinessCounter(10);
        AtomicInteger minFireworkTicks = new AtomicInteger(10);
        BoxRenderer GoingToRenderer = BoxRenderer.create(BetterBlockPos.ORIGIN, 0xFF55FF55);

        PacketListener SetbackListener = PacketListener.create(packet -> {
            if (packet instanceof PlayerPositionLookS2CPacket)
                runDeferred(fireworkCoolDown::accumulate, TickPhase.PRE, 0);
        });

        BetterBlockPos landingSpot = null;

        try {
            while (true) {
                checkElytra();
                if (paused) {
                    delay(1);
                    continue;
                }

                if (!MinecraftContext.player().isGliding() && !MinecraftContext.player().isInLava()) {
                    tryToTakeoff();
                    delay(1);
                    continue;
                }

                CompletableFuture<AngleSolution> solutionFuture = new CompletableFuture<>();
                runDeferred(() -> angleSolverRunner.submit(() -> {
                    try {
                        solutionFuture.complete(angleSolver.solveAngle(MinecraftContext.player()
                                .getEntityPos(), MinecraftContext.player().getVelocity(), MinecraftContext.player()
                                .isInLava(), new FireworkBoost(getFireworkTicksExisted(), minFireworkTicks.get())));
                    } catch (Throwable e) {
                        e.printStackTrace();
                        solutionFuture.complete(null);
                    }
                }), TickPhase.POST, 0);

                delay(1);

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

                autoEatTick();
                escapeLavaTick(solution);

                boolean landForSupply = checkInv();
                if (pathManager.path.complete || landForSupply) {
                    BetterBlockPos last = pathManager.path.getLast();
                    if (last != null && (MinecraftContext.player().getEntityPos().squaredDistanceTo(last.toCenterPos()) < 48 * 48 || landForSupply) &&
                        landingSpot == null && isPlayerInNetherWastes()) {
                        msg.SendMsg("准备着陆 寻找着陆位置", MsgLevel.warning);
                        BetterBlockPos spot = ElytraLanding.findSafeLandingSpot(new BetterBlockPos(MinecraftContext.player().getBlockPos()));

                        if (spot != null) {

                            msg.SendMsg("已找到着陆位置" + spot, MsgLevel.warning);
                            // 重新设置路径管理器
                            pathManagerThread.interrupt();
                            pathManager = new PathManager(spot, npf, bsu);
                            pathManagerThread = build(pathManager::run).setName("elytra路径管理器").daemon().setPhase(TickPhase.PRE).start();
                            while (pathManager.path.isEmpty()) {delay(1);}
                            angleSolver = new AngleSolver(npf, pathManager, bsu);
                            if (pathManager.path.getLast() == null || !npf.raytrace(pathManager.path.getLast().toCenterPos(), spot.toCenterPos())) {
                                ElytraLanding.markBadLandingPos(spot);
                                continue;
                            }
                            landingSpot = spot;
                        }
                    }

                    if (last != null && landingSpot != null && MinecraftContext.player().getEntityPos().squaredDistanceTo(last.toCenterPos()) < 1) {
                        if (landing(landingSpot)) {
                            return;
                        } else {
                            msg.SendMsg("无效的降落点！准备寻找下一个", MsgLevel.warning);
                            ElytraLanding.markBadLandingPos(landingSpot);
                            landingSpot = null;
                        }
                    }
                }
            }
        } finally {
            SetbackListener.remove();
            GoingToRenderer.remove();
            runDeferred(npf::destroy, TickPhase.POST, 10);
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

    private void autoEatTick() {
        int slot2 = -1;
        // 自动进食，恢复血量
        if (!MinecraftContext.player().isInLava() &&
            !MinecraftContext.player().isUsingItem() &&
            MinecraftContext.player().getVelocity().length() > 1.3 &&
            (MinecraftContext.player().getHungerManager().getFoodLevel() < 16 ||
             MinecraftContext.player().getHealth() < 15 && MinecraftContext.player().getHungerManager().getFoodLevel() < 20)) {
            msg.SendMsg("准备食用", MsgLevel.tip);
            for (int i = 0; i < 8; i++) {
                ItemStack s = MinecraftContext.player().getInventory().getStack(i);
                Item item = s.getItem();
                if (item == FoodList[FoodIndex.get()]) {
                    slot2 = i;
                    break;
                }
            }
            if (slot2 == -1)
                throw new RuntimeException("没有足够的食物了！");
            int finalSlot = slot2;
            runOnMain(() -> {
                MinecraftContext.player().getInventory().setSelectedSlot(finalSlot);
                MinecraftContext.networkHandler().sendPacket(new UpdateSelectedSlotC2SPacket(finalSlot));
                MinecraftContext.client().options.useKey.setPressed(true);
            });

            CountDownLatch stopped = new CountDownLatch(1);
            for (int i = 0; i < 35; i++) {
                TaskManager.runDeferred(() -> {
                    if (stopped.getCount() == 0)
                        return;
                    if (MinecraftContext.player().getVelocity().length() < 0.7 || MinecraftContext.player().isInLava() ||
                        MinecraftContext.player().isOnGround()) {
                        // 速度过低，放弃吃食物，防止影响烟花tick
                        msg.SendMsg("放弃吃食物！！！", MsgLevel.tip);
                        MinecraftContext.client().options.useKey.setPressed(false);
                        MinecraftContext.interactionManager().stopUsingItem(MinecraftContext.player());
                        stopped.countDown();
                    } else {
                        MinecraftContext.player().getInventory().setSelectedSlot(finalSlot);
                        MinecraftContext.networkHandler().sendPacket(new UpdateSelectedSlotC2SPacket(finalSlot));
                        MinecraftContext.client().options.useKey.setPressed(true);
                    }
                }, TickPhase.PRE, i);
            }

            TaskManager.runDeferred(() -> {
                if (stopped.getCount() > 0)
                    MinecraftContext.client().options.useKey.setPressed(false);
            }, TickPhase.PRE, 35);
        }
    }

    private void escapeLavaTick(AngleSolution solution) {
        if (MinecraftContext.player().isInLava() && !MinecraftContext.player().isGliding() && !resettingElytra.get()) {
            if (MinecraftContext.player().isOnGround()) {
                jump();
                delay(10);
            }
            jump();
            delay(1);
            if (!MinecraftContext.player().isGliding())
                throw new RuntimeException("被困岩浆");
        }
        if (MinecraftContext.player().isInLava() && MinecraftContext.player().getVelocity().length() < 0.3
            && solution == null && !resettingElytra.get()) {
            msg.SendMsg("正在逃离岩浆", MsgLevel.info);
            MinecraftContext.player().setPitch(-90);
            PlayerInventory inv = MinecraftContext.player().getInventory();

            // 找烟花
            int slots = -1;
            for (int i = 0; i < 8; i++) {
                ItemStack s = inv.getStack(i);
                if (s.isEmpty() || s.getItem() == Items.FIREWORK_ROCKET)
                    slots = i;
            }
            if (slots == -1)
                throw new RuntimeException("找不到烟花");
            else {
                // 切换到烟花所在格子
                int finalSlots = slots;
                runOnMain(() -> {
                    MinecraftContext.player().getInventory().setSelectedSlot(finalSlots);
                    MinecraftContext.networkHandler().sendPacket(new UpdateSelectedSlotC2SPacket(finalSlots));
                    MinecraftContext.interactionManager().interactItem(MinecraftContext.player(), Hand.MAIN_HAND);
                });
                // 使用烟花
                msg.SendMsg("已使用烟花！", MsgLevel.info);
            }

            int yaw = 0;
            int pitch = -90;


            boolean shouldCheck = true;
            for (int i = 0; i < 15; i++) {
                BlockState bs = MinecraftContext.world().getBlockState(MinecraftContext.player().getBlockPos().add(0, i, 0));
                if (bs.isAir()) {
                    shouldCheck = false;
                    break;
                }
                if (!bs.isOf(Blocks.LAVA))
                    break;
            }
            if (MinecraftContext.player().getY() > 32 || shouldCheck) {


                int[] dx = {1, -1, 0, 0};
                int[] dz = {0, 0, 1, -1};
                int[] yawl = {270, 0, 90, 180};

                for (int i = 0; i < 4; i++) {
                    int j;
                    OUT:
                    for (j = 0; j < 21; j++) {
                        BlockPos bp = MinecraftContext.player().getBlockPos().add(dx[i] * j, 0, dz[i] * j);
                        BlockState bs = MinecraftContext.world().getBlockState(bp);
                        if (bs.isAir()) {
                            for (int k = 1; k < 5; k++) {
                                if (!MinecraftContext.world().getBlockState(bp.add(dx[i] * k, 0, dz[i] * k)).isAir())
                                    continue OUT;
                            }
                            break;
                        }
                    }
                    if (j < 20) {
                        yaw = yawl[i];
                        pitch = 0;
                        break;
                    }
                }
            }

            for (int i = 0; ; i++) {
                MinecraftContext.player().setYaw(yaw);
                MinecraftContext.player().setPitch(pitch);
                if (i > 40)
                    throw new RuntimeException("逃离岩浆超时");
                if (!MinecraftContext.player().isInLava())
                    break;
                if (!MinecraftContext.player().isGliding())
                    throw new RuntimeException("逃离岩浆失败");
                delay(1);
            }
        }
    }

    private boolean landing(BetterBlockPos landPos) {
        List<BetterBlockPos> monsterBad = StreamSupport.stream(MinecraftContext.world().getEntities().spliterator(), false)
                .filter(entity -> entity instanceof Monster)
                .filter(monster -> !(monster instanceof ZombifiedPiglinEntity))
                .map(monster -> new BetterBlockPos(monster.getBlockPos()))
                .toList();

        for (BetterBlockPos monsterPos : monsterBad) {
            if (monsterPos.distanceSq(landPos.down(LANDING_COLUMN_HEIGHT)) < 576)
                return false;
        }
        msg.SendMsg("开始降落", MsgLevel.warning);
        while (true) {
            Vec3d from = MinecraftContext.player().getEntityPos();
            Vec3d to = new Vec3d((double) landPos.x + 0.5, from.y, (double) landPos.z + 0.5);
            Rotation rotation = Rotation.calcRotationFromVec3d(from, to);
            SilentRotation.setRotation(rotation.getYaw(), 0);

            if (MinecraftContext.player().getY() < landPos.y - LANDING_COLUMN_HEIGHT) {
                return false;
            }
            delay(1);

            if (!MinecraftContext.player().isGliding()) {
                if (MinecraftContext.player().getBlockPos().isWithinDistance(landPos.down(LANDING_COLUMN_HEIGHT), 2))
                    break;
                else
                    return false;
            }
        }
        delay(1);

        while (MinecraftContext.player().getVelocity().multiply(1, 0, 1).length() > 0.001) {
            msg.SendMsg("已降落,但还在移动,请稍等", MsgLevel.info);
            MinecraftContext.client().options.sneakKey.setPressed(true);
            delay(1);
        }

        MinecraftContext.client().options.sneakKey.setPressed(false);
        msg.SendMsg("降落完成 :)", MsgLevel.info);

        return true;

    }


}
