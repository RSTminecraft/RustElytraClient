package dev.rstminecraft;

//文件解释：本文件为模组GUI实现。

import dev.rstminecraft.RustClientCore.screen.AbstractScr;
import dev.rstminecraft.RustClientCore.messenger.Messenger;
import dev.rstminecraft.RustClientCore.messenger.MsgLevel;
import dev.rstminecraft.RustClientCore.screen.SimpleScr;
import net.minecraft.client.gui.screen.Screen;
import net.minecraft.text.Text;
import org.jetbrains.annotations.Nullable;

import static dev.rstminecraft.RustElytraClient.*;

public class RSTScr extends SimpleScr {
    // 主菜单相关信息
    // 3行1列
    private static final int MainButtonsRow = 2;
    private static final int MainButtonsCol = 1;

    RSTScr(Screen parent) {
        super(Text.literal("RST Auto Elytra Menu"), null, parent, MainButtonsRow, MainButtonsCol, null);

        //主菜单组件信息
        SrcEntry[] MainEntry = {new SrcButtonEntry("设置", "调整Mod设置", () -> {
            if (client != null) {
                client.setScreen(new SettingsScr(client.currentScreen));
            }
        })// 一个“设置”按钮
                , new SrcButtonEntry("飞行菜单", "输入坐标并开始自动飞行", () -> {
            if (client != null) {
                client.setScreen(new ciScr(client.currentScreen));
            }
        })};

        if (FirstUse.get()) {
            // 首次使用,提示信息
            super.entry = new SrcEntry[]{new SrcButtonEntry("我知道了", "阅读完毕指南", () -> {
                FirstUse.set(false);
                if (client != null)
                    client.setScreen(new RSTScr(parent));
            })};
            super.row = 1;
            super.col = 1;
            super.recall = (context, mouseX, mouseY, delta) -> {
                context.drawTextWithShadow(textRenderer, "欢迎使用RSTAutoElytraMod", width / 3 * 2, 20, 0xFFFFFFFF);
                context.drawTextWithShadow(textRenderer, "若您是第一次使用RSTAutoElytraMod，请务必仔细阅读本指南",
                                           width / 4, height / 10, 0xFFFF0000);
                context.drawTextWithShadow(textRenderer, "否则可能造成物资损失或存档损坏等严重后果！", width / 4,
                                           height / 10 + 15, 0xFFFFFFFF);
            };
        } else if (ModStatus != ModStatuses.idle) {
            // 飞行途中,无法使用菜单。
            super.entry = new SrcEntry[]{new SrcButtonEntry("取消飞行", "取消当前的飞行", () -> {
                ModStatus = ModStatuses.canceled;
                if (client != null)
                    client.setScreen(parent);
            })};
            super.row = 1;
            super.col = 1;
            super.recall = (context, mouseX, mouseY, delta) -> {
                context.drawTextWithShadow(textRenderer, "欢迎使用RSTAutoElytraMod", width / 3 * 2, 20, 0xFFFFFFFF);
                context.drawTextWithShadow(textRenderer, "正在飞行中，若要更改设置请先取消飞行。", width / 4, height / 10,
                                           0xFFFF0000);
            };
        } else {
            super.entry = MainEntry;
            super.recall = (context, mouseX, mouseY, delta) -> context.drawTextWithShadow(textRenderer,
                                                                                          "欢迎使用RSTAutoElytraMod",
                                                                                          width / 3 * 2, 20,
                                                                                          0xFFFFFFFF);
        }


    }

    // 开始飞行菜单
    private static class ciScr extends SimpleScr {
        // 3行一列
        private static final int ciButtonsRow = 5;
        private static final int ciButtonsCol = 1;
        private @Nullable String x = null;
        private @Nullable String z = null;


