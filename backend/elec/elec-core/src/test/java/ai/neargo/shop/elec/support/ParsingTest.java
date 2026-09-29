package ai.neargo.shop.elec.support;

import ai.neargo.shop.common.BizException;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.nio.charset.StandardCharsets;
import java.time.Year;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/** 表格解析与规范化：供应商的表五花八门，这些规则认错一条，整张表的匹配就跟着错。 */
class ParsingTest {

    @Test
    @DisplayName("料号规范化：大小写、空格、横杠不影响；/ . # + 保留；% _ 这类 LIKE 通配符不会留下")
    void mpnNorm() {
        assertThat(Mpn.norm(" stm32f103-c8 t6 ")).isEqualTo("STM32F103C8T6");
        assertThat(Mpn.norm("1.5KE6.8CA")).isEqualTo("1.5KE6.8CA");
        assertThat(Mpn.norm("74HC595D/T3")).isEqualTo("74HC595D/T3");
        assertThat(Mpn.norm("AB%_C1")).isEqualTo("ABC1");
        assertThat(Mpn.looksLikeMpn(Mpn.norm("电阻"))).isFalse();
        assertThat(Mpn.looksLikeMpn(Mpn.norm("0805"))).isTrue();
    }

    @Test
    @DisplayName("分段键：只在字母/数字交界处切，所以中段从段首开始都能按前缀搜到")
    void segmentKeys() {
        assertThat(Mpn.segmentKeys("STM32F103C8T6").stream().map(java.util.Map.Entry::getKey).toList())
                .containsExactly("STM32F103C8T6", "32F103C8T6", "F103C8T6", "103C8T6", "C8T6", "8T6", "T6");
        assertThat(Mpn.segmentKeys("74HC595D/T3").stream().map(java.util.Map.Entry::getKey).toList())
                .contains("HC595D/T3", "T3");
    }

    @Test
    @DisplayName("拆词：厂牌、料号、数量各归各位；Excel 粘过来的制表符也认")
    void query() {
        var aliases = java.util.Map.of("TI", "TI", "ST", "ST", "德州仪器", "TI");
        Query.Parsed a = Query.parse("TI tps54331dr 2000", aliases);
        assertThat(a.mpnNorm()).isEqualTo("TPS54331DR");
        assertThat(a.mfrCode()).isEqualTo("TI");
        assertThat(a.qty()).isEqualTo(2000L);
        Query.Parsed b = Query.parse("STM32F103C8T6\tST\t5k", aliases);
        assertThat(b.mpnNorm()).isEqualTo("STM32F103C8T6");
        assertThat(b.mfrCode()).isEqualTo("ST");
        assertThat(b.qty()).isEqualTo(5000L);
        assertThat(Query.parse("德州仪器", aliases).mpnNorm()).as("只有厂牌没有料号").isEmpty();
    }

    @Test
    @DisplayName("厂牌规范化：去公司后缀与标点，中文保留")
    void mfrNorm() {
        assertThat(Mpn.mfrNorm("Texas Instruments Inc.")).isEqualTo("TEXASINSTRUMENTS");
        assertThat(Mpn.mfrNorm("STMicroelectronics N.V.")).isEqualTo("STMICROELECTRONICS");
        assertThat(Mpn.mfrNorm("德州仪器")).isEqualTo("德州仪器");
        assertThat(Mpn.mfrNorm("深圳某某电子有限公司")).isEqualTo("深圳某某电子");
    }

    @Test
    @DisplayName("数量：千分位、K、万、pcs 都认；认不出或 ≤0 为空（空 ≠ 0）")
    void qty() {
        assertThat(Cells.qty("5,000")).isEqualTo(5000L);
        assertThat(Cells.qty("5K")).isEqualTo(5000L);
        assertThat(Cells.qty("1.2万")).isEqualTo(12000L);
        assertThat(Cells.qty("3000pcs")).isEqualTo(3000L);
        assertThat(Cells.qty("abc")).isNull();
        assertThat(Cells.qty("0")).isNull();
        assertThat(Cells.qty("")).isNull();
    }

