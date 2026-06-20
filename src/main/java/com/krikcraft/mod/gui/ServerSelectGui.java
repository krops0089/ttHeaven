package com.krikcraft.mod.gui;

import com.krikcraft.mod.KrikCraftMod;
import com.krikcraft.mod.scanner.Scanner;
import com.mojang.blaze3d.matrix.MatrixStack;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.screen.Screen;
import net.minecraft.client.gui.widget.button.Button;
import net.minecraft.util.text.StringTextComponent;

import java.util.ArrayList;
import java.util.List;

public class ServerSelectGui extends Screen {

    public static final List<String> SERVERS = new ArrayList<>();
    static {
        for (int i = 1001; i <= 1008; i++) SERVERS.add("Анархия-" + i);
        for (int i = 2001; i <= 2010; i++) SERVERS.add("Анархия-" + i);
        for (int i = 3001; i <= 3005; i++) SERVERS.add("Анархия-" + i);
        for (int i = 4001; i <= 4002; i++) SERVERS.add("Анархия-" + i);
        for (int i = 5001; i <= 5003; i++) SERVERS.add("Анархия-" + i);
        for (int i = 6001; i <= 6003; i++) SERVERS.add("Анархия-" + i);
    }

    private int scrollOffset = 0;
    private static final int PER_PAGE = 10;
    private static final int BTN_H = 18, BTN_W = 160;

    public ServerSelectGui() { super(new StringTextComponent("KrikCraft")); }

    @Override protected void init() { super.init(); rebuild(); }

    private void rebuild() {
        this.buttons.clear(); this.children.clear();
        int lx = this.width / 2 - BTN_W - 10, sy = 50;

        int end = Math.min(scrollOffset + PER_PAGE, SERVERS.size());
        for (int i = scrollOffset; i < end; i++) {
            final String srv = SERVERS.get(i);
            boolean sel = srv.equals(KrikCraftMod.currentAnarchy);
            String lbl = (sel ? "§a✔ " : "§7") + srv;
            int y = sy + (i - scrollOffset) * (BTN_H + 3);
            addButton(new Button(lx, y, BTN_W, BTN_H, new StringTextComponent(lbl), btn -> {
                KrikCraftMod.switchToServer(srv);
                rebuild();
            }));
        }

        // Прокрутка
        int ax = lx + BTN_W + 5;
        addButton(new Button(ax, sy, 20, BTN_H, new StringTextComponent("▲"), btn -> {
            if (scrollOffset > 0) { scrollOffset--; rebuild(); }
        }));
        addButton(new Button(ax, sy + (BTN_H+3)*(PER_PAGE-1), 20, BTN_H,
                new StringTextComponent("▼"), btn -> {
            if (scrollOffset + PER_PAGE < SERVERS.size()) { scrollOffset++; rebuild(); }
        }));

        // Правая панель
        int rx = this.width / 2 + 20, ry = 50;

        // Сканер вкл/выкл
        String scanLbl = Scanner.active ? "§aСканер: ВКЛ" : "§cСканер: ВЫКЛ";
        addButton(new Button(rx, ry, BTN_W, BTN_H, new StringTextComponent(scanLbl), btn -> {
            if (Scanner.active) Scanner.stop(); else Scanner.start();
            rebuild();
        }));

        // Автосмена вкл/выкл
        String asLbl = KrikCraftMod.autoSwitchEnabled ? "§aАвтосмена: ВКЛ" : "§cАвтосмена: ВЫКЛ";
        addButton(new Button(rx, ry + (BTN_H+4), BTN_W, BTN_H,
                new StringTextComponent(asLbl), btn -> {
            KrikCraftMod.autoSwitchEnabled = !KrikCraftMod.autoSwitchEnabled;
            KrikCraftMod.resetAutoSwitchTimer();
            rebuild();
        }));

        // Таймаут: 3 → 5 → 10 → 3
        addButton(new Button(rx, ry + (BTN_H+4)*2, BTN_W, BTN_H,
                new StringTextComponent("§eТаймаут: " + KrikCraftMod.autoSwitchMinutes + " мин"),
                btn -> {
                    if      (KrikCraftMod.autoSwitchMinutes == 3)  KrikCraftMod.autoSwitchMinutes = 5;
                    else if (KrikCraftMod.autoSwitchMinutes == 5)  KrikCraftMod.autoSwitchMinutes = 10;
                    else                                            KrikCraftMod.autoSwitchMinutes = 3;
                    rebuild();
                }));

        // /fly
        addButton(new Button(rx, ry + (BTN_H+4)*3, BTN_W, BTN_H,
                new StringTextComponent("§e/fly"),
                btn -> Minecraft.getInstance().player.sendChatMessage("/fly")));

        // Сброс кэша
        addButton(new Button(rx, ry + (BTN_H+4)*4, BTN_W, BTN_H,
                new StringTextComponent("§bСбросить кэш"), btn -> {
            Scanner.reset();
            chat("§a[KrikCraft] Кэш сброшен.");
        }));

        // Закрыть
        addButton(new Button(this.width/2 - 60, this.height - 28, 120, 20,
                new StringTextComponent("§fЗакрыть"), btn -> onClose()));
    }

    @Override
    public void render(MatrixStack ms, int mx, int my, float pt) {
        renderBackground(ms);
        drawCenteredString(ms, font, "§6§lKrikCraft §r§7— Управление", width/2, 12, 0xFFFFFF);
        drawCenteredString(ms, font, "§7Текущая: §b" + KrikCraftMod.currentAnarchy,
                width/2, 26, 0xFFFFFF);
        drawString(ms, font, "§7Серверы:", width/2 - BTN_W - 10, 40, 0xAAAAAA);
        drawString(ms, font, "§7Управление:", width/2 + 20, 40, 0xAAAAAA);

        if (KrikCraftMod.autoSwitchEnabled) {
            long rem = KrikCraftMod.autoSwitchRemainingSeconds();
            drawString(ms, font,
                    "§7До смены: §e" + (rem/60) + "м " + (rem%60) + "с",
                    width/2 + 20, this.height - 44, 0xFFFFFF);
        }
        super.render(ms, mx, my, pt);
    }

    private void chat(String msg) {
        Minecraft mc = Minecraft.getInstance();
        if (mc.player != null)
            mc.player.sendMessage(new StringTextComponent(msg), mc.player.getUniqueID());
    }

    @Override public boolean isPauseScreen() { return false; }
}
