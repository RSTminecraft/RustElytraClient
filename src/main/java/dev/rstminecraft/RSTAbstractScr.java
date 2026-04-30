package dev.rstminecraft;

import net.minecraft.client.font.TextRenderer;
import net.minecraft.client.gui.screen.Screen;
import net.minecraft.client.gui.tooltip.Tooltip;
import net.minecraft.client.gui.widget.ButtonWidget;
import net.minecraft.client.gui.widget.ClickableWidget;
import net.minecraft.client.gui.widget.TextFieldWidget;
import net.minecraft.text.Text;
import org.jetbrains.annotations.NotNull;

import java.util.function.Consumer;

public abstract class RSTAbstractScr extends Screen {
    protected RSTAbstractScr(Text title) {
        super(title);
    }
    // 屏幕组件抽象类
    public static abstract class SrcEntry {
    }

    // 按钮组件
    public static class SrcButtonEntry extends SrcEntry {
        public String text;
        public String tooltip;
        public Runnable onClick;

        /**
         * @param text    按钮文本
         * @param tooltip 按钮提示文本
         * @param onClick 按钮回调
         */
        public SrcButtonEntry(String text, String tooltip, Runnable onClick) {
            this.text = text;
            this.tooltip = tooltip;
            this.onClick = onClick;
        }
    }

    // 开关组件
    public static class SrcSwitchEntry extends SrcEntry {
        public String text;
        public String tooltip;
        public Consumer<Boolean> onClick;
        public boolean value;

        /**
         * @param text    按钮文本
         * @param tooltip 按钮提示文本
         * @param onClick 按钮回调
         */
        public SrcSwitchEntry(String text, String tooltip, Consumer<Boolean> onClick, boolean defaultValue) {
            this.text = text;
            this.value = defaultValue;
            this.tooltip = tooltip;
            this.onClick = onClick;
        }
    }

    // 输入组件
    public static class SrcInputEntry extends SrcEntry {
        public String title;
        public String defaultText;
        public Consumer<String> onTick;

        /**
         * @param title       输入框标题
         * @param defaultText 输入框默认文本
         * @param onTick      输入回调
         */
        public SrcInputEntry(String title, String defaultText, Consumer<String> onTick) {
            this.title = title;
            this.defaultText = defaultText;
            this.onTick = onTick;
        }
    }

    /**
     * 将组件转换为Minecraft屏幕控件
     *
     * @param Entry        组件列表(使用多态接受组件)
     * @param row          组件行数
     * @param col          组件列数
     * @param widgetWidth  屏幕控件宽度
     * @param width        屏幕宽度
     * @param height       屏幕高度
     * @param textRenderer Minecraft文本渲染器
     * @return 屏幕控件列表
     */
    protected ClickableWidget @NotNull [] EntryToWidget(SrcEntry[] Entry, int row, int col, int widgetWidth, int width, int height, TextRenderer textRenderer) {
        ClickableWidget[] widget = new ClickableWidget[row * col];
        for (int i = 0; i < row; i++) {
            for (int j = 0; j < col; j++) {
                int finalI = i;
                int finalJ = j;

                // 计算组件顶点坐标
                // 所有组件均匀排列在屏幕上,矩形排列
                int widgetX = width / (col + 1) * (j + 1) - widgetWidth / 2;
                int widgetY = 40 + (height - 80) / (row + 1) * (i + 1);

                // 判断组件类型
                if (Entry[i * col + j] instanceof SrcButtonEntry)
                    // 按钮控件
                    widget[i * col + j] = ButtonWidget.builder(Text.literal(((SrcButtonEntry) Entry[i * col + j]).text), button -> ((SrcButtonEntry) Entry[finalI * col + finalJ]).onClick.run()).dimensions(widgetX, widgetY, widgetWidth, 20).tooltip(Tooltip.of(Text.literal(((SrcButtonEntry) Entry[i * col + j]).tooltip))).build();
                else if (Entry[i * col + j] instanceof SrcInputEntry) {
                    // 输入框控件
                    TextFieldWidget tmp = new TextFieldWidget(textRenderer, widgetX, widgetY, widgetWidth, 20, Text.literal(((SrcInputEntry) Entry[i * col + j]).title));
                    tmp.setText("");
                    tmp.setMaxLength(10);
                    tmp.setPlaceholder(Text.literal(((SrcInputEntry) Entry[i * col + j]).defaultText));
                    int finalJ1 = j;
                    int finalI1 = i;
                    tmp.setChangedListener(str -> ((SrcInputEntry) Entry[finalI1 * col + finalJ1]).onTick.accept(str));
                    widget[i * col + finalJ1] = tmp;
                }
            }

        }
        return widget;
    }

    protected void drawWidget(SrcEntry[] entry, int row, int col, int widgetWidth, int width, int height, TextRenderer textRenderer) {
        ClickableWidget[][] widget = new ClickableWidget[row * col][0];
        SrcEntry[] entry2 = new SrcEntry[entry.length];
        for (int i = 0; i < entry.length; i++) {
            if (entry[i] instanceof SrcSwitchEntry) {
                int finalI = i;
                entry2[i] = new SrcButtonEntry(((SrcSwitchEntry) entry[i]).text + ":" + (((SrcSwitchEntry) entry[i]).value ? "开" : "关"), ((SrcSwitchEntry) entry[i]).tooltip, () -> {
                    ((SrcSwitchEntry) entry[finalI]).value = !((SrcSwitchEntry) entry[finalI]).value;
                    ((SrcSwitchEntry) entry[finalI]).onClick.accept(((SrcSwitchEntry) entry[finalI]).value);
                    for (ClickableWidget j : widget[0])
                        remove(j);
                    drawWidget(entry, row, col, widgetWidth, width, height, textRenderer);
                });
            } else {
                entry2[i] = entry[i];
            }
        }
        widget[0] = EntryToWidget(entry2, row, col, widgetWidth, width, height, textRenderer);
        for (ClickableWidget i : widget[0]) {
            addDrawableChild(i);
        }
    }
}
