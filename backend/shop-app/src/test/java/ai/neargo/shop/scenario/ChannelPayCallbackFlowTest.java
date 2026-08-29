package ai.neargo.shop.scenario;

import ai.neargo.shop.spi.pay.ChannelCallbackVerifier;
import ai.neargo.shop.spi.pay.PayQueryPort;
import ai.neargo.shop.trade.service.OrderService;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;
import org.springframework.http.MediaType;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.web.context.WebApplicationContext;

import java.util.Map;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;

/**
 * 支付回调的三步顺序：**验签 → 回查 → 落库**，一步都不能省。
 *
 * <p>这个类不需要任何通道凭证 —— 用一个假验签实现顶替真通道，
 * 测的是<b>骨架的判断</b>而不是某一家的签名算法。真验签另有测试（用官方测试向量）。
 *
 * <p><b>最要紧的是第三条</b>：通道推「已支付」而回查说「未支付」时不能落库。
 * 两句话不能都对，而当成已支付会给一笔没付的单发货。
 */
@SpringBootTest(properties = {
        /*
         * **必须另开一个库。** 这个类因为 @Import 与 @MockitoBean 而拿到一个**新的**
         * Spring 上下文，而上下文初始化会把 schema-test.sql 再跑一遍 ——
         * 跑在同一个 `jdbc:h2:mem:shop` 上就是往已经有数据的表里重插种子，
         * 整个上下文起不来，症状与本用例毫无关系（单独跑绿、全量跑红）。
         * 与 PayChannelUnavailableFlowTest 同一手法。
         */
        "spring.datasource.url=jdbc:h2:mem:pay-callback;MODE=MySQL;DATABASE_TO_LOWER=TRUE;DB_CLOSE_DELAY=-1",
})
@ActiveProfiles({"test", "api"})
@Import(ChannelPayCallbackFlowTest.FakeChannel.class)
class ChannelPayCallbackFlowTest {

    static final String CH = "TESTCH";

    @TestConfiguration
    static class FakeChannel {
        @Bean
        ChannelCallbackVerifier fakeVerifier() {
            return new ChannelCallbackVerifier() {
                @Override
                public String payChannel() {
                    return CH;
                }

                /** 报文里带 good 才算验签通过 —— 够用来分「过」与「不过」两条路 */
                @Override
                public Map<String, Object> verify(Map<String, String> headers, String rawBody) {
                    return rawBody != null && rawBody.contains("good")
                            ? Map.of("out_trade_no", "OT-CB-1") : null;
                }

                @Override
                public String ackOk() {
                    return "OK-ACK";
                }

                @Override
                public String ackFail() {
                    return "FAIL-ACK";
                }
            };
        }
    }

    @Autowired
    private WebApplicationContext context;
    @MockitoBean
    private PayQueryPort payQuery;
    @MockitoBean
    private OrderService orderService;

    private MockMvc mvc() {
        return MockMvcBuilders.webAppContextSetup(context).build();
    }

    private void callback(String body, String expectBody) throws Exception {
        mvc().perform(post("/callback/pay/channel/" + CH)
                        .contentType(MediaType.APPLICATION_JSON).content(body))
                .andExpect(content().string(expectBody));
    }

    @Test
    @DisplayName("★★★ 验签不过：不回查、不落库，回执用通道自己的格式")
    void badSignatureStopsEverything() throws Exception {
        callback("{\"x\":\"bad\"}", "FAIL-ACK");

        verify(payQuery, never()).query(anyString(), anyString());
        verify(orderService, never()).markPaid(anyString(), anyString(), anyString());
    }

    @Test
    @DisplayName("★★★ 回查失败：回 FAIL 让通道重推 —— 吞掉就再没人提起这笔")
    void queryFailureAsksForRetry() throws Exception {
        when(payQuery.query(anyString(), anyString()))
                .thenReturn(new PayQueryPort.Result(false, false, false, 0, null));

        callback("{\"x\":\"good\"}", "FAIL-ACK");
        verify(orderService, never()).markPaid(anyString(), anyString(), anyString());
    }

    @Test
    @DisplayName("★★★ 回调说已支付、回查说没付：不落库 —— 两句话不能都对")
    void callbackClaimsPaidButQuerySaysNot() throws Exception {
        when(payQuery.query(anyString(), anyString()))
                .thenReturn(new PayQueryPort.Result(true, false, true, 0, "TX-1"));

        callback("{\"x\":\"good\"}", "FAIL-ACK");
        verify(orderService, never()).markPaid(anyString(), anyString(), anyString());
    }

    @Test
    @DisplayName("★★★ 回查确认已支付：走原本的支付成功链路，不另写一段补状态")
    void verifiedAndQueriedThenMarksPaid() throws Exception {
        when(payQuery.query(anyString(), anyString()))
                .thenReturn(new PayQueryPort.Result(true, true, true, 1990, "TX-9"));

        callback("{\"x\":\"good\"}", "OK-ACK");
        verify(orderService, times(1)).markPaid("OT-CB-1", CH, "TX-9");
    }

    @Test
    @DisplayName("★★★ 没接的通道当没这个端点 —— 不回「通道未接入」，那等于告诉扫端点的人这里认得它")
    void unknownChannelRevealsNothing() throws Exception {
        mvc().perform(post("/callback/pay/channel/NOPE")
                        .contentType(MediaType.APPLICATION_JSON).content("{\"x\":\"good\"}"))
                .andExpect(content().string("FAIL"));

        verify(payQuery, never()).query(anyString(), anyString());
    }
}
