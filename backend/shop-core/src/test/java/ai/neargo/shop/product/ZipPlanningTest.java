package ai.neargo.shop.product;

import ai.neargo.shop.product.dto.ZipPlanning;
import ai.neargo.shop.spi.product.GoodsVisionPort.ZipFile;
import ai.neargo.shop.spi.product.GoodsVisionPort.ZipPick;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 压缩包分类的**逐文件校验与兜底**（TDD-商品压缩包导入 AC11/AC12）。
 *
 * <p>断言一律看「这个文件最后落到哪、排第几」—— 只断言 source 的话，模型分错了也是绿的。
 */
class ZipPlanningTest {

    private static ZipFile img(String path, int w, int h) {
        return new ZipFile(path, w, h);
    }

    private static ZipPick pick(String path, String target, int order) {
        return new ZipPick(path, target, order, false);
    }

    /** 目录叫「01-首图」「02-长图」—— 规则只认「主图/详情」，于是全当主图 */
    private static final List<ZipFile> FILES = List.of(
            img("01-首图/2.jpg", 800, 800),
            img("01-首图/1.jpg", 800, 800),
            img("02-长图/详情_01.png", 750, 2400),
            img("资质/检测报告.jpg", 1240, 1754),
            new ZipFile("文案.txt", null, null));

    private static final List<ZipPick> RULE = List.of(
            pick("01-首图/1.jpg", "MAIN", 1), pick("01-首图/2.jpg", "MAIN", 2),
            pick("02-长图/详情_01.png", "MAIN", 3), pick("资质/检测报告.jpg", "MAIN", 4),
            pick("文案.txt", "TEXT", 0));

    private static String where(ZipPlanning.Plan plan, String path) {
        return plan.items().stream().filter(i -> i.path().equals(path))
                .map(i -> i.target() + "#" + i.order()).findFirst().orElse("缺");
    }

    @Test
    @DisplayName("★★★ 模型分得对就用模型的：长图进详情、资质不导，规则做不到的那一半")
    void llmMappingIsUsed() {
        var llm = List.of(
                pick("01-首图/1.jpg", "MAIN", 1), pick("01-首图/2.jpg", "MAIN", 2),
                pick("02-长图/详情_01.png", "DETAIL", 1), pick("资质/检测报告.jpg", "IGNORE", 0),
                pick("文案.txt", "TEXT", 0));
        var plan = ZipPlanning.resolve(FILES, llm, RULE);
        assertThat(plan.source()).isEqualTo("LLM");
        assertThat(where(plan, "01-首图/1.jpg")).isEqualTo("MAIN#1");
        assertThat(where(plan, "01-首图/2.jpg")).isEqualTo("MAIN#2");
        assertThat(where(plan, "02-长图/详情_01.png")).isEqualTo("DETAIL#1");
        assertThat(where(plan, "资质/检测报告.jpg")).isEqualTo("IGNORE#0");
        assertThat(where(plan, "文案.txt")).isEqualTo("TEXT#0");
    }

    @Test
    @DisplayName("★★★ 模型为空（关着/超时）→ 全按规则，一个文件不丢")
    void nullLlmFallsBackToRule() {
        var plan = ZipPlanning.resolve(FILES, null, RULE);
        assertThat(plan.source()).isEqualTo("RULE");
        assertThat(plan.items()).hasSize(5);
        assertThat(where(plan, "02-长图/详情_01.png")).isEqualTo("MAIN#3");
        assertThat(where(plan, "文案.txt")).isEqualTo("TEXT#0");
    }

    @Test
    @DisplayName("★★ 模型编的路径丢掉、漏掉的文件按规则补 —— 以端上清单为准")
    void hallucinatedDroppedMissingRuled() {
        var llm = List.of(
                pick("01-首图/1.jpg", "MAIN", 1),
                pick("01-首图/3.jpg", "MAIN", 2),            // 包里没有
                pick("02-长图/详情_01.png", "DETAIL", 1));
        var plan = ZipPlanning.resolve(FILES, llm, RULE);
        assertThat(plan.source()).isEqualTo("MIXED");
        assertThat(plan.items()).extracting(ZipPlanning.Item::path).doesNotContain("01-首图/3.jpg");
        assertThat(plan.items()).hasSize(5);
        // 模型排过的在前，兜底的跟在后面
        assertThat(where(plan, "01-首图/1.jpg")).isEqualTo("MAIN#1");
        assertThat(where(plan, "01-首图/2.jpg")).isEqualTo("MAIN#2");
        assertThat(where(plan, "资质/检测报告.jpg")).isEqualTo("MAIN#3");
    }

    @Test
    @DisplayName("★★ 不合格的那一条按规则：非法去向、图片标成文案、txt 标成主图")
    void invalidPickFallsBack() {
        var llm = List.of(
                pick("01-首图/1.jpg", "COVER", 1),           // 不在枚举里
                pick("01-首图/2.jpg", "TEXT", 0),            // 图片不能当文案
                pick("02-长图/详情_01.png", "DETAIL", 1),
                pick("资质/检测报告.jpg", "IGNORE", 0),
                pick("文案.txt", "MAIN", 1));               // txt 不能进主图
        var plan = ZipPlanning.resolve(FILES, llm, RULE);
        assertThat(where(plan, "01-首图/1.jpg")).isEqualTo("MAIN#1");
        assertThat(where(plan, "01-首图/2.jpg")).isEqualTo("MAIN#2");
        assertThat(where(plan, "文案.txt")).isEqualTo("TEXT#0");
        assertThat(plan.source()).isEqualTo("MIXED");
    }

    @Test
    @DisplayName("模型标的封面挪到主图第一张")
    void coverMovesFirst() {
        var llm = List.of(
                pick("01-首图/1.jpg", "MAIN", 1),
                new ZipPick("01-首图/2.jpg", "MAIN", 2, true),
                pick("02-长图/详情_01.png", "DETAIL", 1), pick("资质/检测报告.jpg", "IGNORE", 0),
                pick("文案.txt", "TEXT", 0));
        var plan = ZipPlanning.resolve(FILES, llm, RULE);
        assertThat(where(plan, "01-首图/2.jpg")).isEqualTo("MAIN#1");
        assertThat(where(plan, "01-首图/1.jpg")).isEqualTo("MAIN#2");
    }

    @Test
    @DisplayName("模型给的序号乱（重复、跳号）→ 重排成 1..n")
    void ordersRenumbered() {
        var llm = List.of(
                pick("01-首图/1.jpg", "MAIN", 5), pick("01-首图/2.jpg", "MAIN", 5),
                pick("02-长图/详情_01.png", "MAIN", 9), pick("资质/检测报告.jpg", "IGNORE", 0),
                pick("文案.txt", "TEXT", 0));
        var plan = ZipPlanning.resolve(FILES, llm, RULE);
        assertThat(plan.items().stream().filter(i -> i.target().equals("MAIN")).map(ZipPlanning.Item::order))
                .containsExactly(1, 2, 3);
    }
}
