package ai.neargo.shop.elec.service.impl;

import ai.neargo.shop.elec.gateway.ElecColumnAi;
import ai.neargo.shop.elec.support.Columns.Field;
import ai.neargo.shop.elec.support.SeedAliases;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.support.StaticListableBeanFactory;

import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.Supplier;

import static org.assertj.core.api.Assertions.assertThat;

class ColumnResolverTest {

    /** 别名表 = 种子（不连库） */
    private static final HeaderAliases SEED = new HeaderAliases(null) {
        @Override
        public Map<String, Field> lookup(String supplierNo) {
            return SeedAliases.map();
        }
    };

    /** 能数调用次数、回什么由用例定的大模型 */
    static final class FakeAi implements ElecColumnAi {
        final AtomicInteger calls = new AtomicInteger();
        Supplier<Guess> answer = () -> null;

        @Override
        public boolean isEnabled() {
            return true;
        }

        @Override
        public Guess guess(List<List<String>> head, List<FieldSpec> fields) {
            calls.incrementAndGet();
            return answer.get();
        }
    }

    /** 厂牌别名（按内容猜厂牌列用）：不连库 */
    private static final ElecPartCatalog MFRS = new ElecPartCatalog(null, null, null) {
        @Override
        public Map<String, String> aliases() {
            return Map.of("ST", "ST", "TI", "TI", "MURATA", "MURATA");
        }
    };

    private static ColumnResolver resolver(FakeAi ai) {
        StaticListableBeanFactory f = new StaticListableBeanFactory();
        f.addBean("ai", ai);
        return new ColumnResolver(SEED, f.getBeanProvider(ElecColumnAi.class), MFRS);
    }

    /** 没有标题行：第一行就是数据 */
    private static final List<List<String>> NO_HEADER = List.of(
            List.of("STM32F103C8T6", "ST", "2500", "2338"),
            List.of("TPS54331DR", "TI", "10K", "23+"),
            List.of("GRM188R71H104KA93D", "MURATA", "400000", "24+"));

    @Test
    @DisplayName("★★★ 文件根本没有标题行：认出来是数据（headerRow=-1，第一行不被吃掉），按内容认出料号、厂牌、数量")
    void noHeaderFileSniffedByContent() {
        ColumnResolver.Resolution r = resolver(new FakeAi()).resolve("S1", NO_HEADER, null);
        assertThat(r.headerRow()).isEqualTo(ColumnResolver.NO_HEADER);
        assertThat(r.complete()).isTrue();
        assertThat(r.map()).containsEntry(Field.MPN, 0).containsEntry(Field.MFR, 1).containsEntry(Field.QTY, 2);
        assertThat(r.source()).containsEntry(Field.MPN, ColumnResolver.SRC_CONTENT);
    }

    @Test
    @DisplayName("★★★ 有标题但一个都认不出、大模型也不在：表头照认，列按内容猜，来源 CONTENT 要他核对")
    void unmatchedHeaderSniffedByContent() {
        List<List<String>> rows = new java.util.ArrayList<>();
        rows.add(List.of("X1", "X2", "X3", "X4"));
        rows.addAll(NO_HEADER);
        ColumnResolver.Resolution r = resolver(new FakeAi()).resolve("S1", rows, null);
        assertThat(r.headerRow()).isZero();
        assertThat(r.complete()).isTrue();
        assertThat(r.map()).containsEntry(Field.MPN, 0).containsEntry(Field.QTY, 2);
    }

    @Test
    @DisplayName("★★ 按内容猜：单价列（6.8）与批号列不会被当成数量，纯数字的列不会被当成料号")
    void sniffIsStrict() {
        List<List<String>> rows = List.of(
                List.of("A", "B", "C"),
                List.of("6.8", "STM32F103C8T6", "2500"),
                List.of("1.25", "TPS54331DR", "300"));
        ColumnResolver.Resolution r = resolver(new FakeAi()).resolve("S1", rows, null);
        assertThat(r.map()).containsEntry(Field.MPN, 1).containsEntry(Field.QTY, 2);
    }

