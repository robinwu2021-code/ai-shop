package ai.neargo.shop.elec.svc;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.web.context.WebApplicationContext;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * ★★ 没配 {@code elec.dev-cors-origins}（生产）：谁都不放行 —— 同域经 nginx，链上不该有 CORS。
 * 这一半比「配了能放行」更要紧：默认开着，等于把一个只在开发期需要的口子带进生产。
 */
@SpringBootTest(classes = ElecApplication.class)
@ActiveProfiles("test")
@Import(FakeMainSystem.Config.class)
class ElecDevCorsOffTest {

    @Autowired
    private WebApplicationContext ctx;

    @Test
    @DisplayName("★★ 没配（生产）：运营端的源也不放行")
    void noCorsByDefault() throws Exception {
        assertThat(ElecDevCorsTest.preflight(ctx, ElecDevCorsTest.OPS_WEB).getResponse()
                .getHeader("Access-Control-Allow-Origin")).isNull();
    }
}
