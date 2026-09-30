package ai.neargo.shop.elec.support;

import ai.neargo.shop.elec.support.Columns.Field;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/** 表头别名的种子（V3）：原先写死在 Columns.NAMES 里的 106 种写法，搬进库之后行为不变 */
class HeaderAliasSeedTest {

    @Test
    @DisplayName("种子 106 条、没有重复写法；每条的 alias_norm 就是原文按 HeaderNames 规范化的结果 —— 否则那条永远查不到")
    void seedIsConsistent() {
        Map<String, Field> m = SeedAliases.map();
        assertThat(m).hasSize(106);
        SeedAliases.raw().forEach((raw, norm) ->
                assertThat(HeaderNames.norm(raw)).as("原文「%s」", raw).isEqualTo(norm));
    }

    @Test
    @DisplayName("常见写法照旧认得：型号 / P/N / 品牌 / 制造商 / 库存数量 / D/C / 未税单价")
    void commonHeaders() {
        Map<String, Field> m = SeedAliases.map();
        assertThat(m.get(HeaderNames.norm("型号"))).isEqualTo(Field.MPN);
        assertThat(m.get(HeaderNames.norm("P/N"))).isEqualTo(Field.MPN);
        assertThat(m.get(HeaderNames.norm("Part No."))).isEqualTo(Field.MPN);
        assertThat(m.get(HeaderNames.norm("品牌"))).isEqualTo(Field.MFR);
        assertThat(m.get(HeaderNames.norm("制造商"))).isEqualTo(Field.MFR);
        assertThat(m.get(HeaderNames.norm("库存数量"))).isEqualTo(Field.QTY);
        assertThat(m.get(HeaderNames.norm("D/C"))).isEqualTo(Field.DC);
        assertThat(m.get(HeaderNames.norm("未税单价"))).isEqualTo(Field.PRICE);
    }

    @Test
    @DisplayName("PACKAGING 只归包装方式（原先同时列在封装下，同一列会被两个字段一起认走）")
    void packagingIsPacking() {
        assertThat(SeedAliases.map().get("PACKAGING")).isEqualTo(Field.PACKING);
        Columns.Guess g = Columns.guess(List.of(List.of("型号", "数量", "Packaging", "Package")), SeedAliases.map());
        assertThat(g.map().get(Field.PACKING)).isEqualTo(2);
        assertThat(g.map().get(Field.PACKAGE)).isEqualTo(3);
        assertThat(g.conflicts()).isEmpty();
    }

    @Test
    @DisplayName("两列抢同一个字段记成冲突（取第一列）—— 认列据此去问大模型")
    void conflictsRecorded() {
        Columns.Guess g = Columns.guess(List.of(List.of("型号", "数量", "库存")), SeedAliases.map());
        assertThat(g.map().get(Field.QTY)).isEqualTo(1);
        assertThat(g.conflicts()).containsExactly(Field.QTY);
    }

    @Test
    @DisplayName("没认全料号与数量时也给出候选表头行（认出字段最多的那行），而不是 -1")
    void partialHeaderRowStillReported() {
        Columns.Guess g = Columns.guess(List.of(List.of("库存表"), List.of("Item", "品牌", "Stk")), SeedAliases.map());
        assertThat(g.ok()).isFalse();
        assertThat(g.headerRow()).isEqualTo(1);
        assertThat(g.map()).containsEntry(Field.MFR, 1);
    }
}
