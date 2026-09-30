package ai.neargo.shop.elec.svc;

import ai.neargo.shop.elec.config.ElecProperties;
import ai.neargo.shop.elec.gateway.ElecColumnAi;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import tools.jackson.databind.ObjectMapper;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

class QwenColumnAiTest {

    private static QwenColumnAi ai(String url) {
        ElecProperties p = new ElecProperties();
        p.getAi().setEnabled(url != null);
        p.getAi().setBaseUrl(url == null ? "" : url);
        return new QwenColumnAi(p, new ObjectMapper());
    }

    @Test
    @DisplayName("★★ 回包容忍 ``` 包裹与前后多余的字；columns 不是对象、不是 JSON 都回 null")
    void parse() {
        QwenColumnAi q = ai(null);
        assertThat(q.parse("```json\n{\"headerRow\":1,\"columns\":{\"MPN\":0,\"QTY\":2}}\n```"))
                .isEqualTo(new ElecColumnAi.Guess(1, java.util.Map.of("MPN", 0, "QTY", 2)));
        assertThat(q.parse("好的：{\"headerRow\":0,\"columns\":{\"MPN\":1,\"MFR\":\"x\"}} 以上"))
                .as("不是整数的列号丢掉").isEqualTo(new ElecColumnAi.Guess(0, java.util.Map.of("MPN", 1)));
        assertThat(q.parse("{\"headerRow\":0,\"columns\":[1,2]}")).isNull();
        assertThat(q.parse("认不出来")).isNull();
        assertThat(q.parse(null)).isNull();
    }

    @Test
    @DisplayName("★★ 关着或没配地址：不启用、不发请求")
    void disabledWithoutUrl() {
        assertThat(ai(null).isEnabled()).isFalse();
        assertThat(ai(null).guess(List.of(List.of("a")), List.of())).isNull();
    }

    /**
     * 真连 cdw 上的 qwen。<b>默认跳过</b>（依赖外网，不进 pre-push）；上线前手工跑一次：
     * {@code ELEC_AI_LIVE_URL=http://cdw.near3.ai:8003/v1 mvn -o -pl elec/elec-svc -am test -Dtest=QwenColumnAiTest}
     */
    @Test
    @EnabledIfEnvironmentVariable(named = "ELEC_AI_LIVE_URL", matches = "http.+")
    @DisplayName("★ 真连：Item / Maker / Stk 这张表，模型认出料号、厂牌、数量")
    void live() {
        ElecColumnAi.Guess g = ai(System.getenv("ELEC_AI_LIVE_URL")).guess(List.of(
                List.of("序", "Item", "Maker", "Stk", "年份", "Pkg", "含税价", "备注"),
                List.of("1", "STM32F103C8T6", "ST", "2,500", "23+", "LQFP48", "6.8", "原装"),
                List.of("2", "TPS54331DR", "TI", "10K", "2338", "SOIC8", "1.25", "")),
                List.of(new ElecColumnAi.FieldSpec("MPN", "料号 / 型号"), new ElecColumnAi.FieldSpec("MFR", "厂牌"),
                        new ElecColumnAi.FieldSpec("QTY", "库存数量"), new ElecColumnAi.FieldSpec("DC", "批号 / 年份")));
        assertThat(g).isNotNull();
        assertThat(g.headerRow()).isZero();
        assertThat(g.columns()).containsEntry("MPN", 1).containsEntry("MFR", 2).containsEntry("QTY", 3);
    }
}
