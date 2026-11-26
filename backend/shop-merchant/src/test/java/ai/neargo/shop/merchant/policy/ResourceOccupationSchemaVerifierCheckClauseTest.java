package ai.neargo.shop.merchant.policy;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

class ResourceOccupationSchemaVerifierCheckClauseTest {

    @Test
    @DisplayName("MySQL 9.7 的 CHECK_CLAUSE 带一层反斜杠转义，比对前还原成普通 SQL")
    void mysqlRenderedCheckClauseIsUnescapedOnce() {
        assertThat(ResourceOccupationSchemaVerifier.renderedCheck(
                "(`status` in (_utf8mb4\\'HELD\\',_utf8mb4\\'CONFIRMED\\'))"))
                .isEqualTo("(`status` in (_utf8mb4'HELD',_utf8mb4'CONFIRMED'))");
        assertThat(ResourceOccupationSchemaVerifier.renderedCheck(
                "regexp_like(`h`,_utf8mb4\\'^[0-9a-f]{64}$\\')"))
                .isEqualTo("regexp_like(`h`,_utf8mb4'^[0-9a-f]{64}$')");
    }

    @Test
    @DisplayName("没有转义的子句（H2、MariaDB）原样返回")
    void plainClauseIsUnchanged() {
        assertThat(ResourceOccupationSchemaVerifier.renderedCheck("(`qty` >= 0)")).isEqualTo("(`qty` >= 0)");
    }

    @Test
    @DisplayName("MySQL 改写后的约束与描述文件里的原文规范化后相同：MOD、TIMESTAMPADD、嵌套 OR")
    void mysqlRewrittenFormsNormalizeLikeTheDescriptor() {
        assertSame("MOD(MINUTE(start_at),5)=0 AND SECOND(start_at)=0 AND end_at=TIMESTAMPADD(MINUTE,5,start_at)",
                "(((minute(`start_at`) % 5) = 0) and (second(`start_at`) = 0)"
                        + " and (`end_at` = (`start_at` + interval 5 minute)))");
        assertSame("((end_day_offset*1440+end_minute)-start_minute) BETWEEN 1 AND 1440",
                "((((`end_day_offset` * 1440) + `end_minute`) - `start_minute`) between 1 and 1440)");
        assertSame("(a IS NULL OR a NOT IN ('X','Y')) OR (b='Z' AND c IS NOT NULL)",
                "((`a` is null) or (`a` not in (_utf8mb4\\'X\\',_utf8mb4\\'Y\\')) or ((`b` = _utf8mb4\\'Z\\') and (`c` is not null)))");
    }

    @Test
    @DisplayName("真正不同的约束仍然判为不同")
    void differentConstraintsStayDifferent() {
        assertThat(ResourceOccupationSchemaVerifier.expression("a IN ('X','Y')"))
                .isNotEqualTo(ResourceOccupationSchemaVerifier.expression(
                        ResourceOccupationSchemaVerifier.renderedCheck("(`a` in (_utf8mb4\\'X\\'))")));
        assertThat(ResourceOccupationSchemaVerifier.expression("MOD(a,5)=0"))
                .isNotEqualTo(ResourceOccupationSchemaVerifier.expression("((`a` % 6) = 0)"));
    }

    private static void assertSame(String descriptor, String mysqlRendered) {
        assertThat(ResourceOccupationSchemaVerifier.expression(ResourceOccupationSchemaVerifier.renderedCheck(mysqlRendered)))
                .isEqualTo(ResourceOccupationSchemaVerifier.expression(descriptor));
    }
}
