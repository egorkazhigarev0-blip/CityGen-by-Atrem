package com.citygen.client;

import com.citygen.gen.GenSettings;
import com.citygen.gen.GenSettings.CellType;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.Font;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.components.AbstractWidget;
import net.minecraft.client.gui.narration.NarrationElementOutput;
import net.minecraft.network.chat.Component;
import net.minecraft.util.FormattedCharSequence;
import net.minecraft.util.Mth;
import net.minecraft.world.item.ItemStack;

import java.util.List;
import java.util.function.*;

/** Тема и собственные виджеты редактора: тёмная панель, цветной акцент, скруглённые элементы. */
public final class Ui {
    private Ui() {}

    // ---------------- тема ----------------
    public static final String[] ACCENT_NAMES = {"Лазурный", "Изумрудный", "Янтарный", "Аметист", "Коралловый", "Бирюзовый"};
    public static final int[] ACCENTS = {0xFF4FA3FF, 0xFF3CCB7F, 0xFFFFB443, 0xFFA779FF, 0xFFFF6B6B, 0xFF2FD4C4};
    public static final int TEXT = 0xFFE9EEF4, SUB = 0xFF97A3B3, DIM = 0xFF5F6B7A, WIDGET = 0xFF222B36, WIDGET_HOVER = 0xFF2B3644,
            BORDER = 0xFF364352, HEADER = 0xFF18202A, SIDEBAR = 0xFF141B23;

    public static int accent() { return ACCENTS[Mth.clamp(ClientPrefs.get().accent, 0, ACCENTS.length - 1)]; }

    public static int panel() {
        int a = Mth.clamp((int) (ClientPrefs.get().opacity * 2.55f), 120, 255);
        return (a << 24) | 0x0F151C;
    }

    public static int alpha(int c, float a) { return ((int) (((c >>> 24) & 255) * a) << 24) | (c & 0xFFFFFF); }

    public static int mix(int a, int b, float t) {
        int r = (int) (((a >> 16) & 255) * (1 - t) + ((b >> 16) & 255) * t);
        int g = (int) (((a >> 8) & 255) * (1 - t) + ((b >> 8) & 255) * t);
        int bl = (int) ((a & 255) * (1 - t) + (b & 255) * t);
        return 0xFF000000 | (r << 16) | (g << 8) | bl;
    }

    public static void rect(GuiGraphics g, int x, int y, int w, int h, int c) {
        if (w <= 2 || h <= 2) { g.fill(x, y, x + w, y + h, c); return; }
        g.fill(x + 1, y, x + w - 1, y + h, c);
        g.fill(x, y + 1, x + 1, y + h - 1, c);
        g.fill(x + w - 1, y + 1, x + w, y + h - 1, c);
    }

    public static void outline(GuiGraphics g, int x, int y, int w, int h, int c) {
        g.fill(x + 1, y, x + w - 1, y + 1, c);
        g.fill(x + 1, y + h - 1, x + w - 1, y + h, c);
        g.fill(x, y + 1, x + 1, y + h - 1, c);
        g.fill(x + w - 1, y + 1, x + w, y + h - 1, c);
    }

    public static void small(GuiGraphics g, Font f, String s, int x, int y, int c) {
        g.pose().pushPose();
        g.pose().translate(x, y, 0);
        g.pose().scale(0.75f, 0.75f, 1f);
        g.drawString(f, s, 0, 0, c, false);
        g.pose().popPose();
    }

    public static String fit(Font f, String s, int w) {
        if (f.width(s) <= w) return s;
        while (s.length() > 1 && f.width(s + "…") > w) s = s.substring(0, s.length() - 1);
        return s + "…";
    }

    // ---------------- база ----------------
    public abstract static class W extends AbstractWidget {
        public String help = "";

        protected W(int x, int y, int w, int h, String label) { super(x, y, w, h, Component.literal(label)); }

        public String help() { return help; }

        protected Font font() { return Minecraft.getInstance().font; }

        protected int bg() { return !active ? 0xFF1A2129 : isHoveredOrFocused() ? WIDGET_HOVER : WIDGET; }

        @Override protected void updateWidgetNarration(NarrationElementOutput out) { defaultButtonNarrationText(out); }
    }

    // ---------------- кнопка ----------------
    public static class Button extends W {
        public enum Kind { NORMAL, PRIMARY, GHOST, DANGER }
        private final Runnable action;
        private final Kind kind;
        private ItemStack icon = ItemStack.EMPTY;

        public Button(int x, int y, int w, int h, String label, Kind kind, Runnable action) {
            super(x, y, w, h, label);
            this.kind = kind; this.action = action;
        }

        public Button icon(ItemStack s) { this.icon = s; return this; }
        public Button help(String h) { this.help = h; return this; }

        @Override public void onClick(double mx, double my) { action.run(); }

