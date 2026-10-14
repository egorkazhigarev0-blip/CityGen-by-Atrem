package com.citygen.client;

import com.citygen.CityGen;
import com.citygen.gen.GenSettings;
import net.minecraft.Util;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.Font;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.components.AbstractWidget;
import net.minecraft.client.gui.narration.NarrationElementOutput;
import net.minecraft.network.chat.Component;
import net.minecraft.network.chat.MutableComponent;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.util.FormattedCharSequence;
import net.minecraft.util.Mth;
import net.minecraft.world.item.ItemStack;

import java.util.List;
import java.util.function.*;

/**
 * Тема и собственные виджеты редактора: тёмные скруглённые панели, цветной акцент, плавная подсветка,
 * собственный шрифт интерфейса (Noto Sans) с запасным пиксельным шрифтом Minecraft.
 */
public final class Ui {
    private Ui() {}

    // ---------------- тема ----------------
    public static final String[] ACCENT_NAMES = {"Лазурный", "Изумрудный", "Янтарный", "Аметист", "Коралловый", "Бирюзовый", "Розовый"};
    public static final int[] ACCENTS = {0xFF4FA3FF, 0xFF3CCB7F, 0xFFFFB443, 0xFFA779FF, 0xFFFF6B6B, 0xFF2FD4C4, 0xFFFF7EC8};
    public static final int TEXT = 0xFFEDF1F6, SUB = 0xFF9BA7B6, DIM = 0xFF5F6B7A, WIDGET = 0xFF1F2833, WIDGET_HOVER = 0xFF29343F,
            BORDER = 0xFF323E4C, HEADER = 0xFF151C25, SIDEBAR = 0xFF121920, CARD = 0xFF19212B, OK = 0xFF4CD08A, WARN = 0xFFFFC857,
            BAD = 0xFFFF6B6B;
    @SuppressWarnings("removal")
    public static final ResourceLocation FONT_ID = new ResourceLocation(CityGen.MODID, "ui");

    public static int accent() { return ACCENTS[Mth.clamp(ClientPrefs.get().accent, 0, ACCENTS.length - 1)]; }

    public static int panel() {
        int a = Mth.clamp((int) (ClientPrefs.get().opacity * 2.55f), 120, 255);
        return (a << 24) | 0x0E141B;
    }

    public static int alpha(int c, float a) { return ((int) (((c >>> 24) & 255) * Mth.clamp(a, 0f, 1f)) << 24) | (c & 0xFFFFFF); }

    public static int mix(int a, int b, float t) {
        t = Mth.clamp(t, 0f, 1f);
        int aa = (int) (((a >>> 24) & 255) * (1 - t) + ((b >>> 24) & 255) * t);
        int r = (int) (((a >> 16) & 255) * (1 - t) + ((b >> 16) & 255) * t);
        int g = (int) (((a >> 8) & 255) * (1 - t) + ((b >> 8) & 255) * t);
        int bl = (int) ((a & 255) * (1 - t) + (b & 255) * t);
        return (aa << 24) | (r << 16) | (g << 8) | bl;
    }

    // ---------------- текст ----------------
    public static Font font() { return Minecraft.getInstance().font; }

    public static boolean modern() { return ClientPrefs.get().modernFont; }

    /** Текст в шрифте интерфейса (или стандартном, если современный шрифт выключен). */
    public static MutableComponent c(String s) {
        MutableComponent m = Component.literal(s);
        return modern() ? m.withStyle(st -> st.withFont(FONT_ID)) : m;
    }

    public static int width(String s) { return font().width(c(s)); }

    public static void text(GuiGraphics g, String s, int x, int y, int col) { g.drawString(font(), c(s), x, y, col, false); }

    public static void shadow(GuiGraphics g, String s, int x, int y, int col) { g.drawString(font(), c(s), x, y, col, true); }

    public static void center(GuiGraphics g, String s, int cx, int y, int col) { text(g, s, cx - width(s) / 2, y, col); }

    public static void scaled(GuiGraphics g, String s, float x, float y, int col, float k) {
        g.pose().pushPose();
        g.pose().translate(x, y, 0);
        g.pose().scale(k, k, 1f);
        g.drawString(font(), c(s), 0, 0, col, false);
        g.pose().popPose();
    }

    public static void small(GuiGraphics g, String s, int x, int y, int col) { scaled(g, s, x, y, col, 0.75f); }

    public static int smallWidth(String s) { return Mth.ceil(width(s) * 0.75f); }

