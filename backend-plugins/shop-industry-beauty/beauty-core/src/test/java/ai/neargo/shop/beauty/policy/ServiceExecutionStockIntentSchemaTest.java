package ai.neargo.shop.beauty.policy;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;

import java.nio.charset.StandardCharsets;

/** Frozen descriptor explicitly records permanent originals and sticky route protection. */
class ServiceExecutionStockIntentSchemaTest {
    @Test
    void descriptorAndMigrationProtectEveryImmutableColumn() throws Exception {
        var loader = getClass().getClassLoader();
        String descriptor;
        String migration;
        try (var stream =
                loader.getResourceAsStream(
                        "META-INF/shop/beauty-service-execution-stock-intent-schema.json")) {
            descriptor = new String(stream.readAllBytes(), StandardCharsets.UTF_8);
        }
        try (var stream =
                loader.getResourceAsStream(
                        "db/industry/beauty/V11__beauty_service_execution_stock_intent.sql")) {
            migration = new String(stream.readAllBytes(), StandardCharsets.UTF_8);
        }
        var tree = tools.jackson.databind.json.JsonMapper.builder().build().readTree(descriptor);
        assertEquals(11, tree.path("platformVersion").asInt());
        for (var column : tree.path("tables").get(0).path("immutable")) {
            assertTrue(
                    migration.contains(
                            "BINARY NEW."
                                    + column.asString()
                                    + " <=> BINARY OLD."
                                    + column.asString()));
        }
        assertTrue(migration.contains("OLD.route_no IS NOT NULL"));
        assertTrue(migration.contains("history cannot be deleted"));
    }
}
