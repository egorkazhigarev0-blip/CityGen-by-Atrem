package com.citygen.gen;

/** Типы зданий. Значения по умолчанию подобраны по реальным пропорциям (1 блок ≈ 1 м). */
public enum BuildingType implements GenSettings.Labeled {
    SKYSCRAPER("Небоскрёб", 30, 50, 25, 25, 13, 13,
            "Башня 30–60 этажей: подиум с лобби, витраж или сетка, уступы, корона и шпиль."),
    OFFICE("Офисный центр", 8, 16, 28, 18, 11, 9,
            "Деловое здание 8–20 этажей: ленточные окна или витраж, открытые офисы внутри."),
    HIGHRISE("Многоэтажка", 10, 20, 26, 14, 11, 9,
            "Жилой дом 10–25 этажей: балконы и лоджии, квартиры, лифт и лестничная клетка."),
    MIDRISE("Средняя плотность", 4, 7, 22, 16, 9, 9,
            "Городской дом 4–8 этажей: магазины и кафе внизу, квартиры выше, карниз или мансарда."),
    TOWNHOUSE("Таунхаус", 2, 4, 7, 12, 5, 7,
            "Узкий дом 2–4 этажа. Промежуток 0 — сплошная улица, как в Амстердаме."),
    HOUSE("Частный дом", 1, 2, 11, 9, 7, 7,
            "Дом на 1–2 этажа с палисадником, крыльцом и садом."),
    COMMERCIAL("Торговый центр", 1, 3, 40, 26, 14, 12,
            "Витрины по периметру, супермаркет и бутики внутри, зенитные фонари на крыше."),
    CIVIC("Общественное здание", 2, 4, 30, 22, 13, 11,
            "Музей, библиотека или театр: портик, высокие залы, купол или фронтон."),
    MIXED("Микс (квартал)", 0, 0, 0, 0, 9, 9,
            "Каждое здание получает свой тип: офисы, многоэтажки, дома, иногда небоскрёб. "
                    + "Этажность и размеры берутся из типа.");

    public final String label, help;
    public final int defMin, defMax, defW, defD, minW, minD;

    BuildingType(String label, int defMin, int defMax, int defW, int defD, int minW, int minD, String help) {
        this.label = label; this.defMin = defMin; this.defMax = defMax; this.defW = defW; this.defD = defD;
        this.minW = minW; this.minD = minD; this.help = help;
    }

    @Override public String label() { return label; }
    @Override public String help() { return help; }

    /** Примерная «высотность» для сортировки в режиме Микс. */
    public int rank() {
        return switch (this) {
            case SKYSCRAPER -> 6; case OFFICE -> 4; case HIGHRISE -> 5; case MIDRISE -> 3; case CIVIC -> 2;
            case COMMERCIAL -> 1; case TOWNHOUSE -> 2; case HOUSE -> 0; case MIXED -> 3;
        };
    }

    public boolean small() { return this == HOUSE || this == TOWNHOUSE; }
}
