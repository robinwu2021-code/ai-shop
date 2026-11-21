package ai.neargo.commerce.api.common;

import java.util.List;

public enum PlanTier {
    FREE, BASE, PRO, GROWTH, CHAIN;

    public static final String PAYMENT_TYPE = "PAYMENT";

    public static List<PlanTier> catalogTiers(String typeCode) {
        return PAYMENT_TYPE.equals(typeCode)
                ? List.of(FREE, BASE)
                : List.of(FREE, BASE, PRO, GROWTH, CHAIN);
    }

    public static List<PlanTier> legacyCatalogTiers() {
        return List.of(FREE, PRO, CHAIN);
    }
}