    @Test
    @DisplayName("★★ 大模型说没有标题行（-1）：照此从第一行起当数据")
    void aiSaysNoHeader() {
        FakeAi ai = new FakeAi();
        ai.answer = () -> new ElecColumnAi.Guess(-1, Map.of("MPN", 0, "QTY", 2));
        ColumnResolver.Resolution r = resolver(ai).resolve("S1", NO_HEADER, null);
        assertThat(r.headerRow()).isEqualTo(ColumnResolver.NO_HEADER);
        assertThat(r.source()).containsEntry(Field.MPN, ColumnResolver.SRC_AI);
    }

    @Test
    @DisplayName("★★ 内容也猜不出：待选列，表头行取第一行（他在页面上看着每列的内容选）")
    void nothingSniffableNeedsMapping() {
        ColumnResolver.Resolution r = resolver(new FakeAi()).resolve("S1",
                List.of(List.of("备注", "说明"), List.of("随便", "写写")), null);
        assertThat(r.complete()).isFalse();
        assertThat(r.headerRow()).isZero();
    }

    /** 实测过的那张表（2026-09-30 从生产机问 qwen） */
    private static final List<List<String>> ITEM_MAKER_STK = List.of(
            List.of("序", "Item", "Maker", "Stk", "年份", "Pkg", "含税价", "备注"),
            List.of("1", "STM32F103C8T6", "ST", "2,500", "23+", "LQFP48", "6.8", "原装"),
            List.of("2", "TPS54331DR", "TI", "10K", "2338", "SOIC8", "1.25", ""),
            List.of("3", "GRM188R71H104KA93D", "MURATA", "400000", "24+", "0603", "0.008", "整盘"));

    @Test
    @DisplayName("★★★ AC1 别名表认全了（含厂牌）就不调大模型，来源记 ALIAS")
    void aliasHitSkipsAi() {
        FakeAi ai = new FakeAi();
        ColumnResolver.Resolution r = resolver(ai).resolve("S1", List.of(
                List.of("型号", "制造商", "库存数量"), List.of("STM32F103C8T6", "ST", "100")), null);
        assertThat(ai.calls.get()).isZero();
        assertThat(r.complete()).isTrue();
        assertThat(r.source()).containsEntry(Field.MPN, ColumnResolver.SRC_ALIAS);
    }

    @Test
    @DisplayName("★★★ AC2 规则认不出的表头交给大模型；它只补缺的字段，别名认出的（年份→批号）它改不了")
    void aiFillsOnlyMissingFields() {
        FakeAi ai = new FakeAi();
        // 模型把批号说成第 7 列「备注」（错的，且这一列没被别的字段占用 —— 不然拦住它的是「列已占用」那道，
        // 测不到「别名认出的字段模型不许改」这一道）：年份那一列别名已经认出，不许它改
        ai.answer = () -> new ElecColumnAi.Guess(0, Map.of("MPN", 1, "MFR", 2, "QTY", 3, "DC", 7, "PACKAGE", 5));
        ColumnResolver.Resolution r = resolver(ai).resolve("S1", ITEM_MAKER_STK, null);
        assertThat(ai.calls.get()).isEqualTo(1);
        assertThat(r.complete()).isTrue();
        assertThat(r.map()).containsEntry(Field.MPN, 1).containsEntry(Field.MFR, 2).containsEntry(Field.QTY, 3)
                .containsEntry(Field.DC, 4);
        assertThat(r.source()).containsEntry(Field.MPN, ColumnResolver.SRC_AI)
                .containsEntry(Field.DC, ColumnResolver.SRC_ALIAS);
        assertThat(r.aiUsed()).isTrue();
    }