    public static String fit(String s, int w) {
        if (width(s) <= w) return s;
        while (s.length() > 1 && width(s + "…") > w) s = s.substring(0, s.length() - 1);
        return s + "…";
    }

    public static List<FormattedCharSequence> wrap(String s, int w) { return font().split(c(s), Math.max(10, w)); }

    // ---------------- фигуры ----------------
    private static int inset(int r, int i) { return r - (int) Math.floor(Math.sqrt((double) r * r - (double) (r - i) * (r - i))); }

    /** Скруглённый прямоугольник (пиксельное скругление радиуса r). */
    public static void round(GuiGraphics g, int x, int y, int w, int h, int r, int c) {
        if ((c >>> 24) == 0 || w <= 0 || h <= 0) return;
        r = Math.min(r, Math.min(w, h) / 2);
        if (r <= 0) { g.fill(x, y, x + w, y + h, c); return; }
        g.fill(x, y + r, x + w, y + h - r, c);
        for (int i = 0; i < r; i++) {
            int k = inset(r, i);
            g.fill(x + k, y + i, x + w - k, y + i + 1, c);
            g.fill(x + k, y + h - 1 - i, x + w - k, y + h - i, c);
        }
    }

    /** Скруглённая плашка с рамкой: рамка рисуется подложкой, заливка — поверх (для непрозрачных цветов). */
    public static void card(GuiGraphics g, int x, int y, int w, int h, int r, int fill, int border) {
        round(g, x, y, w, h, r, border);
        round(g, x + 1, y + 1, w - 2, h - 2, Math.max(0, r - 1), fill);
    }

    /** Мягкая тень вокруг панели. */
    public static void softShadow(GuiGraphics g, int x, int y, int w, int h, int r) {
        for (int i = 4; i >= 1; i--) round(g, x - i, y - i + 2, w + 2 * i, h + 2 * i, r + i, alpha(0xFF000000, 0.09f));
    }

    public static void rect(GuiGraphics g, int x, int y, int w, int h, int c) { round(g, x, y, w, h, 2, c); }

    public static void outline(GuiGraphics g, int x, int y, int w, int h, int c) {
        g.fill(x + 2, y, x + w - 2, y + 1, c);
        g.fill(x + 2, y + h - 1, x + w - 2, y + h, c);
        g.fill(x, y + 2, x + 1, y + h - 2, c);
        g.fill(x + w - 1, y + 2, x + w, y + h - 2, c);
        g.fill(x + 1, y + 1, x + 2, y + 2, c);
        g.fill(x + w - 2, y + 1, x + w - 1, y + 2, c);
        g.fill(x + 1, y + h - 2, x + 2, y + h - 1, c);
        g.fill(x + w - 2, y + h - 2, x + w - 1, y + h - 1, c);
    }

    public static void gradient(GuiGraphics g, int x, int y, int w, int h, int top, int bottom) { g.fillGradient(x, y, x + w, y + h, top, bottom); }

    public static void hline(GuiGraphics g, int x0, int x1, int y, int c) { g.fill(Math.min(x0, x1), y, Math.max(x0, x1) + 1, y + 1, c); }

    public static void vline(GuiGraphics g, int x, int y0, int y1, int c) { g.fill(x, Math.min(y0, y1), x + 1, Math.max(y0, y1) + 1, c); }

    /** Плавное значение 0..1 для анимаций (без зависимости от FPS). */
    public static float approach(float cur, float target, long[] lastT, float ms) {
        long now = Util.getMillis();
        float dt = lastT[0] == 0 ? 1f : Mth.clamp((now - lastT[0]) / ms, 0f, 1f);
        lastT[0] = now;
        return cur + (target - cur) * dt;
    }

    // ---------------- база ----------------
    public abstract static class W extends AbstractWidget {
        public String help = "";
        protected float hover;
        private final long[] last = {0};

        protected W(int x, int y, int w, int h, String label) { super(x, y, w, h, Component.literal(label)); }

        public String help() { return help; }

        protected Font font() { return Minecraft.getInstance().font; }

        /** Обновляет и возвращает плавную подсветку при наведении. */
        protected float hov() {
            hover = approach(hover, isHoveredOrFocused() && active ? 1f : 0f, last, 110f);
            return hover;
        }

        protected int bg() { return !active ? 0xFF171E26 : mix(WIDGET, WIDGET_HOVER, hov()); }