        @Override
        protected void renderWidget(GuiGraphics g, int mx, int my, float pt) {
            boolean hov = isHoveredOrFocused() && active;
            int c;
            int tc = TEXT;
            switch (kind) {
                case PRIMARY -> { c = hov ? mix(accent(), 0xFFFFFFFF, 0.15f) : accent(); tc = 0xFF0B1118; }
                case DANGER -> c = hov ? 0xFF7A2E35 : 0xFF5A2329;
                case GHOST -> c = hov ? WIDGET_HOVER : 0x00000000;
                default -> c = bg();
            }
            if (!active) { c = 0xFF1A2129; tc = DIM; }
            rect(g, getX(), getY(), width, height, c);
            if (kind == Kind.NORMAL && hov) outline(g, getX(), getY(), width, height, alpha(accent(), 0.7f));
            Font f = font();
            String s = getMessage().getString();
            int iw = icon.isEmpty() ? 0 : 18;
            int tw = Math.min(f.width(s), width - 6 - iw);
            int tx = getX() + (width - tw - iw) / 2 + iw;
            if (!icon.isEmpty()) g.renderItem(icon, tx - 18, getY() + (height - 16) / 2);
            g.drawString(f, fit(f, s, width - 6 - iw), tx, getY() + (height - 8) / 2, tc, false);
        }
    }

    // ---------------- переключатель значений ----------------
    public static class Cycle<T> extends W {
        private final String name;
        private final List<T> values;
        private int idx;
        private final Function<T, String> label;
        private final Function<T, String> helpFn;
        private final Consumer<T> onChange;

        public Cycle(int x, int y, int w, int h, String name, List<T> values, T initial,
                     Function<T, String> label, Function<T, String> helpFn, Consumer<T> onChange) {
            super(x, y, w, h, name);
            this.name = name; this.values = values; this.label = label; this.helpFn = helpFn; this.onChange = onChange;
            this.idx = Math.max(0, values.indexOf(initial));
        }

        public static <E extends Enum<E> & GenSettings.Labeled> Cycle<E> of(int x, int y, int w, String name, E[] vals, E init, Consumer<E> on) {
            return new Cycle<>(x, y, w, 20, name, List.of(vals), init, GenSettings.Labeled::label, GenSettings.Labeled::help, on);
        }

        public T value() { return values.get(idx); }

        @Override public String help() {
            String h = helpFn == null ? "" : helpFn.apply(value());
            return (help.isEmpty() ? "" : help + " ") + h + "  [ЛКМ — дальше, ПКМ — назад]";
        }

        private void step(int d) {
            idx = Math.floorMod(idx + d, values.size());
            onChange.accept(value());
        }

        @Override
        public boolean mouseClicked(double mx, double my, int button) {
            if (!active || !visible || !clicked(mx, my) || (button != 0 && button != 1)) return false;
            playDownSound(Minecraft.getInstance().getSoundManager());
            step(button == 1 || mx < getX() + width * 0.3 ? -1 : 1);
            return true;
        }

        @Override
        protected void renderWidget(GuiGraphics g, int mx, int my, float pt) {
            rect(g, getX(), getY(), width, height, bg());
            if (isHoveredOrFocused() && active) outline(g, getX(), getY(), width, height, alpha(accent(), 0.7f));
            Font f = font();
            int ty = getY() + (height - 8) / 2;
            String v = label.apply(value());
            int nameW = Math.min(f.width(name), width / 2 - 4);
            g.drawString(f, fit(f, name, width / 2 - 4), getX() + 6, ty, active ? SUB : DIM, false);
            int avail = width - nameW - 26;
            String vs = fit(f, v, avail);
            int vx = getX() + width - 12 - f.width(vs);
            g.drawString(f, vs, vx, ty, active ? TEXT : DIM, false);
            int ac = active ? accent() : DIM;
            g.drawString(f, "‹", vx - 7, ty, ac, false);
            g.drawString(f, "›", getX() + width - 8, ty, ac, false);
        }
    }

    // ---------------- ползунок ----------------
    public static class Slider extends W {
        private final String name;
        private final int min, max;
        private int value;
        private final IntFunction<String> fmt;
        private final IntConsumer onChange;

        public Slider(int x, int y, int w, int h, String name, int min, int max, int value, IntFunction<String> fmt, IntConsumer onChange) {
            super(x, y, w, h, name);
            this.name = name; this.min = min; this.max = max; this.value = Mth.clamp(value, min, max);
            this.fmt = fmt; this.onChange = onChange;
        }

        public Slider help(String h) { this.help = h; return this; }

        private void fromMouse(double mx) {
            double t = (mx - (getX() + 5)) / (double) (width - 10);
            int v = Mth.clamp(min + (int) Math.round(t * (max - min)), min, max);
            if (v != value) { value = v; onChange.accept(v); }
        }