    @Test
    @DisplayName("单价 → 百万分之一元：小数点后四位的电阻价也不丢")
    void price() {
        assertThat(Cells.priceE6("¥6.20")).isEqualTo(6_200_000L);
        assertThat(Cells.priceE6("0.0015")).isEqualTo(1_500L);
        assertThat(Cells.priceE6("面议")).isNull();
        assertThat(Cells.priceE6("0")).isNull();
    }

    @Test
    @DisplayName("批号 → 年份：四位一律按年周（2019 = 2020 年第 19 周），23+、24/25、2023-05 都认")
    void dcYear() {
        assertThat(Cells.dcYear("2338")).isEqualTo(2023);
        assertThat(Cells.dcYear("2019")).as("行业惯例：四位是 YYWW").isEqualTo(2020);
        assertThat(Cells.dcYear("23+")).isEqualTo(2023);
        assertThat(Cells.dcYear("24/25")).isEqualTo(2025);
        assertThat(Cells.dcYear("2023-05")).isEqualTo(2023);
        assertThat(Cells.dcYear("2399")).as("第 99 周不存在").isNull();
        int nextYear = Year.now().getValue() + 1 - 2000;
        assertThat(Cells.dcYear(nextYear + "01")).as("未来的年份丢掉").isNull();
    }

    @Test
    @DisplayName("档位：只给档，不给精确数")
    void bands() {
        assertThat(Bands.qty(99)).isEqualTo("B1");
        assertThat(Bands.qty(12345)).isEqualTo("B10K");
        assertThat(Bands.source(1)).isEqualTo("ONE");
        assertThat(Bands.source(3)).isEqualTo("FEW");
        assertThat(Bands.source(9)).isEqualTo("MANY");
    }

    @Test
    @DisplayName("表头：不在第一行也找得到；P/N、D/C、品牌都认；价格表头写了「未税」要提示")
    void columns() {
        Columns.Guess g = Columns.guess(List.of(
                List.of("某某电子库存表"),
                List.of("序号", "P/N", "品牌", "D/C", "库存数量", "未税单价")));
        assertThat(g.ok()).isTrue();
        assertThat(g.headerRow()).isEqualTo(1);
        assertThat(g.map().get(Columns.Field.MPN)).isEqualTo(1);
        assertThat(g.map().get(Columns.Field.QTY)).isEqualTo(4);
        assertThat(g.map().get(Columns.Field.DC)).isEqualTo(3);
        assertThat(g.taxHint()).isFalse();
        assertThat(Columns.guess(List.of(List.of("a", "b"))).ok()).isFalse();
    }

    @Test
    @DisplayName("csv：引号里的逗号与换行、\"\" 转义；制表符分隔也认")
    void csv() {
        List<List<String>> rows = SheetReader.read(
                "型号,备注\n\"AB,12\",\"第一行\n第二行\"\n\"X\"\"Y1\",z\n".getBytes(StandardCharsets.UTF_8), 100);
        assertThat(rows.get(1)).containsExactly("AB,12", "第一行\n第二行");
        assertThat(rows.get(2).get(0)).isEqualTo("X\"Y1");
        assertThat(SheetReader.read("型号\t数量\nA1\t5\n".getBytes(StandardCharsets.UTF_8), 100).get(1))
                .containsExactly("A1", "5");
    }

    @Test
    @DisplayName("行数超上限直接拒，不静默截断 —— 截断会让全量替换把后半截全下架")
    void tooManyRows() {
        String csv = "型号,数量\n" + "A1,1\n".repeat(11);
        assertThatThrownBy(() -> SheetReader.read(csv.getBytes(StandardCharsets.UTF_8), 10))
                .isInstanceOf(BizException.class);
    }

    @Test
    @DisplayName("xlsx 列号：AB = 27（从 0 起）")
    void colIndex() {
        assertThat(SheetReader.colIndex("A1")).isZero();
        assertThat(SheetReader.colIndex("AB12")).isEqualTo(27);
    }
}