        protected void frame(GuiGraphics g, int fill) {
            float h = hover;
            int border = active ? mix(BORDER, alpha(accent(), 0.85f), h) : 0xFF1E262F;
            card(g, getX(), getY(), width, height, 3, fill, border);
        }

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
            float h = hov();
            int tc = TEXT;
            int x = getX(), y = getY();
            if (!active) {
                card(g, x, y, width, height, 3, 0xFF161C23, 0xFF1E262F);
                tc = DIM;
            } else switch (kind) {
                case PRIMARY -> {
                    int a = accent(), top = mix(a, 0xFFFFFFFF, 0.18f + 0.12f * h), bot = mix(a, 0xFF000000, 0.12f - 0.1f * h);
                    round(g, x, y, width, height, 3, mix(a, 0xFF000000, 0.35f));
                    g.fillGradient(x + 1, y + 1, x + width - 1, y + height - 1, top, bot);
                    tc = 0xFF0A1016;
                }
                case DANGER -> card(g, x, y, width, height, 3, mix(0xFF4A1F25, 0xFF6E2A33, h), mix(0xFF5E2830, 0xFFFF6B6B, h * 0.7f));
                case GHOST -> { if (h > 0.01f) round(g, x, y, width, height, 3, alpha(0x33FFFFFF, h)); tc = mix(SUB, TEXT, h); }
                default -> frame(g, bg());
            }
            String s = getMessage().getString();
            int iw = icon.isEmpty() ? 0 : 18;
            String shown = fit(s, width - 8 - iw);
            int tw = width(shown);
            int tx = x + (width - tw - iw) / 2 + iw;
            if (!icon.isEmpty()) g.renderItem(icon, tx - 18, y + (height - 16) / 2);
            text(g, shown, tx, y + (height - 8) / 2, tc);
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

        public Cycle<T> help(String h) { this.help = h; return this; }

        public T value() { return values.get(idx); }

        @Override public String help() {
            String h = helpFn == null ? "" : helpFn.apply(value());
            return (help.isEmpty() ? "" : help + " ") + h + "  [ЛКМ — дальше, ПКМ — назад, Shift+колесо — листать]";
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
        public boolean mouseScrolled(double mx, double my, double delta) {
            if (!active || !visible || !isMouseOver(mx, my) || !net.minecraft.client.gui.screens.Screen.hasShiftDown()) return false;
            step(delta > 0 ? -1 : 1);
            return true;
        }

        @Override
        public boolean keyPressed(int key, int scan, int mods) {
            if (key == 263 || key == 262) { step(key == 263 ? -1 : 1); return true; }
            return super.keyPressed(key, scan, mods);
        }

        @Override
        protected void renderWidget(GuiGraphics g, int mx, int my, float pt) {
            frame(g, bg());
            int ty = getY() + (height - 8) / 2 - (values.size() <= 14 ? 1 : 0);
            String v = label.apply(value());
            String nm = fit(name, width / 2 - 6);
            int nameW = width(nm);
            text(g, nm, getX() + 7, ty, active ? SUB : DIM);
            String vs = fit(v, width - nameW - 30);
            int vx = getX() + width - 13 - width(vs);
            text(g, vs, vx, ty, active ? TEXT : DIM);
            int ac = active ? mix(accent(), 0xFFFFFFFF, hover * 0.3f) : DIM;
            text(g, "‹", vx - 8, ty, ac);
            text(g, "›", getX() + width - 9, ty, ac);
            // индикатор положения: сегменты внизу
            int n = values.size();
            if (n > 1 && n <= 14 && height >= 18) {
                int x0 = getX() + 7, x1 = getX() + width - 7, sy = getY() + height - 4;
                float seg = (x1 - x0) / (float) n;
                for (int i = 0; i < n; i++) {
                    int a = x0 + Math.round(i * seg), b = x0 + Math.round((i + 1) * seg) - 1;
                    g.fill(a, sy, Math.max(a + 1, b), sy + 1, i == idx ? (active ? accent() : DIM) : 0x30FFFFFF);
                }
            }
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
            double t = (mx - (getX() + 7)) / (double) (width - 14);
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
            frame(g, bg());
            String v = fmt.apply(value);
            int vw = width(v);
            text(g, fit(name, width - vw - 16), getX() + 7, getY() + 3, active ? SUB : DIM);
            text(g, v, getX() + width - 7 - vw, getY() + 3, active ? TEXT : DIM);
            int tx0 = getX() + 7, tx1 = getX() + width - 7, ty = getY() + height - 6;
            round(g, tx0, ty, tx1 - tx0, 2, 1, 0xFF2E3946);
            double t = (value - min) / (double) Math.max(1, max - min);
            int kx = tx0 + (int) Math.round(t * (tx1 - tx0));
            if (kx > tx0) g.fill(tx0, ty, kx, ty + 2, active ? accent() : DIM);
            int kw = 5 + Math.round(hover);
            round(g, kx - kw / 2, ty - 3, kw, 8, 2, active ? 0xFFFFFFFF : DIM);
        }
    }

