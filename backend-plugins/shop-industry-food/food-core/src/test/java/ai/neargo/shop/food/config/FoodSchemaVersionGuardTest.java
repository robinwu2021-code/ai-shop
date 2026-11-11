package ai.neargo.shop.food.config;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;
import org.springframework.core.io.support.PathMatchingResourcePatternResolver;

import java.io.IOException;
import java.util.Arrays;
import java.util.Objects;
import java.util.regex.Pattern;

/** 新增餐饮迁移却忘了改 EXPECTED_VERSION，会让启动校验在 MIGRATE/VALIDATE 模式下直接失败。 */
class FoodSchemaVersionGuardTest {
    private static final Pattern VERSIONED = Pattern.compile("^V(\\d+)__.+\\.sql$");

    @Test
    void expectedVersionIsTheHighestFoodMigration() throws IOException {
        var resources =
                new PathMatchingResourcePatternResolver()
                        .getResources("classpath*:db/industry/food/V*__*.sql");
        int highest =
                Arrays.stream(resources)
                        .map(r -> r.getFilename())
                        .filter(Objects::nonNull)
                        .map(VERSIONED::matcher)
                        .filter(m -> m.matches())
                        .mapToInt(m -> Integer.parseInt(m.group(1)))
                        .max()
                        .orElseThrow();
        assertThat(FoodSchemaValidator.EXPECTED_VERSION).isEqualTo(Integer.toString(highest));
    }
}
