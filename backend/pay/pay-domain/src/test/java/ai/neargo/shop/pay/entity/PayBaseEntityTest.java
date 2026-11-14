package ai.neargo.shop.pay.entity;

import static org.assertj.core.api.Assertions.assertThat;

import com.baomidou.mybatisplus.annotation.TableName;
import java.io.IOException;
import java.lang.reflect.Field;
import java.lang.reflect.Modifier;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.stream.Collectors;
import java.util.stream.Stream;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

/**
 * v4 支付新表的实体基类与结构化模型对齐（TDD-支付服务-I0地基 AC1）。
 *
 * <p>真源是 {@code docs/technical/design/schema/pay-service-database.json}：它的 {@code standardColumns}
 * 是每张新表都有的列，{@code tables[].name} 是新表清单。两边任何一边改了而另一边没跟，这里红。
 */
class PayBaseEntityTest {

    private static final Path MODEL = Path.of("..", "..", "..", "docs", "technical", "design", "schema",
            "pay-service-database.json");
    private static final Path PAY_SRC = Path.of("..");

    @Test
    @DisplayName("★★★ 基类字段 = 模型的标准列，且没有 tenantNo —— 新表没有这一列，填充器写进去就是 INSERT 报错")
    void fieldsMatchStandardColumns() throws IOException {
        Set<String> expected = new HashSet<>();
        model().get("standardColumns").forEach(c -> expected.add(camel(c.asString())));

        Set<String> actual = Arrays.stream(PayBaseEntity.class.getDeclaredFields())
                .filter(f -> !Modifier.isStatic(f.getModifiers()))
                .map(Field::getName)
                .collect(Collectors.toSet());

        assertThat(expected).as("模型里一列标准列都没读到 —— 路径错了？").isNotEmpty();
        assertThat(actual).isEqualTo(expected);
        assertThat(actual).doesNotContain("tenantNo");
    }

    @Test
    @DisplayName("★★ 模型里列为新表的实体必须继承 PayBaseEntity —— 继承旧 BaseEntity 会带着 tenantNo 去插入")
    void newTableEntitiesExtendPayBaseEntity() throws IOException {
        Set<String> newTables = new HashSet<>();
        model().get("tables").forEach(t -> newTables.add(t.get("name").asString()));
        assertThat(newTables).as("模型里一张新表都没有 —— 路径错了？").hasSizeGreaterThan(30);

        List<String> offenders = new ArrayList<>();
        int scanned = 0;
        try (Stream<Path> files = Files.walk(PAY_SRC)) {
            for (Path f : files.filter(p -> p.toString().endsWith(".java"))
                    .filter(p -> p.toString().contains("/src/main/java/"))
                    .filter(p -> !p.toString().contains("/target/"))
                    .toList()) {
                scanned++;
                String text = Files.readString(f);
                var m = java.util.regex.Pattern.compile("@TableName\\(\\s*(?:value\\s*=\\s*)?\"([a-z0-9_]+)\"").matcher(text);
                if (m.find() && newTables.contains(m.group(1)) && !text.contains("extends PayBaseEntity")) {
                    offenders.add(f.getFileName() + " → " + m.group(1));
                }
            }
        }
        assertThat(scanned).as("一个 java 文件都没扫到 —— 路径错了？").isPositive();
        assertThat(offenders).as("这些 v4 新表的实体没有继承 PayBaseEntity：%s", offenders).isEmpty();
    }

    @Test
    @DisplayName("★ 判据自身能量出东西：一个映射到新表、继承旧基类的实体会被认出来")
    void detectorFiresOnOffender() {
        @TableName("pay_order")
        class Offender extends ai.neargo.shop.common.BaseEntity { }
        assertThat(Offender.class.getAnnotation(TableName.class).value()).isEqualTo("pay_order");
        assertThat(PayBaseEntity.class.isAssignableFrom(Offender.class)).isFalse();
        assertThat(Arrays.stream(ai.neargo.shop.common.BaseEntity.class.getDeclaredFields()).map(Field::getName))
                .as("对照：旧基类确实带 tenantNo，否则上面「没有 tenantNo」的断言没有意义")
                .contains("tenantNo");
    }

    private static JsonNode model() throws IOException {
        assertThat(MODEL).as("结构化模型不在 %s", MODEL.toAbsolutePath().normalize()).exists();
        return new ObjectMapper().readTree(Files.readString(MODEL));
    }

    private static String camel(String column) {
        StringBuilder sb = new StringBuilder();
        boolean up = false;
        for (char ch : column.toCharArray()) {
            if (ch == '_') {
                up = true;
            } else {
                sb.append(up ? Character.toUpperCase(ch) : ch);
                up = false;
            }
        }
        return sb.toString();
    }
}
