package ai.neargo.shop.elec.service.impl;

import ai.neargo.shop.elec.dto.SupplierDtos.Issue;
import ai.neargo.shop.elec.support.Columns.Field;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

class StockSheetParserTest {

    private static final Map<Field, Integer> MAP = Map.of(Field.MPN, 0, Field.MFR, 1, Field.QTY, 2, Field.DC, 3);
    private static final Map<String, String> MFR = Map.of("TI", "TI", "ST", "ST");

    @Test
    @DisplayName("★★★ AC6 定位到格、一行多处全记：料号空 + 数量读不出 → 两条，各带列、表头、原值")
    void issuesPinpointCellAndCollectAllPerRow() {
        List<List<String>> rows = List.of(
                List.of("型号", "品牌", "数量", "批号"),
                List.of("ABC123", "TI", "100", "2338"),
                List.of("", "TI", "约2千", ""));
        StockSheetParser.Result r = StockSheetParser.parse(rows, 0, MAP, List.of(), MFR);
        List<Issue> is = r.rows().get(1).issues();
        assertThat(is).hasSize(2);
        assertThat(is.get(0)).isEqualTo(new Issue(3, 0, "型号", "", "MPN_MISSING", "ERROR"));
        assertThat(is.get(1)).isEqualTo(new Issue(3, 2, "数量", "约2千", "QTY_INVALID", "ERROR"));
        assertThat(r.invalid()).isEqualTo(1);
        assertThat(r.rows().get(1).cells()).as("有问题的行留原样单元格（导出用）").isNotNull();
        assertThat(r.rows().get(0).cells()).as("没问题的行不留，省内存").isNull();
    }

    @Test
    @DisplayName("★★★ AC7 警告照常上架：厂牌没写 / 认不出、批号读不出年份；数量为 0 是错误（不上架），但说清是 0")
    void warnings() {
        List<List<String>> rows = List.of(
                List.of("型号", "品牌", "数量", "批号"),
                List.of("ABC001", "", "100", "2338"),
                List.of("ABC002", "TIX", "100", "2338"),
                List.of("ABC003", "TI", "0 pcs", "2338"),
                List.of("ABC004", "TI", "5", "看批次"),
                List.of("ABC005", "TI", "零", "2338"));
        StockSheetParser.Result r = StockSheetParser.parse(rows, 0, MAP, List.of(), MFR);
        assertThat(r.invalid()).isEqualTo(2);
        assertThat(r.warn()).isEqualTo(3);
        assertThat(r.issueCounts()).containsEntry("MFR_MISSING", 1).containsEntry("MFR_UNKNOWN", 1)
                .containsEntry("DC_UNPARSED", 1).containsEntry("QTY_ZERO", 1).containsEntry("QTY_INVALID", 1);
        assertThat(r.rows().get(2).issues().get(0).level()).isEqualTo("ERROR");
        assertThat(r.rows().get(1).issues().get(0)).isEqualTo(new Issue(3, 1, "品牌", "TIX", "MFR_UNKNOWN", "WARN"));
    }

    @Test
    @DisplayName("★★ 重复行报整行（col=-1），值是先出现的那一行的行号")
    void duplicatePointsToFirstRow() {
        List<List<String>> rows = List.of(
                List.of("型号", "品牌", "数量", "批号"),
                List.of("ABC001", "TI", "100", "2338"),
                List.of("abc-001", "TI", "200", "2338"));
        Issue d = StockSheetParser.parse(rows, 0, MAP, List.of(), MFR).rows().get(1).issues().get(0);
        assertThat(d).isEqualTo(new Issue(3, -1, null, "2", "DUPLICATE", "ERROR"));
    }

    @Test
    @DisplayName("★★ 厂牌列没认出时不逐行报「没写厂牌」（那会让每一行都带警告）")
    void noMfrColumnNoPerRowWarning() {
        List<List<String>> rows = List.of(List.of("型号", "数量"), List.of("ABC001", "100"));
        StockSheetParser.Result r = StockSheetParser.parse(rows, 0, Map.of(Field.MPN, 0, Field.QTY, 1), List.of(), MFR);
        assertThat(r.warn()).isZero();
    }
}
