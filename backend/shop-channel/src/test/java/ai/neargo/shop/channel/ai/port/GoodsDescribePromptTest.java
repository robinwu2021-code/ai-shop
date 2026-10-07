package ai.neargo.shop.channel.ai.port;

import ai.neargo.shop.spi.product.GoodsVisionPort.ParamKV;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 图文详情提示词：把商家已填的参数喂给模型（TDD-商品描述带参数生成）。
 *
 * <p><b>判据取提示词文本，不取模型输出。</b> 模型是外部依赖、输出不确定，
 * 拿它当断言就是把闸门建在别人家的服务上（`known-failures.txt` 头部：恒红的闸门等于没有闸门）。
 * 输出质量仍按 v1→v3 的老办法验：跑样本、读输出，不进自动化闸门。
 *
 * <p>根因提醒：线上柿子的正文全是「催熟与清洗」，不是提示词写坏了 ——
 * v3 第一句就是「你只知道商品名、卖点、类目这三项」，而柿子标题三个字、卖点是空的，
 * 模型手上就这些。**补事实，不放宽规则。**
 */
@DisplayName("图文详情提示词：已填参数作为已知事实")
class GoodsDescribePromptTest {

    /** 不连模型，只拼提示词 —— enabled=false 也不影响 describePrompt */
    private final GoodsVisionGateway gw = new GoodsVisionGateway("", "m", "", 25, false);

    private static final List<ParamKV> PERSIMMON = List.of(
            new ParamKV("产地", "山西运城临猗"),
            new ParamKV("口感风味", "脆爽"),
            new ParamKV("储存条件", "常温"));

    @Test
    @DisplayName("★★★ AC1 已填参数要进提示词，名与值都在")
    void knownFactsGoIntoPrompt() {
        String p = gw.describePrompt("脆柿子", "", "食品生鲜/水果", PERSIMMON);

        assertThat(p).as("要有「已知事实」这一段").contains("已知事实");
        assertThat(p).as("产地的名与值都要在").contains("产地").contains("山西运城临猗");
        assertThat(p).contains("口感风味").contains("脆爽");
        assertThat(p).contains("储存条件").contains("常温");
    }

    @Test
    @DisplayName("★★★ AC2 禁写清单一条不少 —— 补事实不等于放宽规则")
    void forbiddenListStaysIntact() {
        String p = gw.describePrompt("脆柿子", "", "食品生鲜/水果", PERSIMMON);

        // v3 那张清单：一条都不许因为「现在有参数了」而消失
        assertThat(p).as("养殖/种植方式").contains("散养");
        assertThat(p).as("外观与口感的自由发挥").contains("饱满");
        assertThat(p).as("产地品牌等级保质期认证").contains("产地、品牌、等级、保质期、认证、执行标准");
        assertThat(p).as("数量与库存").contains("限量多少");
        assertThat(p).as("**时间承诺** —— v2 栽的就是这一条").contains("什么时候截单");
        assertThat(p).as("营销话术").contains("性价比高");
        assertThat(p).as("「你只知道这三项」那句总纲").contains("别的一概不知道");
    }

    @Test
    @DisplayName("★★★ AC3 不带参数时，提示词与加这个参数之前逐字相同")
    void promptUnchangedWhenNoFacts() {
        String withNull = gw.describePrompt("脆柿子", "", "食品生鲜/水果", null);
        String withEmpty = gw.describePrompt("脆柿子", "", "食品生鲜/水果", List.of());

        assertThat(withNull).as("null 与空列表要等价").isEqualTo(withEmpty);
        assertThat(withNull).as("空参数时一个字都不该拼「已知事实」").doesNotContain("已知事实");
        // 老调用方（不发 params）拿到的必须还是那份 v3 —— 结尾那句是 v3 的最后一行
        assertThat(withNull.trim()).endsWith("类目：食品生鲜/水果");
    }

    @Test
    @DisplayName("★★★ AC4 写明只许照抄不许引申 —— 填了「常温」不等于可以写「常温保存更香甜」")
    void factsMustBeCopiedNotExtended() {
        String p = gw.describePrompt("脆柿子", "", "食品生鲜/水果", PERSIMMON);
        assertThat(p).contains("只能照抄，不许在它们之上引申");
        assertThat(p).as("给出反例，比只写一条规则管用").contains("常温保存更香甜");
    }

    @Test
    @DisplayName("★★★ 售后类参数不进提示词 —— 那是我们兑不了的承诺")
    void afterSaleParamsAreFiltered() {
        String p = gw.describePrompt("脆柿子", "", "食品生鲜/水果",
                List.of(new ParamKV("产地", "山西运城临猗"),
                        new ParamKV("售后说明", "坏果包赔，48小时内发货")));

        assertThat(p).as("正常参数照进").contains("山西运城临猗");
        assertThat(p).as("售后那一格要被挡掉").doesNotContain("坏果包赔");
        assertThat(p).doesNotContain("售后说明");
    }

    @Test
    @DisplayName("★★ 空名或空值的参数不拼进去 —— 「产地：」是一行废话")
    void blankFactsAreSkipped() {
        String p = gw.describePrompt("脆柿子", "", "食品生鲜/水果",
                List.of(new ParamKV("产地", "  "), new ParamKV("  ", "脆爽"),
                        new ParamKV("储存条件", "常温")));

        // 用「· 名：值」整串做判据，而不是数「· 」出现几次 ——
        // v3 正文里本来就满是「· 」（格式规则与禁写清单都用它），数它量的是另一回事。
        assertThat(p).as("有效的那条照进").contains("· 储存条件：常温");
        assertThat(p).as("值是空白的不拼").doesNotContain("· 产地：");
        assertThat(p).as("名是空白的不拼").doesNotContain("：脆爽");
    }
}
