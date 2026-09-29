package ai.neargo.shop.elec.support;

/**
 * 买家看到的档位。<b>有损是故意的</b>：精确库存加上批号与地区，同行一眼就能认出是谁家的货。
 * 投影表里只存档位，不存精确值 —— 将来谁要「显示精确库存」，得先来改这里，而那时会被看见。
 */
public final class Bands {

    public static final String ONE = "ONE";
    public static final String FEW = "FEW";
    public static final String MANY = "MANY";

    private Bands() {
    }

    /** B1 / B100 / B1K / B10K / B100K / B1M：端上显示成「100+」「1k+」… */
    public static String qty(long total) {
        if (total >= 1_000_000) {
            return "B1M";
        }
        if (total >= 100_000) {
            return "B100K";
        }
        if (total >= 10_000) {
            return "B10K";
        }
        if (total >= 1_000) {
            return "B1K";
        }
        if (total >= 100) {
            return "B100";
        }
        return "B1";
    }

    /** 1 家 / 2–4 家 / 5 家及以上。端上只说「1 家有货」「多家有货」 */
    public static String source(int suppliers) {
        if (suppliers >= 5) {
            return MANY;
        }
        return suppliers >= 2 ? FEW : ONE;
    }
}