    @Test
    @DisplayName("★★★ AC3 大模型说「备注」是料号：样本里大多不像料号，这个字段丢掉")
    void aiColumnFailingContentCheckIsDropped() {
        FakeAi ai = new FakeAi();
        ai.answer = () -> new ElecColumnAi.Guess(0, Map.of("MPN", 7, "QTY", 3, "MFR", 2));
        ColumnResolver.Resolution r = resolver(ai).resolve("S1", ITEM_MAKER_STK, null);
        assertThat(r.map().get(Field.MPN)).as("模型说的第 7 列（备注）丢掉；按内容补回来的是第 1 列").isEqualTo(1);
        assertThat(r.source()).containsEntry(Field.MPN, ColumnResolver.SRC_CONTENT).containsEntry(Field.QTY, "AI");
    }

    @Test
    @DisplayName("★★★ 大模型把「备注」认成货况：备注 / 联系方式类的列不采纳（常写着公司名和微信，导进去身份就漏了）")
    void privateColumnsNeverTakenFromAi() {
        FakeAi ai = new FakeAi();
        ai.answer = () -> new ElecColumnAi.Guess(0, Map.of("MPN", 1, "MFR", 2, "QTY", 3, "CONDITION", 7));
        ColumnResolver.Resolution r = resolver(ai).resolve("S1", ITEM_MAKER_STK, null);
        assertThat(r.map()).containsEntry(Field.MPN, 1).doesNotContainKey(Field.CONDITION);
    }

    @Test
    @DisplayName("★★ 大模型把数量列说成厂牌：厂牌不会是纯数字，拦住")
    void numericMfrRejected() {
        assertThat(ColumnResolver.verify(Field.MFR, ITEM_MAKER_STK, 0, 3)).isFalse();
        assertThat(ColumnResolver.verify(Field.MFR, ITEM_MAKER_STK, 0, 2)).isTrue();
    }

    @Test
    @DisplayName("★★★ AC4 大模型不可用：不报错；按内容补上料号与数量（来源 CONTENT），表头行取认出字段最多的那行")
    void aiDownFallsBackToContent() {
        FakeAi ai = new FakeAi();
        ColumnResolver.Resolution r = resolver(ai).resolve("S1", ITEM_MAKER_STK, null);
        assertThat(r).isNotNull();
        assertThat(r.headerRow()).isZero();
        assertThat(r.complete()).isTrue();
        assertThat(r.source()).containsEntry(Field.MPN, ColumnResolver.SRC_CONTENT)
                .containsEntry(Field.QTY, ColumnResolver.SRC_CONTENT);
    }

    @Test
    @DisplayName("★★ 连续失败 3 次熔断：第 4 次上传不再去等超时")
    void breakerOpensAfterThreeFailures() {
        FakeAi ai = new FakeAi();
        ColumnResolver res = resolver(ai);
        for (int i = 0; i < 4; i++) {
            res.resolve("S1", ITEM_MAKER_STK, null);
        }
        assertThat(ai.calls.get()).isEqualTo(3);
        res.resetBreaker();
        res.resolve("S1", ITEM_MAKER_STK, null);
        assertThat(ai.calls.get()).isEqualTo(4);
    }

    @Test
    @DisplayName("★★ 记住的映射优先，一次都不调模型")
    void rememberedWins() {
        FakeAi ai = new FakeAi();
        ColumnResolver.Resolution r = resolver(ai).resolve("S1", ITEM_MAKER_STK,
                new ColumnResolver.Remembered(0, Map.of(Field.MPN, 1, Field.QTY, 3)));
        assertThat(ai.calls.get()).isZero();
        assertThat(r.source()).containsEntry(Field.MPN, ColumnResolver.SRC_REMEMBERED);
    }

    @Test
    @DisplayName("★★ 发给模型的样本有上限：前几行、每格 40 字符")
    void headIsCapped() {
        List<List<String>> rows = new java.util.ArrayList<>();
        rows.add(List.of("型号", "x".repeat(100)));
        for (int i = 0; i < 30; i++) {
            rows.add(List.of("A" + i, "1"));
        }
        List<List<String>> head = ColumnResolver.head(rows);
        assertThat(head.size()).isLessThanOrEqualTo(ColumnResolver.AI_DATA_ROWS + 3);
        assertThat(head.get(0).get(1)).hasSize(ColumnResolver.AI_CELL_CHARS);
    }
}
