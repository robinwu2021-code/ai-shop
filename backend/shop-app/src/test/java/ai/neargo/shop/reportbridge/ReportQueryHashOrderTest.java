package ai.neargo.shop.reportbridge;

import java.time.LocalDate;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;

import ai.neargo.shop.report.support.ReportEvidenceCodec;
import ai.neargo.shop.spi.report.ReportContracts.Scope;
import ai.neargo.shop.spi.report.ReportQueryPort.Mode;
import ai.neargo.shop.spi.report.ReportQueryPort.Query;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 导出任务的请求哈希与快照哈希必须跨 JVM 稳定。
 *
 * <p>{@code PricingSnapshotHash} 只给对象键排序、数组保持原序，而 {@code Set.copyOf}
 * 的遍历顺序每个 JVM 随机。于是实例 A 建的导出，在实例 B 或重启后读回时哈希对不上，
 * 报 {@code REPORT_EXPORT_SNAPSHOT_CORRUPT}。这里锁住「metricCodes 按字典序序列化」。
 */
@DisplayName("报表查询哈希不依赖 Set 的遍历顺序")
class ReportQueryHashOrderTest {

    private static Query query(List<String> codes) {
        return new Query(List.of(new Scope("T1", "E1", "S1")), LocalDate.of(2026, 10, 1),
                LocalDate.of(2026, 10, 8), 1, "SALES_DAY", new LinkedHashSet<>(codes),
                Map.of("currency", "CNY"), Mode.DAILY, 100, "A1");
    }

    @Test
    void metricCodesSerializeInSortedOrder() {
        List<String> codes = new ArrayList<>();
        for (char c = 'z'; c >= 'a'; c--) codes.add("metric_" + c);
        String json = ReportEvidenceCodec.json(query(codes));
        List<String> sorted = codes.stream().sorted().toList();
        assertThat(json).contains("\"metricCodes\":[\"" + String.join("\",\"", sorted) + "\"]");
    }

    @Test
    void hashIsIndependentOfInsertionOrder() {
        List<String> codes = new ArrayList<>();
        for (char c = 'a'; c <= 'z'; c++) codes.add("metric_" + c);
        List<String> reversed = new ArrayList<>(codes);
        Collections.reverse(reversed);
        assertThat(ReportEvidenceCodec.hash(query(codes))).isEqualTo(ReportEvidenceCodec.hash(query(reversed)));
    }

    @Test
    void storedJsonRoundTripKeepsTheHash() {
        List<String> codes = new ArrayList<>();
        for (char c = 'a'; c <= 'z'; c++) codes.add("metric_" + c);
        Query original = query(codes);
        Query reread = ReportEvidenceCodec.read(ReportEvidenceCodec.json(original), Query.class);
        assertThat(ReportEvidenceCodec.hash(reread)).isEqualTo(ReportEvidenceCodec.hash(original));
    }
}
