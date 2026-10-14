package ai.neargo.shop.channel.ai.port;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 经营范围识别：提示词的硬约束 + 模型返回体的解析（TDD-经营范围文字录入 §7）。
 *
 * <p><b>判据取提示词文本与解析，不取模型输出</b>（同 GoodsDescribePromptTest 的理由）：
 * 模型是外部依赖，拿它的输出当断言就是把闸门建在别人家的服务上。输出质量另用样本实跑验，记在 TDD。
 */
@DisplayName("经营范围识别：提示词与返回体解析")
class ScopePromptParseTest {

    private final ObjectMapper json = new ObjectMapper();

    @Test
    @DisplayName("提示词：店主给的三句原话都是示例，且写明「整条行政路径、不写码」「方向管整句」「常识展开标 guess」")
    void promptCarriesTheContract() {
        String p = GoodsVisionGateway.SCOPE_PROMPT;
        assertThat(p).contains("全国发货，新疆、西藏不发", "除了新疆西藏的其他区域", "深圳，山西运城，广东等");
        assertThat(p).contains("[\"山西省\",\"运城市\"]", "方向管整句", "guess");
        assertThat(p).as("不能让模型给区划码").doesNotContain("regionCode", "\"code\"");
    }

    @Test
    @DisplayName("解析：容忍代码块和前后废话；路径、方向、guess、unclear 原样带出")
    void parsesFencedJson() {
        var r = GoodsVisionGateway.parseScope(json, """
                好的，结果如下：
                ```json
                {"unlimited":false,"places":[
                  {"path":["山西省","运城市"],"mode":"INCLUDE","text":"山西运城","guess":false},
                  {"path":["江苏省"],"mode":"include","text":"江浙沪","guess":true}],
                 "unclear":["老城区"]}
                ```""");
        assertThat(r).isNotNull();
        assertThat(r.unlimited()).isFalse();
        assertThat(r.places()).hasSize(2);
        assertThat(r.places().get(0).path()).containsExactly("山西省", "运城市");
        assertThat(r.places().get(1).mode()).as("大小写归一").isEqualTo("INCLUDE");
        assertThat(r.places().get(1).guess()).isTrue();
        assertThat(r.unclear()).containsExactly("老城区");
    }

    @Test
    @DisplayName("方向不明、路径为空的地点丢掉 —— 宁可少一条，也不要一条方向不明的")
    void dropsUnusablePlaces() {
        var r = GoodsVisionGateway.parseScope(json, """
                {"unlimited":true,"places":[
                  {"path":["新疆维吾尔自治区"],"mode":"EXCLUDE","text":"新疆"},
                  {"path":["西藏自治区"],"mode":"MAYBE","text":"西藏"},
                  {"path":[],"mode":"INCLUDE","text":"空"}],"unclear":[]}""");
        assertThat(r.unlimited()).isTrue();
        assertThat(r.places()).extracting(p -> p.text()).containsExactly("新疆");
    }

    @Test
    @DisplayName("返回体里没有 JSON → null（调用方退回规则），不抛")
    void noJsonIsNull() {
        assertThat(GoodsVisionGateway.parseScope(json, "抱歉，我无法确定。")).isNull();
        assertThat(GoodsVisionGateway.parseScope(json, null)).isNull();
    }

    @Test
    @DisplayName("没配模型时 extractScope 直接 null，不发请求")
    void disabledReturnsNull() {
        assertThat(new GoodsVisionGateway("", "m", "", 25, false).extractScope("全国发货")).isNull();
    }
}