        public ciScr(Screen parent) {
            super(Text.literal("RST Auto Elytra Mod Menu"), null, parent, ciButtonsRow, ciButtonsCol, null);
            super.entry = new SrcEntry[]{new SrcInputEntry("目的地X坐标", "目的地X坐标",
                                                           str -> x = str), // 一个X轴输入框
                    new SrcInputEntry("目的地Z坐标", "目的地Z坐标", str -> z = str), // 一个Y轴输入框
                    new SrcButtonEntry("开始飞行(鞘翅模式)", "开始前往上方输入的坐标,并在必要时补充新的满耐久鞘翅",
                                       () -> {
                                           int x1, z1;
                                           // 将输入框内文本转为数字,若转换异常,则说明输入信息有误,提前返回
                                           if (x == null || z == null)
                                               return;
                                           try {
                                               x1 = Integer.parseInt(x);
                                           } catch (NumberFormatException e) {
                                               return;
                                           }
                                           try {
                                               z1 = Integer.parseInt(z);
                                           } catch (NumberFormatException e) {
                                               return;
                                           }
                                           if (client == null || client.player == null)
                                               return;
                                           if (ModTask.status != ModTask.TaskStatus.NO_TASK) {
                                               msg.SendMsg("模组遇到线程状态错误，通常重启可解决！",
                                                           MsgLevel.warning);
                                               return;
                                           }
                                           // 开始飞行
                                           msg.SendMsg("任务开始！", MsgLevel.warning);
                                           ModTask.startTask(ModTask.TaskType.ELYTRA, isAutoLog.get(),
                                                             isAutoLogOnSeg1.get(), x1, z1);
                                           client.setScreen(null);
                                       }),// 一个“开始飞行”按钮
                    new SrcButtonEntry("开始飞行(XP模式)",
                                       "开始前往上方输入的坐标,鞘翅无耐久时通过附魔之瓶补充,仅需一个鞘翅", () -> {
                        int x1, z1;
                        // 将输入框内文本转为数字,若转换异常,则说明输入信息有误,提前返回
                        if (x == null || z == null)
                            return;
                        try {
                            x1 = Integer.parseInt(x);
                        } catch (NumberFormatException e) {
                            return;
                        }
                        try {
                            z1 = Integer.parseInt(z);
                        } catch (NumberFormatException e) {
                            return;
                        }
                        if (client == null || client.player == null)
                            return;

                        if (ModTask.status != ModTask.TaskStatus.NO_TASK) {
                            msg.SendMsg("模组遇到线程状态错误，通常重启可解决！", MsgLevel.warning);
                            return;
                        }
                        // 开始飞行
                        msg.SendMsg("任务开始！", MsgLevel.warning);
                        ModTask.startTask(ModTask.TaskType.EXP_BOTTLE, isAutoLog.get(), isAutoLogOnSeg1.get(), x1, z1);
                        client.setScreen(null);
                    }),// 一个“开始飞行”按钮
                    new SrcButtonEntry("开始飞行(无尽鞘翅模式)",
                                       "开始前往上方输入的坐标,通过自动每秒重装鞘翅,可不消耗鞘翅耐久,仅需一个鞘翅,且无需经验瓶",
                                       () -> {
                                           int x1, z1;
                                           // 将输入框内文本转为数字,若转换异常,则说明输入信息有误,提前返回
                                           if (x == null || z == null)
                                               return;
                                           try {
                                               x1 = Integer.parseInt(x);
                                           } catch (NumberFormatException e) {
                                               return;
                                           }
                                           try {
                                               z1 = Integer.parseInt(z);
                                           } catch (NumberFormatException e) {
                                               return;
                                           }
                                           if (client == null || client.player == null)
                                               return;

                                           if (ModTask.status != ModTask.TaskStatus.NO_TASK) {
                                               msg.SendMsg("模组遇到线程状态错误，通常重启可解决！",
                                                           MsgLevel.warning);
                                               return;
                                           }
                                           // 开始飞行
                                           msg.SendMsg("任务开始！", MsgLevel.warning);
                                           ModTask.startTask(ModTask.TaskType.INFINITY_ELYTRA, isAutoLog.get(),
                                                             isAutoLogOnSeg1.get(), x1, z1);
                                           client.setScreen(null);
                                       })// 一个“开始飞行”按钮
            };
        }

    }

    // 设置屏幕
    private static class SettingsScr extends SimpleScr {
        // 5行一列
        private static final int SettingsButtonsRow = 5;
        private static final int SettingsButtonsCol = 1;

        public SettingsScr(Screen parent) {
            super(Text.literal("RST Auto Elytra Mod Settings Menu"), null, parent, SettingsButtonsRow,
                  SettingsButtonsCol, null);
            super.entry = new SrcEntry[]{new SrcSwitchEntry("自动退出", "在任务失败时是否自动退出服务器",
                                                            isAutoLog::set, isAutoLog.get())//自动退出开关
                    , new SrcSwitchEntry("第一段自动退出",
                                         "在任务刚开始时若失败是否自动退出。假如否，您可以避免在第一次补给时因“末影箱中没有补给物品”等简单原因自动退出（造成时间浪费），但请确保第一次补给成功后再离开电脑",
                                         isAutoLogOnSeg1::set, isAutoLogOnSeg1.get()) //第一次自动退出开关
                    , new SrcSwitchEntry("发送调试信息", "是否发送调试信息", b -> {
                DisplayDebug.set(b);
                msg = new Messenger("Rust Elytra", b ? MsgLevel.debug : MsgLevel.info, MODLOGGER);
            }, DisplayDebug.get())// 调试信息
                    , new SrcButtonEntry("高级设置", "仅供调试用的高级设置。请不要轻易更改！", () -> {
                if (client != null)
                    client.setScreen(new AdvancedSettingsWarningScr(client.currentScreen));

            })// 高级设置
                    , new SrcButtonEntry("HUD设置", "更改HUD相关设置", () -> {
                if (client != null)
                    client.setScreen(new HudSettingsScr(client.currentScreen));
            })};
        }
    }

