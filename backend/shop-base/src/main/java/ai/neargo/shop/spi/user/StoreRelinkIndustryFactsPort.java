package ai.neargo.shop.spi.user;

/** A deployed industry owner proves its own original business history; absence of a bean is not proof. */
public interface StoreRelinkIndustryFactsPort {
    java.util.Set<String> businessTypes();
    record Facts(boolean complete, boolean hasHistory) {}
    Facts inspect(String tenantNo, String entityNo, String storeNo);
}
