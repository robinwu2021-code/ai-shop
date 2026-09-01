package ai.neargo.shop.scenario;

import ai.neargo.shop.pay.channel.PayloadMasker;
import ai.neargo.shop.pay.channel.entity.StlChannelMessage;
import ai.neargo.shop.pay.mapper.ChannelMappers;
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

import com.baomidou.mybatisplus.core.toolkit.Wrappers;

import java.util.List;
import java.util.Map;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.assertj.core.api.Assertions.assertThat;
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
                            // 解析结果里刻意带一个敏感键，验落库时被遮掉
                            ? Map.of("out_trade_no", "OT-CB-1", "sign", "SIG-SHOULD-NOT-PERSIST")
                            : null;
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
    @Autowired
    private ChannelMappers.ChannelMessageMapper messageMapper;
    @Autowired
    private ai.neargo.shop.pay.channel.ChannelMessageRecorder recorder;
    @Autowired
    @org.springframework.beans.factory.annotation.Qualifier("payTxManager")
    private org.springframework.transaction.PlatformTransactionManager payTx;

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

    // ───────────────────────── 渠道报文（V286）

    private List<StlChannelMessage> messages() {
        return messageMapper.selectList(Wrappers.<StlChannelMessage>lambdaQuery()
                .eq(StlChannelMessage::getPayChannel, CH)
                .orderByAsc(StlChannelMessage::getId));
    }

    private StlChannelMessage lastMessage() {
        List<StlChannelMessage> all = messages();
        assertThat(all).as("这次回调一行报文都没落 —— 报文要在处理之前落，不是处理完了补").isNotEmpty();
        return all.getLast();
    }

    @Test
    @DisplayName("★★★ 被拒的回调也要留报文 —— 四条拒绝路径今天各自只有一行 log.warn")
    void rejectedCallbacksAreRecordedWithReason() throws Exception {
        callback("{\"x\":\"bad\"}", "FAIL-ACK");

        StlChannelMessage m = lastMessage();
        assertThat(m.getOutcome()).isEqualTo(StlChannelMessage.REJECTED);
        // 原因直接给运营看，不能是空的 —— 「被拒了」而不说为什么等于没记
        assertThat(m.getReason()).isEqualTo("验签失败");
        assertThat(m.getMsgType()).isEqualTo(StlChannelMessage.CALLBACK);
        assertThat(m.getApi()).isEqualTo("/callback/pay/channel/" + CH);
    }

    @Test
    @DisplayName("★★ 回查说没付：报文要记下这句话，否则事后分不清是谁在说谎")
    void querySaysUnpaidIsRecorded() throws Exception {
        when(payQuery.query(anyString(), anyString()))
                .thenReturn(new PayQueryPort.Result(true, false, true, 0, "TX-1"));

        callback("{\"x\":\"good\"}", "FAIL-ACK");

        StlChannelMessage m = lastMessage();
        assertThat(m.getOutcome()).isEqualTo(StlChannelMessage.REJECTED);
        assertThat(m.getReason()).contains("回调说已支付、回查说未支付");
        assertThat(m.getBizNo()).isEqualTo("OT-CB-1");
    }

    @Test
    @DisplayName("★★★ 没验过签的报文只存指纹与前缀 —— 这个端点公网可写，原样落库就是个写入口")
    void unverifiedBodyIsNotStoredInFull() throws Exception {
        String secretish = "bad-" + "A".repeat(2000);
        callback("{\"x\":\"" + secretish + "\"}", "FAIL-ACK");

        StlChannelMessage m = lastMessage();
        assertThat(m.getPayload()).contains("sha256=").contains("未验签");
        // 全文不能在里面：2000 个 A 只留前 512
        assertThat(m.getPayload()).doesNotContain("A".repeat(600));
        // 指纹要真的是这份 body 的，不是一个占位串
        assertThat(m.getPayload())
                .contains(PayloadMasker.fingerprint("{\"x\":\"" + secretish + "\"}"));
    }

    @Test
    @DisplayName("★★★ 验签通过后才按字段存，且 sign 一律遮掉 —— 报文表会被导出、会被贴进工单")
    void acceptedCallbackStoresMaskedPayload() throws Exception {
        when(payQuery.query(anyString(), anyString()))
                .thenReturn(new PayQueryPort.Result(true, true, true, 1990, "TX-9"));

        callback("{\"x\":\"good\"}", "OK-ACK");

        StlChannelMessage m = lastMessage();
        assertThat(m.getOutcome()).isEqualTo(StlChannelMessage.ACCEPTED);
        assertThat(m.getReason()).isNull();
        assertThat(m.getBizNo()).isEqualTo("OT-CB-1");
        assertThat(m.getPayload()).contains("out_trade_no=OT-CB-1");
        assertThat(m.getPayload()).doesNotContain("SIG-SHOULD-NOT-PERSIST");
        assertThat(m.getPayload()).contains("sign=***");
    }

    @Test
    @DisplayName("★★★ 报文的事务独立于调用方 —— 调用方回滚，报文必须留下")
    void messageSurvivesCallerRollback() {
        int before = messages().size();

        /*
         * **这条才是 REQUIRES_NEW 的判据。**
         *
         * 下面那条（走真实回调、让 markPaid 抛异常）看着像在测同一件事，
         * 其实不是：controller 本身不在事务里，所以摘掉 REQUIRES_NEW
         * 它照样绿 —— 它证明的是「controller 没有外层事务」。
         * 试过了，消融不变红，这就是一条假守卫。
         *
         * 真正要守的是**将来**：谁在回调链路上加一层 @Transactional
         * （很自然的一个改动），报文就会跟着业务一起回滚，
         * 而表里从此只剩顺利的那些 —— 没人会来查顺利的那些。
         * 所以这里把那个将来当场造出来。
         */
        var tt = new org.springframework.transaction.support.TransactionTemplate(payTx);
        try {
            tt.execute(status -> {
                recorder.received(CH, "/callback/pay/channel/" + CH, Map.of(), "rollback-me");
                throw new IllegalStateException("调用方回滚");
            });
        } catch (IllegalStateException expected) {
            // 外层确实回滚了 —— 这条用例要的就是「回滚之后报文还在」
        }

        assertThat(messages())
                .as("报文跟着调用方事务一起回滚了 —— 出事的那次恰恰没有记录")
                .hasSize(before + 1);
        assertThat(lastMessage().getPayload()).contains(PayloadMasker.fingerprint("rollback-me"));
    }

    @Test
    @DisplayName("★★ 真实回调里处理抛异常，报文停在 RECEIVED —— 那本身就是线索")
    void messageSurvivesWhenProcessingBlowsUp() throws Exception {
        when(payQuery.query(anyString(), anyString()))
                .thenReturn(new PayQueryPort.Result(true, true, true, 1990, "TX-BOOM"));
        org.mockito.Mockito.doThrow(new IllegalStateException("故意炸"))
                .when(orderService).markPaid(anyString(), anyString(), anyString());

        int before = messages().size();
        // 不断言回执：异常被全局处理器接走，返回的是 500 的信封而不是通道格式。
        // 这条用例问的是「报文还在不在」，不是「回执长什么样」
        mvc().perform(post("/callback/pay/channel/" + CH)
                .contentType(MediaType.APPLICATION_JSON).content("{\"x\":\"good\"}"));

        assertThat(messages()).hasSize(before + 1);
        StlChannelMessage m = lastMessage();
        // 停在 RECEIVED 不是脏数据，是「收到了、没处理完」这句话本身 ——
        // 排查时看到它就知道该去翻应用日志的那一刻，而不是怀疑通道没推
        
        assertThat(m.getOutcome()).isEqualTo(StlChannelMessage.RECEIVED);
    }

    @Test
    @DisplayName("★★ 未知通道不落报文 —— 通道名来自路径，落库就多一条往任意通道名灌报文的路")
    void unknownChannelRecordsNothing() throws Exception {
        int before = messageMapper.selectCount(Wrappers.<StlChannelMessage>lambdaQuery()).intValue();

        mvc().perform(post("/callback/pay/channel/NOPE")
                        .contentType(MediaType.APPLICATION_JSON).content("{\"x\":\"good\"}"))
                .andExpect(content().string("FAIL"));

        assertThat(messageMapper.selectCount(Wrappers.<StlChannelMessage>lambdaQuery()).intValue())
                .isEqualTo(before);
    }
}
