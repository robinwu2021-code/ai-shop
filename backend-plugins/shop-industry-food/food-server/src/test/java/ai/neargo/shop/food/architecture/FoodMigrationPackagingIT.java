package ai.neargo.shop.food.architecture;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.ByteArrayInputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.util.HexFormat;
import java.util.zip.ZipFile;
import java.util.zip.ZipInputStream;
import org.junit.jupiter.api.Test;

/** 在 verify 阶段读取实际构建产物；不启动 Spring 或连接数据库。 */
class FoodMigrationPackagingIT {
    private static final String FILE = "V1__food_business_tables.sql";
    private static final String RESOURCE = "db/industry/food/" + FILE;
    private static final String SHA256 = "215d76bf3a1c23aefc31bbbaa1eea25fd62c7bad3d7eb9123f5a84f0cc347aa9";

    @Test
    void movedMigrationPreservesBytes() throws Exception {
        Path source = root().resolve("food-core/src/main/resources/" + RESOURCE);
        assertTrue(Files.isRegularFile(source), "frozen V1 must be owned by food-core");
        assertEquals(SHA256, digest(Files.readAllBytes(source)));
        assertFalse(Files.exists(root().resolve("../../backend/shop-app/src/main/resources/" + RESOURCE)),
                "host must not retain a second V1 source");
    }

    @Test
    void packagedFoodContainsOneV1() throws Exception {
        for (String module : new String[] {"food-api", "food-core", "food-channel"}) {
            try (var jar = new ZipFile(artifact(module).toFile())) {
                assertEquals(module.equals("food-core"), jar.getEntry(
                        "db/industry/food/V3__option_spec_value_identity_width.sql") != null);
                var entries = jar.stream().filter(entry -> isV1(entry.getName())).toList();
                assertEquals(module.equals("food-core") ? 1 : 0, entries.size(), module);
                if (!entries.isEmpty()) {
                    assertEquals(RESOURCE, entries.getFirst().getName());
                    try (var input = jar.getInputStream(entries.getFirst())) {
                        assertEquals(SHA256, digest(input.readAllBytes()));
                    }
                }
            }
        }
    }

    @Test
    void standaloneContainsOnlyNestedCoreMigration() throws Exception {
        int count = 0;
        int v3 = 0;
        try (var server = new ZipFile(artifact("food-server").toFile())) {
            for (var entry : server.stream().toList()) {
                assertFalse(isV1(entry.getName()), "server must not copy V1 into its own classes");
                if (!entry.getName().startsWith("BOOT-INF/lib/") || !entry.getName().endsWith(".jar")) {
                    continue;
                }
                try (var input = server.getInputStream(entry);
                        var nested = new ZipInputStream(new ByteArrayInputStream(input.readAllBytes()))) {
                    for (var resource = nested.getNextEntry(); resource != null; resource = nested.getNextEntry()) {
                        if (resource.getName().equals("db/industry/food/V3__option_spec_value_identity_width.sql")) {
                            v3++;
                            assertTrue(entry.getName().startsWith("BOOT-INF/lib/food-core-"));
                        }
                        if (isV1(resource.getName())) {
                            count++;
                            assertTrue(entry.getName().startsWith("BOOT-INF/lib/food-core-"));
                            assertEquals(RESOURCE, resource.getName());
                            assertEquals(SHA256, digest(nested.readAllBytes()));
                        }
                    }
                }
            }
        }
        assertEquals(1, v3, "one V3 through food-core");
        assertEquals(1, count, "standalone must contain one frozen V1 through food-core only");
    }

    private static boolean isV1(String name) {
        return name.equals(FILE) || name.endsWith("/" + FILE);
    }

    private static Path root() {
        return Path.of(System.getProperty("food.reactor.root")).toAbsolutePath().normalize();
    }

    private static Path artifact(String module) {
        String version = System.getProperty("food.project.version");
        return root().resolve(module + "/target/" + module + "-" + version + ".jar");
    }

    private static String digest(byte[] bytes) throws Exception {
        return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(bytes));
    }
}
