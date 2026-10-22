package ai.neargo.shop.food.config;

import java.util.List;

/** 餐饮专用 schema 凭证，不是 Flyway Bean，也不表示远程支付库存合同已经就绪。 */
public record FoodSchemaReady(boolean validated, String version, List<String> missingCapabilities) {
    public FoodSchemaReady {
        missingCapabilities = List.copyOf(missingCapabilities);
    }

    public boolean ready() {
        return validated && missingCapabilities.isEmpty();
    }

    public static FoodSchemaReady off() {
        return new FoodSchemaReady(false, null, List.of("food.migration.off"));
    }
}
