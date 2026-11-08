package ai.neargo.shop.message;

import static org.assertj.core.api.Assertions.assertThat;

import ai.neargo.shop.message.dto.MessageVOs.DiagnosticVO;
import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/** 回报诊断清洗（TDD-商户App问题回报与版本 F2、F4）：一律不拒单。 */
class FeedbackDiagnosticsTest {

    private static DiagnosticVO d(String at, String method, String path, String outcome) {
        return new DiagnosticVO(at, method, path, outcome);
    }

    @Test
    @DisplayName("★★★ 超过 300 条留最后 300 条（App 是旧的在前）")
    void keeps_last_300_entries() {
        List<DiagnosticVO> raw = new ArrayList<>();
        for (int i = 0; i < 305; i++) {
            raw.add(d(null, "GET", "/biz/n" + i, "ok"));
        }
        List<DiagnosticVO> out = FeedbackDiagnostics.sanitize(raw);
        assertThat(out).hasSize(300);
        assertThat(out.get(0).path()).isEqualTo("/biz/n5");
        assertThat(out.get(299).path()).isEqualTo("/biz/n304");
        assertThat(FeedbackDiagnostics.sanitize(null)).isEmpty();
    }

    @Test
    @DisplayName("★★ 单条字段超长截断：method ≤ 8、path ≤ 256、outcome ≤ 96")
    void truncates_overlong_fields() {
        DiagnosticVO out = FeedbackDiagnostics.sanitize(List.of(
                d(null, "PROPFINDXX", "/" + "a".repeat(300), "o".repeat(200)))).get(0);
        assertThat(out.method()).hasSize(8);
        assertThat(out.path()).hasSize(256);
        assertThat(out.outcome()).hasSize(96);
        assertThat(FeedbackDiagnostics.truncate("ab😀", 3)).as("不劈开代理对").isEqualTo("ab");
    }

    @Test
    @DisplayName("★★★ 带时区的 at 归一成 UTC；不带时区的原样保留（设备本地时间，不猜）")
    void offset_at_is_normalized_to_utc_and_local_at_is_kept_verbatim() {
        assertThat(FeedbackDiagnostics.normalizeAt("2026-11-08T17:20:00+08:00")).isEqualTo("2026-11-08T09:20:00Z");
        assertThat(FeedbackDiagnostics.normalizeAt("2026-11-08T09:20:00.123456Z")).isEqualTo("2026-11-08T09:20:00.123456Z");
        assertThat(FeedbackDiagnostics.normalizeAt("2026-11-08T17:20:00.123456"))
                .as("App 现在送的 DateTime.now().toIso8601String()").isEqualTo("2026-11-08T17:20:00.123456");
    }

    @Test
    @DisplayName("★★ 解析不了的 at 置 null，这条诊断保留")
    void unparseable_at_becomes_null() {
        List<DiagnosticVO> out = FeedbackDiagnostics.sanitize(List.of(d("昨天下午", "GET", "/biz/a", "ok")));
        assertThat(out).hasSize(1);
        assertThat(out.get(0).at()).isNull();
        assertThat(out.get(0).path()).isEqualTo("/biz/a");
    }

    @Test
    @DisplayName("★★★ 8 位以上数字只留后 4 位（与 App redactPath 同规则）")
    void keeps_only_four_fields_and_redacts_long_numbers() {
        assertThat(FeedbackDiagnostics.redactPath("/biz/payment/orders/P2611051432001?phone=0912345678"))
                .isEqualTo("/biz/payment/orders/P…2001");
        assertThat(FeedbackDiagnostics.redactPath("/biz/store/1234567/x")).as("7 位不动").isEqualTo("/biz/store/1234567/x");
        assertThat(FeedbackDiagnostics.redactPath("/biz/a/12345678/b/987654321"))
                .isEqualTo("/biz/a/…5678/b/…4321");
    }

    @Test
    @DisplayName("★★ 去掉查询串与片段")
    void strips_query_string() {
        assertThat(FeedbackDiagnostics.redactPath("/biz/a?token=btk_x&phone=1")).isEqualTo("/biz/a");
        assertThat(FeedbackDiagnostics.redactPath("/biz/a#frag")).isEqualTo("/biz/a");
    }
}
