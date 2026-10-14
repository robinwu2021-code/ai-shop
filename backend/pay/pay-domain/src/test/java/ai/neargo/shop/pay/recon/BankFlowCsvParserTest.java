package ai.neargo.shop.pay.recon;

import ai.neargo.shop.pay.entity.StlBankFlow;
import ai.neargo.shop.pay.service.recon.BankFlowCsvParser;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 网银 CSV 解析（TDD §10.3）。
 *
 * <p>这些用例钉的都是<b>方言</b>：列名、日期写法、借贷标志、千分位与负号。
 * 归一化只要漏一种，比对那边看到的就是脏数据 ——
 * 而脏数据在对账里的表现是「凭空多出一批差异」，没人会怀疑到解析器头上。
 */
class BankFlowCsvParserTest {

    @Test
    @DisplayName("常见列名与日期写法都认得，金额转成分")
    void parsesCommonDialect() {
        String csv = """
                交易日期,交易流水号,借贷标志,发生额,对方户名,对方账号,摘要
                2026/9/20,BF001,借,1234.50,深圳虹选,6222021234567890123,货款-E001
                20260921,BF002,贷,"1,000.00",某某,6222000000001111,退回
                """;
        var r = BankFlowCsvParser.parse(csv);

        assertThat(r.failures()).isEmpty();
        assertThat(r.rows()).hasSize(2);

        StlBankFlow a = r.rows().get(0);
        assertThat(a.getFlowNo()).isEqualTo("BF001");
        assertThat(a.getTradeDate()).isEqualTo("2026-09-20");
        assertThat(a.getDirection()).isEqualTo(StlBankFlow.OUT);
        assertThat(a.getAmountMinor()).isEqualTo(123450L);
        assertThat(a.getRemark()).isEqualTo("货款-E001");

        StlBankFlow b = r.rows().get(1);
        assertThat(b.getTradeDate()).isEqualTo("2026-09-21");
        assertThat(b.getDirection()).isEqualTo(StlBankFlow.IN);
        assertThat(b.getAmountMinor()).isEqualTo(100000L);
    }

    @Test
    @DisplayName("对方账号在解析时就掩码，全号一个字节都不往下走")
    void masksCounterpartyAccount() {
        String csv = """
                日期,流水号,方向,金额,对方账号
                2026-09-20,BF003,支出,10.00,6222021234567890123
                """;
        var r = BankFlowCsvParser.parse(csv);

        assertThat(r.rows()).hasSize(1);
        assertThat(r.rows().get(0).getCounterpartyAccountMasked()).isEqualTo("****0123");
        // 判据是「全号不在结果里的任何字段上」，不是「掩码字段长得对」
        assertThat(r.rows().get(0).toString()).doesNotContain("6222021234567890123");
    }

    @Test
    @DisplayName("没有借贷标志列时，负号才是方向的来源；两者都没有就整行失败")
    void directionFromSignOnlyWhenColumnAbsent() {
        var out = BankFlowCsvParser.parse("""
                日期,流水号,金额
                2026-09-20,BF004,-88.00
                """);
        assertThat(out.rows()).hasSize(1);
        assertThat(out.rows().get(0).getDirection()).isEqualTo(StlBankFlow.OUT);
        assertThat(out.rows().get(0).getAmountMinor()).isEqualTo(8800L);   // 恒为正

        var ambiguous = BankFlowCsvParser.parse("""
                日期,流水号,金额
                2026-09-20,BF005,88.00
                """);
        assertThat(ambiguous.rows()).isEmpty();
        assertThat(ambiguous.failures()).hasSize(1);
        assertThat(ambiguous.failures().get(0).line()).isEqualTo(2);
        assertThat(ambiguous.failures().get(0).reason()).contains("方向");
    }

    @Test
    @DisplayName("借贷标志列与负号同时存在时以列为准")
    void columnWinsOverSign() {
        var r = BankFlowCsvParser.parse("""
                日期,流水号,借贷标志,金额
                2026-09-20,BF006,贷,-88.00
                """);
        assertThat(r.rows().get(0).getDirection()).isEqualTo(StlBankFlow.IN);
    }

    @Test
    @DisplayName("一行坏不毁掉整份：坏行报行号，其余照常入账")
    void badLineDoesNotKillTheFile() {
        var r = BankFlowCsvParser.parse("""
                日期,流水号,借贷标志,金额
                2026-09-20,BF007,借,10.00
                哪天,BF008,借,10.00
                2026-09-22,BF009,借,abc
                2026-09-23,BF010,借,30.00
                """);
        assertThat(r.rows()).extracting(StlBankFlow::getFlowNo)
                .containsExactly("BF007", "BF010");
        assertThat(r.failures()).extracting(BankFlowCsvParser.Failure::line)
                .containsExactly(3, 4);   // 行号按原始文件数，财务要对着原文件看
    }

    @Test
    @DisplayName("表头前的说明行、空行、页脚合计行都不算失败")
    void skipsPreambleBlankAndFooter() {
        var r = BankFlowCsvParser.parse("""
                账号：6222 **** 8888
                查询区间：2026-09-01 至 2026-09-30

                日期,流水号,借贷标志,金额

                2026-09-20,BF011,借,10.00
                合计,,,10.00
                """);
        assertThat(r.rows()).hasSize(1);
        assertThat(r.failures()).isEmpty();
    }

    @Test
    @DisplayName("附言里带逗号（被引号包住）不会把列撑歪")
    void quotedCommaKeepsColumns() {
        var r = BankFlowCsvParser.parse("""
                日期,流水号,借贷标志,金额,摘要
                2026-09-20,BF012,借,10.00,"货款-E001,第二期"
                """);
        assertThat(r.rows().get(0).getRemark()).isEqualTo("货款-E001,第二期");
        assertThat(r.rows().get(0).getAmountMinor()).isEqualTo(1000L);
    }

    @Test
    @DisplayName("认不出表头就整份拒绝，而不是解析出一堆空行")
    void refusesFileWithoutHeader() {
        var r = BankFlowCsvParser.parse("这不是流水,随便写的\n1,2\n");
        assertThat(r.rows()).isEmpty();
        assertThat(r.failures()).singleElement()
                .extracting(BankFlowCsvParser.Failure::reason).asString().contains("表头");
    }
}
