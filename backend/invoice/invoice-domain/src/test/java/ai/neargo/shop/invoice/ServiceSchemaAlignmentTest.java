package ai.neargo.shop.invoice;

import static org.assertj.core.api.Assertions.assertThat;

import ai.neargo.shop.invoice.entity.IvcAllowance;
import ai.neargo.shop.invoice.entity.IvcBuyerPreference;
import ai.neargo.shop.invoice.entity.IvcInvoice;
import ai.neargo.shop.invoice.entity.IvcInvoiceApplication;
import ai.neargo.shop.invoice.entity.IvcInvoiceApplicationRevision;
import ai.neargo.shop.invoice.entity.IvcInvoiceLine;
import ai.neargo.shop.invoice.entity.IvcNumberRange;
import ai.neargo.shop.invoice.entity.IvcRegimeProfile;
import ai.neargo.shop.invoice.entity.IvcStoreItem;
import ai.neargo.shop.invoice.entity.IvcStoreSetting;
import ai.neargo.shop.invoice.entity.IvcTransmission;
import ai.neargo.shop.invoice.entity.IvcTransmissionAttempt;
import ai.neargo.shop.invoice.entity.IvcUserCarrier;
import com.baomidou.mybatisplus.annotation.TableName;
import java.io.IOException;
import java.lang.reflect.Modifier;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.regex.Pattern;
import org.junit.jupiter.api.Test;

/**
 * 发票切到平台服务库之后，实体与新库的表对得上（PRD §7 S4）。
 *
 * <p>新库的结构不归发票管，由设计方案的建表语句给出（测试资源 {@code db/n_service/V1}，补充在 V2）。
 * 实体多写一个字段，MyBatis-Plus 会把它拼进 SQL，而错误要到跑到那一行才出现；这里在构建期对一遍。
 */
class ServiceSchemaAlignmentTest {

    private static final List<Class<?>> ENTITIES = List.of(IvcRegimeProfile.class, IvcNumberRange.class,
            IvcStoreItem.class, IvcStoreSetting.class, IvcBuyerPreference.class, IvcUserCarrier.class,
            IvcInvoiceApplication.class, IvcInvoiceApplicationRevision.class, IvcInvoice.class, IvcInvoiceLine.class,
            IvcAllowance.class, IvcTransmission.class, IvcTransmissionAttempt.class);

    private static final Pattern TABLE = Pattern.compile("CREATE TABLE `(\\w+)` \\((.*?)\\n\\) COMMENT", Pattern.DOTALL);
    private static final Pattern COLUMN = Pattern.compile("(?m)^\\s*`(\\w+)` ");
    private static final Pattern ADDED = Pattern.compile("ALTER TABLE `(\\w+)` ADD COLUMN `(\\w+)` ");

    @Test
    void every_entity_field_is_a_column_and_every_common_field_is_mapped() throws IOException {
        Map<String, Set<String>> schema = schema();
        List<String> missing = new ArrayList<>();
        for (Class<?> entity : ENTITIES) {
            String table = entity.getAnnotation(TableName.class).value();
            Set<String> columns = schema.get(table);
            assertThat(columns).as("新库里没有 %s", table).isNotNull();
            Set<String> fields = new LinkedHashSet<>();
            Arrays.stream(entity.getDeclaredFields()).filter(f -> !Modifier.isStatic(f.getModifiers()))
                    .forEach(f -> fields.add(snake(f.getName())));
            fields.stream().filter(f -> !columns.contains(f)).forEach(f -> missing.add(table + "." + f));
            for (String common : List.of("id", "created_by", "updated_by")) {
                assertThat(columns).as("%s 的公共字段", table).contains(common, "created_at", "updated_at");
                assertThat(fields).as("%s 实体的公共字段", entity.getSimpleName()).contains(common);
            }
        }
        assertThat(missing).as("实体上有、新库的表里没有的字段").isEmpty();
    }

    @Test
    void status_values_fit_ten_characters() {
        // 落库的状态取值全集（规范：状态列 varchar(10)）
        assertThat(List.of("ISSUED", "VOIDED", "REVOKING", "REVOKED", "ACTIVE", "SUSPENDED", "EXHAUSTED",
                "PENDING", "SENDING", "SENT", "FAILED", "HELD", "REVIEW", "SUBMITTED", "REJECTED", "CANCELLED"))
                .allSatisfy(status -> assertThat(status.length()).as(status).isLessThanOrEqualTo(10));
    }

    private static Map<String, Set<String>> schema() throws IOException {
        Map<String, Set<String>> tables = new LinkedHashMap<>();
        for (String file : List.of("V1__ivc_design.sql", "V2__ivc_supplement.sql")) {
            String sql;
            try (var in = ServiceSchemaAlignmentTest.class.getResourceAsStream("/db/n_service/" + file)) {
                sql = new String(in.readAllBytes(), StandardCharsets.UTF_8);
            }
            var table = TABLE.matcher(sql);
            while (table.find()) {
                Set<String> columns = new LinkedHashSet<>();
                var column = COLUMN.matcher(table.group(2));
                while (column.find()) columns.add(column.group(1));
                tables.put(table.group(1), columns);
            }
            var added = ADDED.matcher(sql);
            while (added.find()) tables.get(added.group(1)).add(added.group(2));
        }
        return tables;
    }

    private static String snake(String camel) {
        return camel.replaceAll("([a-z0-9])([A-Z])", "$1_$2").toLowerCase();
    }
}
