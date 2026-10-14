package com.citygen.dev;

import com.citygen.gen.GenSettings;
import com.citygen.gen.GenSettings.Quarter;
import com.citygen.gen.GenSettings.QuarterKind;
import com.citygen.gen.Layout;

/** Пример города из шести кварталов для тестов, скриншотов и ролика. */
public final class DemoCity {
    private DemoCity() {}

    public static void make(GenSettings s, int cx, int cz) {
        s.arrangement = GenSettings.Arrangement.CITY;
        s.quarters.clear();
        int g = Layout.SHARED_GAP;
        int ox = cx - (60 + 40 + 60 + 2 * g) / 2, oz = cz - (60 + 48 + g) / 2;
        add(s, "Сити", QuarterKind.DOWNTOWN, ox, oz, 60, 60);
        add(s, "Центральный парк", QuarterKind.PARK, ox + 60 + g, oz, 40, 60);
        add(s, "Старый город", QuarterKind.HISTORIC, ox + 100 + 2 * g, oz, 60, 60);
        add(s, "Жилой квартал", QuarterKind.RESIDENTIAL, ox, oz + 60 + g, 60, 48);
        add(s, "Новостройка", QuarterKind.CONSTRUCTION, ox + 60 + g, oz + 60 + g, 40, 48);
        add(s, "Пригород", QuarterKind.SUBURB, ox + 100 + 2 * g, oz + 60 + g, 60, 48);
    }

    private static void add(GenSettings s, String name, QuarterKind k, int x, int z, int w, int d) {
        Quarter q = new Quarter();
        q.name = name; q.kind = k; q.x0 = x; q.z0 = z; q.x1 = x + w - 1; q.z1 = z + d - 1;
        q.seed = s.quarters.size() + 1;
        s.quarters.add(q);
    }
}
