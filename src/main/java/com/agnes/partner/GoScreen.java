package com.agnes.partner;

import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.screens.MenuScreens;
import net.minecraft.client.gui.screens.inventory.AbstractContainerScreen;
import net.minecraft.network.chat.Component;
import net.minecraft.world.entity.player.Inventory;
import net.minecraftforge.api.distmarker.Dist;
import net.minecraftforge.eventbus.api.SubscribeEvent;
import net.minecraftforge.fml.common.Mod;
import net.minecraftforge.fml.event.lifecycle.FMLClientSetupEvent;

@Mod.EventBusSubscriber(modid = AgnesPartnerMod.MOD_ID, bus = Mod.EventBusSubscriber.Bus.MOD, value = Dist.CLIENT)
public final class GoScreen extends AbstractContainerScreen<GoMenu> {
    private int originX, originY, spacing, extent, pendingSize;
    private boolean pendingResign;
    private Button pass, resign, confirm, resume;
    private Button goMode, gomokuMode, changeMode;
    private Button newGomoku;
    private final java.util.List<Button> sizes = new java.util.ArrayList<>();
    public GoScreen(GoMenu menu, Inventory inventory, Component title) { super(menu, inventory, title); }
    @SubscribeEvent public static void setup(FMLClientSetupEvent event) { event.enqueueWork(() -> MenuScreens.register(GoContent.MENU.get(), GoScreen::new)); }
    @Override protected void init() {
        imageWidth = Math.min(440, width - 12); imageHeight = Math.min(352, height - 12);
        super.init(); sizes.clear();
        int side = leftPos + imageWidth - 104, top = topPos + 27;
        pass = button(side, top + 34, "停一手", 400);
        resign = button(side, top + 58, "认输", 401);
        confirm = button(side, top + 82, "确认计分", 402);
        resume = button(side, top + 106, "继续落子", 403);
        goMode = addRenderableWidget(Button.builder(Component.literal("围棋 / 续局"), b -> send(500)).bounds(leftPos + imageWidth / 2 - 114, topPos + 115, 108, 24).build());
        gomokuMode = addRenderableWidget(Button.builder(Component.literal("五子棋 / 续局"), b -> send(501)).bounds(leftPos + imageWidth / 2 + 6, topPos + 115, 108, 24).build());
        changeMode = addRenderableWidget(Button.builder(Component.literal("换棋类"), b -> { pendingSize = 0; pendingResign = false; send(502); }).bounds(side, top + 10, 96, 20).build());
        newGomoku = addRenderableWidget(Button.builder(Component.literal("五子棋新局"), b -> {
            if (pendingSize == 15) { send(415); pendingSize = 0; } else pendingSize = 15;
        }).bounds(side, top + 106, 96, 20).build());
        int column = side;
        for (int n : new int[]{9, 13, 19}) {
            Button b = Button.builder(Component.literal(n + " 路新局"), clicked -> {
                pendingResign = false;
                if (pendingSize == n) { send(400 + n); pendingSize = 0; }
                else pendingSize = n;
            }).bounds(column, top + 140, 30, 20).build();
            sizes.add(addRenderableWidget(b)); column += 33;
        }
    }
    private Button button(int x, int y, String label, int action) {
        return addRenderableWidget(Button.builder(Component.literal(label), b -> {
            pendingSize = 0;
            if (action == 401 && !pendingResign) pendingResign = true;
            else { pendingResign = false; send(action); }
        }).bounds(x, y, 96, 20).build());
    }
    private void send(int action) { GoContent.send(menu.containerId, action); }
    private void geometry() {
        int available = Math.min(imageWidth - 130, imageHeight - 95);
        spacing = Math.max(5, available / (menu.size() - 1)); extent = spacing * (menu.size() - 1);
        originX = leftPos + 18; originY = topPos + 35;
    }
    @Override public void render(GuiGraphics g, int mouseX, int mouseY, float partialTick) {
        geometry();
        boolean chooser = menu.mode() == 0;
        goMode.visible = gomokuMode.visible = chooser;
        changeMode.visible = !chooser;
        resign.visible = !chooser;
        pass.visible = confirm.visible = resume.visible = menu.mode() == 1;
        newGomoku.visible = menu.mode() == 2;
        newGomoku.setMessage(Component.literal(pendingSize == 15 ? "再点确认新局" : "五子棋新局"));
        for (Button b : sizes) b.visible = menu.mode() == 1;
        if (chooser) { renderBackground(g); super.render(g, mouseX, mouseY, partialTick); g.drawCenteredString(font, "选择游戏模式", leftPos + imageWidth / 2, topPos + 75, 0xFFFFDE98); return; }
        pass.active = menu.phase() == 0 && menu.turn() == 1;
        resign.active = menu.phase() < 2;
        resign.setMessage(Component.literal(pendingResign ? "再点确认认输" : "认输"));
        confirm.active = resume.active = menu.phase() == 1;
        int[] ns = {9, 13, 19};
        for (int i = 0; i < sizes.size(); i++) sizes.get(i).setMessage(Component.literal(ns[i] + (pendingSize == ns[i] ? "!" : "路")));
        renderBackground(g); super.render(g, mouseX, mouseY, partialTick);
        String hint = pendingSize != 0 ? "再次点击 " + pendingSize + " 路：覆盖当前棋局，重新开始。"
            : pendingResign ? "再次点击认输将结束本局；点击棋盘可取消确认。"
            : menu.error() != 0 ? "此处不能落子：占用、无气、重复局面或未轮到你。"
            : menu.phase() == 1 ? "双方停手：点击死子整组标记，再确认计分；有争议可继续。"
            : menu.phase() == 0 ? "你执黑先行；女仆执白。Esc 保存并离开，不会弃局。"
            : "本局已结束，点击右侧按钮可另开一局。";
        g.drawWordWrap(font, Component.literal(hint), leftPos + 10, topPos + imageHeight - 30, imageWidth - 20, 0xFFEEEEEE);
    }
    @Override protected void renderBg(GuiGraphics g, float partialTick, int mx, int my) {
        g.fill(leftPos, topPos, leftPos + imageWidth, topPos + imageHeight, 0xF5222932);
        if (menu.mode() == 0) return;
        g.fill(originX - 9, originY - 9, originX + extent + 10, originY + extent + 10, 0xFFD4AC6A);
        for (int i = 0; i < menu.size(); i++) {
            g.fill(originX, originY + i * spacing, originX + extent + 1, originY + i * spacing + 1, 0xFF59432B);
            g.fill(originX + i * spacing, originY, originX + i * spacing + 1, originY + extent + 1, 0xFF59432B);
        }
        int low = menu.size() == 9 ? 2 : 3, middle = menu.size() / 2;
        for (int x : new int[]{low, middle, menu.size() - 1 - low}) for (int y : new int[]{low, middle, menu.size() - 1 - low})
            g.fill(originX + x * spacing - 1, originY + y * spacing - 1, originX + x * spacing + 2, originY + y * spacing + 2, 0xFF59432B);
        for (int p = 0; p < menu.size() * menu.size(); p++) {
            int value = menu.cell(p); if (value == 0) continue;
            int x = originX + p % menu.size() * spacing, y = originY + p / menu.size() * spacing;
            int radius = Math.max(2, spacing / 2 - 1), color = value % 2 == 1 ? 0xFF15171A : 0xFFF2F1E9;
            for (int dy = -radius; dy <= radius; dy++) {
                int dx = (int)Math.sqrt(radius * radius - dy * dy);
                g.fill(x - dx, y + dy, x + dx + 1, y + dy + 1, color);
            }
            if (value > 2) { g.fill(x - 2, y - 1, x + 3, y + 2, 0xFFE85959); g.fill(x - 1, y - 2, x + 2, y + 3, 0xFFE85959); }
            else if (p == menu.last()) g.fill(x - 1, y - 1, x + 2, y + 2, 0xFFE85A42);
        }
    }
    @Override protected void renderLabels(GuiGraphics g, int mx, int my) {
        g.drawString(font, font.plainSubstrByWidth(title.getString(), imageWidth - 20), 10, 10, 0xFFFFFFFF, false);
        if (menu.mode() == 0) return;
        String status = menu.mode() == 2 ? switch (menu.phase()) {
            case 2 -> menu.turn() == 1 ? "黑胜 · 你赢了" : "白胜 · 女仆赢了";
            case 3 -> "你认输";
            case 4 -> "棋盘已满 · 和棋";
            default -> menu.turn() == 1 ? "轮到你（黑）" : "女仆思考中…";
        } : switch (menu.phase()) {
            case 1 -> "标记死子";
            case 2 -> menu.blackScore() > menu.whiteScore() ? "黑胜 · 你赢了" : "白胜 · 女仆赢了";
            case 3 -> "你认输 · 白胜";
            default -> menu.turn() == 1 ? (menu.passes() == 1 ? "女仆停手·你落子" : "轮到你（黑）") : "女仆思考中…";
        };
        g.drawString(font, status, imageWidth - 104, 28, 0xFFFFDE98, false);
        if (menu.mode() == 2) g.drawString(font, "五子棋 · 五连即胜", 10, imageHeight - 45, 0xFFFFFFFF, false);
        else if (menu.phase() == 1 || menu.phase() == 2) {
            g.drawString(font, "黑 " + menu.blackScore() + "  白 " + menu.whiteScore(), 10, imageHeight - 45, 0xFFFFFFFF, false);
        } else g.drawString(font, "提子 你 " + menu.blackCaptures() + " / 女仆 " + menu.whiteCaptures(), 10, imageHeight - 45, 0xFFFFFFFF, false);
    }
    @Override public boolean mouseClicked(double mx, double my, int button) {
        geometry();
        if (menu.mode() == 0) return super.mouseClicked(mx, my, button);
        if (button == 0) {
            int x = (int)Math.round((mx - originX) / spacing), y = (int)Math.round((my - originY) / spacing);
            if (x >= 0 && y >= 0 && x < menu.size() && y < menu.size()
                && Math.abs(mx - originX - x * spacing) <= spacing * 0.48 && Math.abs(my - originY - y * spacing) <= spacing * 0.48) {
                pendingSize = 0; pendingResign = false; send(y * menu.size() + x); return true;
            }
        }
        return super.mouseClicked(mx, my, button);
    }
}
