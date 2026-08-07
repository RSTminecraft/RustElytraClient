package dev.rstminecraft.elytra;

import baritone.api.BaritoneAPI;
import baritone.api.event.events.PacketEvent;
import baritone.api.event.listener.AbstractGameEventListener;
import baritone.api.utils.BetterBlockPos;
import baritone.utils.accessor.IFireworkRocketEntity;
import dev.rstminecraft.ModTask;
import dev.rstminecraft.RustClientCore.MsgLevel;
import dev.rstminecraft.RustClientCore.TaskManager;
import dev.rstminecraft.SupplyTask;
import dev.rstminecraft.elytra.AngleSolver.FireworkBoost;
import dev.rstminecraft.utils.TimelinessCounter;
import dev.rstminecraft.utils.TrajectoryRenderer;
import net.minecraft.client.MinecraftClient;
import net.minecraft.client.gui.screen.ingame.HandledScreen;
import net.minecraft.client.gui.screen.ingame.InventoryScreen;
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

import static dev.rstminecraft.RustClientCore.TaskManager.*;
import static dev.rstminecraft.RustElytraClient.*;
import static dev.rstminecraft.utils.TrajectoryRenderer.drawTrajectory;
import static dev.rstminecraft.utils.TrajectoryRenderer.markPos;

public class ElytraTask {

    private final BetterBlockPos destination;
    private final NetherPathfinderContext npf;
    private final BlockStateUtils bsu;
    private final MinecraftClient client;

    public ElytraTask(BlockPos destination) {
        this.destination = new BetterBlockPos(destination);
        client = MinecraftClient.getInstance();
        if (client.player == null || client.world == null)
            throw new ModTask.TaskException("client对象异常");
        npf = new NetherPathfinderContext(netherSeed.get());
        bsu = new BlockStateUtils(npf, client.world);
    }


    public void run() {
        PathManager pathManager = new PathManager(client, destination, npf, bsu);
        TaskManager.runTask(pathManager::run, "elytra路径管理器", true, true);
        while (pathManager.path.isEmpty()) {
            delay(1);
        }
        delay(2);
        AngleSolver angleSolver = new AngleSolver(client, npf, pathManager, bsu);

        TaskManager.runTask(() -> {
            while (true) {
                TrajectoryRenderer.clear();
                List<Vec3d> unpacked = new ArrayList<>();
                for (int i = 0; i < Math.min(1000, pathManager.path.size()); i++) {
                    unpacked.add(pathManager.path.get(i).toCenterPos());
                }
                drawTrajectory(unpacked);
                if (pathManager.playerNear > 0)
                    markPos(pathManager.path.get(pathManager.playerNear));

                TaskManager.delay(4);
            }
        });

        TimelinessCounter fireworkCoolDown = new TimelinessCounter(10);

        BaritoneAPI.getProvider().getPrimaryBaritone().getGameEventHandler().registerEventListener(new AbstractGameEventListener() {
            @Override
            public void onReceivePacket(PacketEvent packetEvent) {
                if (packetEvent.getPacket() instanceof PlayerPositionLookS2CPacket) {
                    runDeferred(fireworkCoolDown::accumulate, false, 0);
                }
            }
        });
        AtomicInteger minFireworkTicks = new AtomicInteger(10);
        while (true) {
            if (!client.player.isGliding()) {
                delay(1);
                continue;
            }

            CompletableFuture<AngleSolution> solutionFuture = new CompletableFuture<>();
            TaskManager.runDeferred(() -> CompletableFuture.runAsync(() -> solutionFuture.complete(angleSolver.solveAngle(client.player.getEntityPos(), client.player.getVelocity(), client.player.isInLava(), new FireworkBoost(getFireworkTicksExisted(), minFireworkTicks.get())))), true, 0);

            delay(1);
            for (int i = 0; i < 10 && !solutionFuture.isDone(); i++)
                delay(1);
            if (!solutionFuture.isDone()) {
                msg.SendMsg(client.player, "solve超时!", MsgLevel.error);
                continue;
            }

            AngleSolution solution = solutionFuture.join();
            if (solution == null) {
                msg.SendMsg(client.player, "解算失败!", MsgLevel.warning);
                continue;
            }
            if (!solution.solvedPitch())
                msg.SendMsg(client.player, "未解算出pitch", MsgLevel.warning);
            BaritoneAPI.getProvider().getPrimaryBaritone().getLookBehavior().updateTarget(solution.rotation(), false);


            fireworkTick(solution, minFireworkTicks, fireworkCoolDown);

        }
    }