        @Override public void onClick(double mx, double my) { fromMouse(mx); }
        @Override protected void onDrag(double mx, double my, double dx, double dy) { fromMouse(mx); }

        @Override
        public boolean keyPressed(int key, int scan, int mods) {
            if (key == 263 || key == 262) { // стрелки
                int v = Mth.clamp(value + (key == 263 ? -1 : 1), min, max);
                if (v != value) { value = v; onChange.accept(v); }
                return true;
            }
            return super.keyPressed(key, scan, mods);
        }

        @Override
        protected void renderWidget(GuiGraphics g, int mx, int my, float pt) {
            rect(g, getX(), getY(), width, height, bg());
            if (isHoveredOrFocused() && active) outline(g, getX(), getY(), width, height, alpha(accent(), 0.7f));
            Font f = font();
            String v = fmt.apply(value);
            g.drawString(f, fit(f, name, width - f.width(v) - 14), getX() + 6, getY() + 3, active ? SUB : DIM, false);
            g.drawString(f, v, getX() + width - 6 - f.width(v), getY() + 3, active ? TEXT : DIM, false);
            int tx0 = getX() + 5, tx1 = getX() + width - 5, ty = getY() + height - 5;
            g.fill(tx0, ty, tx1, ty + 2, BORDER);
            double t = (value - min) / (double) Math.max(1, max - min);
            int kx = tx0 + (int) Math.round(t * (tx1 - tx0));
            g.fill(tx0, ty, kx, ty + 2, active ? accent() : DIM);
            rect(g, kx - 2, ty - 2, 5, 6, active ? 0xFFFFFFFF : DIM);
        }
    }

    // ---------------- тумблер ----------------
    public static class Toggle extends W {
        private final String name;
        private boolean on;
        private final Consumer<Boolean> onChange;

        public Toggle(int x, int y, int w, int h, String name, boolean on, Consumer<Boolean> onChange) {
            super(x, y, w, h, name);
            this.name = name; this.on = on; this.onChange = onChange;
        }

        public Toggle help(String h) { this.help = h; return this; }

        @Override public void onClick(double mx, double my) { on = !on; onChange.accept(on); }

        @Override
        protected void renderWidget(GuiGraphics g, int mx, int my, float pt) {
            rect(g, getX(), getY(), width, height, bg());
            if (isHoveredOrFocused() && active) outline(g, getX(), getY(), width, height, alpha(accent(), 0.7f));
            Font f = font();
            g.drawString(f, fit(f, name, width - 34), getX() + 6, getY() + (height - 8) / 2, active ? TEXT : DIM, false);
            int sw = 20, sh = 10, sx = getX() + width - sw - 6, sy = getY() + (height - sh) / 2;
            rect(g, sx, sy, sw, sh, on ? accent() : BORDER);
            int kx = on ? sx + sw - 9 : sx + 1;
            rect(g, kx, sy + 1, 8, sh - 2, 0xFFFFFFFF);
        }
    }

    // ---------------- заголовок / текст ----------------
    public static class Label extends W {
        private final int color;
        public Label(int x, int y, int w, String text, int color) {
            super(x, y, w, 12, text);
            this.color = color; this.active = false;
        }
        @Override public boolean mouseClicked(double mx, double my, int b) { return false; }
        @Override public boolean isMouseOver(double mx, double my) { return false; }
        @Override
        protected void renderWidget(GuiGraphics g, int mx, int my, float pt) {
            Font f = font();
            g.drawString(f, getMessage().getString().toUpperCase(), getX(), getY() + 2, color, false);
            int lw = f.width(getMessage().getString().toUpperCase()) + 6;
            g.fill(getX() + lw, getY() + 6, getX() + width, getY() + 7, BORDER);
        }
    }

    public static class Text extends W {
        private final List<FormattedCharSequence> lines;
        private final int color;
        public Text(int x, int y, int w, String text, int color) {
            super(x, y, w, 10, text);
            this.lines = Minecraft.getInstance().font.split(Component.literal(text), w);
            this.height = lines.size() * 10;
            this.color = color; this.active = false;
        }
        @Override public boolean mouseClicked(double mx, double my, int b) { return false; }
        @Override public boolean isMouseOver(double mx, double my) { return false; }
        @Override
        protected void renderWidget(GuiGraphics g, int mx, int my, float pt) {
            int y = getY();
            for (FormattedCharSequence l : lines) { g.drawString(font(), l, getX(), y, color, false); y += 10; }
        }
    }

