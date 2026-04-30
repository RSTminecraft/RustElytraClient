package dev.rstminecraft;

import net.minecraft.client.gui.DrawContext;
import net.minecraft.client.gui.screen.Screen;
import net.minecraft.text.Text;
import org.jetbrains.annotations.NotNull;

public class RSTSimpleScr extends RSTAbstractScr {
    private final Screen parent;
    protected final int buttonWidth;
    protected int row;
    protected int col;
    protected SrcEntry[] entry;
    protected RenderRecall recall;

    public RSTSimpleScr(Text title, SrcEntry[] entry, Screen parent, int row, int col, RenderRecall recall) {
        super(title);
        this.entry = entry;
        this.parent = parent;
        this.buttonWidth = Math.max(100, Math.min(300, (int) (this.width * 0.3)));
        this.row = row;
        this.col = col;
        this.recall = recall;
    }

    @Override
    protected void init() {
        drawWidget(entry, row, col, buttonWidth, width, height, textRenderer);
    }

    @Override
    public void render(DrawContext context, int mouseX, int mouseY, float delta) {
        super.render(context,mouseX,mouseY,delta);
        if(recall != null) recall.render(context, mouseX, mouseY, delta);
    }

    @Override
    public void close() {
        if (client != null) client.setScreen(parent);
    }

    public interface RenderRecall {
        void render(@NotNull DrawContext context, int mouseX, int mouseY, float delta);
    }
}