    // ---------------- тумблер ----------------
    public static class Toggle extends W {
        private final String name;
        private boolean on;
        private float knob;
        private final long[] lastK = {0};
        private final Consumer<Boolean> onChange;

        public Toggle(int x, int y, int w, int h, String name, boolean on, Consumer<Boolean> onChange) {
            super(x, y, w, h, name);
            this.name = name; this.on = on; this.onChange = onChange; this.knob = on ? 1 : 0;
        }

        public Toggle help(String h) { this.help = h; return this; }

        @Override public void onClick(double mx, double my) { on = !on; onChange.accept(on); }

        @Override
        protected void renderWidget(GuiGraphics g, int mx, int my, float pt) {
            frame(g, bg());
            knob = approach(knob, on ? 1f : 0f, lastK, 90f);
            text(g, fit(name, width - 38), getX() + 7, getY() + (height - 8) / 2, active ? TEXT : DIM);
            int sw = 22, sh = 12, sx = getX() + width - sw - 7, sy = getY() + (height - sh) / 2;
            round(g, sx, sy, sw, sh, 6, active ? mix(0xFF2E3946, accent(), knob) : 0xFF252D36);
            int kx = sx + 1 + Math.round(knob * (sw - 12));
            round(g, kx, sy + 1, 10, 10, 5, active ? 0xFFFFFFFF : DIM);
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
            String t = getMessage().getString().toUpperCase();
            round(g, getX(), getY() + 3, 3, 6, 1, color);
            scaled(g, t, getX() + 7, getY() + 3.5f, color, 0.85f);
            int lw = Mth.ceil(width(t) * 0.85f) + 12;
            g.fill(getX() + lw, getY() + 6, getX() + width, getY() + 7, BORDER);
        }
    }

    public static class Text extends W {
        private final List<FormattedCharSequence> lines;
        private final int color;
        public Text(int x, int y, int w, String text, int color) {
            super(x, y, w, 10, text);
            this.lines = wrap(text, w);
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
            float h = hov();
            int x = getX() + 3, w = width - 6;
            if (selected) {
                round(g, x, getY(), w, height, 3, alpha(accent(), 0.20f));
                round(g, x, getY() + 4, 2, height - 8, 1, accent());
            } else if (h > 0.01f) round(g, x, getY(), w, height, 3, alpha(0x1AFFFFFF, h));
            g.renderItem(icon, x + 4, getY() + (height - 16) / 2);
            if (width > 40)
                text(g, fit(getMessage().getString(), w - 28), x + 24, getY() + (height - 8) / 2, selected ? TEXT : mix(SUB, TEXT, h));
        }
    }

    // ---------------- плитка выбора (список кварталов и т.п.) ----------------
    public static class Tile extends W {
        private final int swatch;
        private final String sub;
        private final BooleanSupplier selected;
        private final Runnable action;
        public Tile(int x, int y, int w, int h, String title, String sub, int swatch, BooleanSupplier selected, Runnable action) {
            super(x, y, w, h, title);
            this.sub = sub; this.swatch = swatch; this.selected = selected; this.action = action;
        }
        public Tile help(String h) { this.help = h; return this; }
        @Override public void onClick(double mx, double my) { action.run(); }
        @Override
        protected void renderWidget(GuiGraphics g, int mx, int my, float pt) {
            float h = hov();
            boolean sel = selected.getAsBoolean();
            int border = sel ? accent() : mix(BORDER, alpha(accent(), 0.8f), h);
            card(g, getX(), getY(), width, height, 3, sel ? mix(WIDGET, accent(), 0.16f) : bg(), border);
            round(g, getX() + 4, getY() + 4, 4, height - 8, 2, swatch);
            text(g, fit(getMessage().getString(), width - 18), getX() + 12, getY() + 3, TEXT);
            if (!sub.isEmpty() && height >= 20) small(g, fit(sub, (int) ((width - 18) / 0.75f)), getX() + 12, getY() + height - 9, SUB);
        }
    }
}
