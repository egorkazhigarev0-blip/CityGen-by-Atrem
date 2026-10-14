package com.citygen.client;

import com.mojang.blaze3d.vertex.DefaultVertexFormat;
import com.mojang.blaze3d.vertex.VertexFormat;
import net.minecraft.client.renderer.RenderType;

import java.util.OptionalDouble;

/** Линии, видимые сквозь блоки: контуры кварталов в режиме правки в мире не прячутся за деревьями и холмами. */
public final class SeeThrough extends RenderType {
    private SeeThrough(String n, VertexFormat f, VertexFormat.Mode m, int b, boolean c, boolean s, Runnable a, Runnable z) {
        super(n, f, m, b, c, s, a, z);
    }

    public static final RenderType LINES = create("citygen_lines_see_through", DefaultVertexFormat.POSITION_COLOR_NORMAL,
            VertexFormat.Mode.LINES, 1024, false, false,
            CompositeState.builder()
                    .setShaderState(RENDERTYPE_LINES_SHADER)
                    .setLineState(new LineStateShard(OptionalDouble.of(2.5)))
                    .setLayeringState(VIEW_OFFSET_Z_LAYERING)
                    .setTransparencyState(TRANSLUCENT_TRANSPARENCY)
                    .setOutputState(ITEM_ENTITY_TARGET)
                    .setWriteMaskState(COLOR_WRITE)
                    .setCullState(NO_CULL)
                    .setDepthTestState(NO_DEPTH_TEST)
                    .createCompositeState(false));
}