    private void fireworkTick(AngleSolution solution, AtomicInteger minFireworkTicks, TimelinessCounter fireworkCoolDown) {

        if (fireworkCoolDown.getCount() == 0) {
            if (solution.goingTo() == null)
                return;
            Vec3d start = client.player.getEntityPos();
            boolean isNotOnDescend = client.player.getY() < solution.goingTo().y + 5;
            double currentSpeed = new Vec3d(client.player.getVelocity().x, client.player.getY() <
                                                                           solution.goingTo().y ? Math.max(0, client.player.getVelocity().y) : client.player.getVelocity().y, client.player.getVelocity().z).lengthSquared();

            if (solution.forceUseFirework() || getAttachedFirework().isEmpty() && isNotOnDescend &&
                                               (client.player.getY() < solution.goingTo().y - 5 || start.distanceTo(new Vec3d(
                                                       solution.goingTo().x + 0.5, client.player.getY(), solution.goingTo().z + 0.5)) > 5) &&
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
                int level = SupplyTask.getFireworkLevel(client.player.getInventory().getStack(fireworkSlot));
                runOnMain(() -> {
                    client.player.getInventory().setSelectedSlot(finalSlot);
                    client.getNetworkHandler().sendPacket(new UpdateSelectedSlotC2SPacket(finalSlot));
                    client.interactionManager.interactItem(client.player, Hand.MAIN_HAND);
                });
                minFireworkTicks.set(10 * (1 + level));
            }
        }
    }

    private Optional<FireworkRocketEntity> getAttachedFirework() {
        return StreamSupport.stream(client.world.getEntities().spliterator(), false).filter(x -> x instanceof FireworkRocketEntity)
                .filter(x -> Objects.equals(((IFireworkRocketEntity) x).getBoostedEntity(), client.player)).map(x -> (FireworkRocketEntity) x)
                .findFirst();
    }

    private Integer getFireworkTicksExisted() {
        return getAttachedFirework().map(e -> e.age).orElse(null);
    }

    private int findItemInHotBar(Item item) {
        int slot = -1;
        for (int i = 0; i < 9; i++) {
            ItemStack stack = client.player.getInventory().getStack(i);
            if (!stack.isEmpty() && stack.getItem() == item) {
                slot = i;
                break;
            }
        }
        return slot;
    }

    private void takeOutFirework() {
        HandledScreen<?> handled = computeOnMain(() -> {
            client.setScreen(new InventoryScreen(client.player));
            if (!(client.currentScreen instanceof HandledScreen<?> handled2))
                throw new IllegalStateException("窗口异常");
            return handled2;
        });

        List<Integer> replaceList = new ArrayList<>();

        PlayerInventory inv = client.player.getInventory();
        for (int i = 0; i < 9; i++) {
            ItemStack s = inv.getStack(i);
            if (s.isEmpty() ||
                s.getItem() != Items.ENDER_CHEST && s.getItem() != Items.DIAMOND_PICKAXE && s.getItem() != Items.NETHERITE_PICKAXE &&
                s.getItem() != Items.DIAMOND_SWORD && s.getItem() != Items.NETHERITE_SWORD && s.getItem() != FoodList[FoodIndex.get()] &&
                s.getItem() != Items.TOTEM_OF_UNDYING)
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
                if (replaceList.isEmpty())
                    break;
                int slot = replaceList.removeFirst();
                int finalI = i;
                runOnMain(() -> {
                    client.interactionManager.clickSlot(handler.syncId, finalI, 0, SlotActionType.PICKUP, client.player);
                    client.interactionManager.clickSlot(handler.syncId, slot + 36, 0, SlotActionType.PICKUP, client.player);
                    client.interactionManager.clickSlot(handler.syncId, finalI, 0, SlotActionType.PICKUP, client.player);
                });
                c += stack.getCount();
            }
        }
        runOnMain(handled::close);

        if (c == 0)
            throw new IllegalStateException("没有烟花了");

    }

}
