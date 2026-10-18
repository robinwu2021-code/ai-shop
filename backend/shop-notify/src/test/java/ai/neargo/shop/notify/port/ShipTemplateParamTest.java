package ai.neargo.shop.notify.port;

import org.junit.jupiter.api.Test;
import tools.jackson.databind.json.JsonMapper;

import static org.assertj.core.api.Assertions.assertThat;

class ShipTemplateParamTest {
    @Test
    void preservesRecipientNameAndEscapesSpecialCharacters() {
        String name = "王\"女士\\家";
        var params = JsonMapper.builder().build().readTree(
                AliSmsGateway.shipTemplateParam(name, "https://s.hxmall.top/ABC1234"));
        assertThat(params.get("name").asText()).isEqualTo(name);
        assertThat(params.get("code").asText()).isEqualTo("ABC1234");
        assertThat(params.size()).isEqualTo(2);
    }
}