    // ---------------- вкладка боковой панели ----------------
    public static class Tab extends W {
        private final ItemStack icon;
        private final boolean selected;
        private final Runnable action;
        public Tab(int x, int y, int w, int h, String label, ItemStack icon, boolean selected, Runnable action) {
            super(x, y, w, h, label);
            this.icon = icon; this.selected = selected; this.action = action;
        }
        @Override public void onClick(double mx, double my) { action.run(); }
        @Override
        protected void renderWidget(GuiGraphics g, int mx, int my, float pt) {
            if (selected) {
                g.fill(getX(), getY(), getX() + width, getY() + height, alpha(accent(), 0.18f));
                g.fill(getX(), getY(), getX() + 2, getY() + height, accent());
            } else if (isHoveredOrFocused()) g.fill(getX(), getY(), getX() + width, getY() + height, 0x22FFFFFF);
            g.renderItem(icon, getX() + 5, getY() + (height - 16) / 2);
            if (width > 40)
                g.drawString(font(), fit(font(), getMessage().getString(), width - 30), getX() + 25, getY() + (height - 8) / 2,
                        selected ? TEXT : SUB, false);
        }
    }

    // ---------------- кисть для кварталов ----------------
    public static class Chip extends W {
        private final CellType type;
        private final BooleanSupplier selected;
        private final Runnable action;
        public Chip(int x, int y, int w, int h, CellType type, BooleanSupplier selected, Runnable action) {
            super(x, y, w, h, type.label());
            this.type = type; this.selected = selected; this.action = action;
            this.help = type.label() + ": " + type.help() + "  Выберите и кликайте по клеткам таблицы.";
        }
        @Override public void onClick(double mx, double my) { action.run(); }
        @Override
        protected void renderWidget(GuiGraphics g, int mx, int my, float pt) {
            boolean sel = selected.getAsBoolean();
            rect(g, getX(), getY(), width, height, sel ? alpha(accent(), 0.35f) : bg());
            if (sel) outline(g, getX(), getY(), width, height, accent());
            rect(g, getX() + 3, getY() + 3, 8, height - 6, type.color);
            g.drawString(font(), fit(font(), type.label(), width - 16), getX() + 14, getY() + (height - 8) / 2, sel ? TEXT : SUB, false);
        }
    }

    // ---------------- таблица кварталов ----------------
    public static class Planner extends W {
        private final GenSettings s;
        private final Supplier<CellType> brush;
        private final Runnable changed;
        private int cw, ch, gx, gy;

        public Planner(int x, int y, int w, int h, GenSettings s, Supplier<CellType> brush, Runnable changed) {
            super(x, y, w, h, "Кварталы");
            this.s = s; this.brush = brush; this.changed = changed;
            this.help = "ЛКМ — поставить выбранное, ПКМ — сбросить в «Авто». Верх таблицы — дальние ряды, низ — ближе к вам.";
            layout();
        }

        private void layout() {
            cw = Math.max(14, Math.min(46, (width - 2) / s.cols));
            ch = Math.max(12, Math.min(28, (height - 14) / s.rows));
            gx = getX() + (width - cw * s.cols) / 2;
            gy = getY() + Math.max(0, (height - 12 - ch * s.rows) / 2);
        }

        private int[] cellAt(double mx, double my) {
            int c = (int) Math.floor((mx - gx) / cw), r = (int) Math.floor((my - gy) / ch);
            if (c < 0 || r < 0 || c >= s.cols || r >= s.rows) return null;
            return new int[]{r, c};
        }

        @Override
        public boolean mouseClicked(double mx, double my, int button) {
            if (!visible || !clicked(mx, my)) return false;
            int[] rc = cellAt(mx, my);
            if (rc == null) return false;
            s.setCell(rc[0], rc[1], button == 1 ? CellType.AUTO : brush.get());
            playDownSound(Minecraft.getInstance().getSoundManager());
            changed.run();
            return true;
        }

        @Override
        protected void renderWidget(GuiGraphics g, int mx, int my, float pt) {
            layout();
            Font f = font();
            int[] hov = isHovered ? cellAt(mx, my) : null;
            for (int r = 0; r < s.rows; r++)
                for (int c = 0; c < s.cols; c++) {
                    CellType t = s.cell(r, c);
                    int x = gx + c * cw, y = gy + r * ch;
                    int col = t == CellType.AUTO ? mix(t.color, accent(), 0.25f) : t.color;
                    rect(g, x + 1, y + 1, cw - 2, ch - 2, mix(col, 0xFF000000, 0.35f));
                    rect(g, x + 2, y + 2, cw - 4, ch - 4, col);
                    if (hov != null && hov[0] == r && hov[1] == c) outline(g, x + 1, y + 1, cw - 2, ch - 2, 0xFFFFFFFF);
                    String code = t.code;
                    g.drawString(f, code, x + (cw - f.width(code)) / 2, y + (ch - 8) / 2, 0xFF0B1118, false);
                }
            String me = "▲ вы здесь";
            g.drawString(f, me, gx + (cw * s.cols - f.width(me)) / 2, gy + ch * s.rows + 2, SUB, false);
        }
    }
}