    // 高级设置屏幕
    private static class AdvancedSettingsScr extends AbstractScr {
        // 5行一列
        private static final int SettingsButtonsRow = 3;
        private static final int SettingsButtonsCol = 1;
        private final Screen parent;
        private final int buttonWidth;

        public AdvancedSettingsScr(Screen parent) {
            super(Text.literal("RST Auto Elytra Mod Settings Menu"));
            this.parent = parent;
            buttonWidth = Math.max(100, Math.min(600, (int) (width * 0.7)));
        }

        private void BuildButtons() {
            SrcEntry[] settingsEntry = new SrcEntry[]{new SrcSwitchEntry("检查盔甲",
                                                                         "是否检查盔甲。关闭本开关后,即使您没有足够装备," +
                                                                         "也可以开始飞行。警告：没有足够的装备就开始飞行十分危险!除非遭遇非常情况," +
                                                                         "不要打开本开关!!!", inspectArmor::set,
                                                                         inspectArmor.get())//检查盔甲开关
                    , new SrcSwitchEntry("更详细的调试信息",
                                         "是否打印区块加载信息等更加冗长的调试信息。注意：本开关虽然不影响模组安全性,但可能造成被调试信息刷屏等",
                                         verboseDisplayDebug::set, verboseDisplayDebug.get()) // 额外调试信息
                    , new SrcButtonEntry("Mod食物:" + FoodList[FoodIndex.get()].getName().getString(), "切换Mod" +
                                                                                                       "用于回复血量的食物，默认为金胡萝卜，是用其他食物可能降低模组稳定性!!!",
                                         () -> {
                                             FoodIndex.set((FoodIndex.get() + 1) % FoodList.length);
                                             BuildButtons();
                                         })};

            drawWidget(settingsEntry, SettingsButtonsRow, SettingsButtonsCol, buttonWidth, width, height, textRenderer,
                       0, 0);
        }

        @Override
        protected void init() {
            BuildButtons();
        }

        @Override
        public void close() {
            if (client != null) {
                client.setScreen(parent);
            }
        }
    }

    // 高级设置的警告
    private static class AdvancedSettingsWarningScr extends SimpleScr {
        // 5行一列
        private static final int SettingsButtonsRow = 1;
        private static final int SettingsButtonsCol = 2;

        public AdvancedSettingsWarningScr(Screen parent) {
            super(Text.literal("RST Auto Elytra Mod Settings Menu"), null, parent, SettingsButtonsRow,
                  SettingsButtonsCol, null);
            super.recall = (context, mouseX, mouseY, delta) -> {
                context.drawCenteredTextWithShadow(textRenderer, "您正在修改高级设置!", width / 2, height / 4,
                                                   16777215);
                context.drawCenteredTextWithShadow(textRenderer, "这可能导致Mod稳定性下降或出现意外事故!!!", width / 2,
                                                   height / 4 + 30, 0xFF0000);
            };
            super.entry = new SrcEntry[]{new SrcButtonEntry("返回", "不修改高级设置", this::close), new SrcButtonEntry(
                    "我知道我在做什么!", "您已知晓更改高级设置的可能风险", () -> {
                if (client != null) {
                    client.setScreen(new AdvancedSettingsScr(parent));
                }
            })};
        }
    }

    // HUD 设置
    private static class HudSettingsScr extends SimpleScr {
        private static final int HudSettingsRow = 4;
        private static final int HudSettingsCol = 1;
        private int x = 0;
        private int y = 0;

        public HudSettingsScr(Screen parent) {
            super(Text.literal("RST Elytra Mod Hud Settings"), null, parent, HudSettingsRow, HudSettingsCol, null);
            super.entry = new SrcEntry[]{new SrcInputEntry("HUD X坐标", "HUD X坐标", str -> {
                try {
                    x = Integer.parseInt(str);
                } catch (NumberFormatException ignored) {
                }
            }), new SrcInputEntry("HUD Y坐标", "HUD Y坐标", str -> {
                try {
                    y = Integer.parseInt(str);
                } catch (NumberFormatException ignored) {
                }
            }), new SrcButtonEntry("应用坐标设置", "以上方输入的坐标显示HUD", () -> {
                HudX.set(x);
                HudY.set(y);
            }), new SrcSwitchEntry("HUD状态", "是否开启HUD", enableHud::set, enableHud.get())};
        }
    }
}
