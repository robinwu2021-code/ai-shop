package ai.neargo.shop.pay.channel;

import ai.neargo.shop.pay.channel.base.AbstractPayGateway;
import ai.neargo.shop.pay.channel.entity.SysPayChannel;
import ai.neargo.shop.pay.channel.master.PayChannelMasterService;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 我方发给通道的每一次资金调用都要留报文（V286）。
 *
 * <p><b>四条路径一条都不能漏</b>：成功、通道回执失败、回执缺字段、调用异常。
 * 最要紧的是最后一条 —— 超时的时候「到底发出去没有」是真的不知道，
 * 而那正是对账那天要问的一笔。日志里那行会随日志滚掉。
 *
 * <p>不测「能力位拦下」和「前置不满足」两条：那两条<b>根本没发出去</b>，
 * 没有报文才是对的。给没发生的调用记一行报文，
 * 会让排查的人以为通道收到过。
 */
class GatewayRecordsMessagesTest {

    /** 把落库那一步接下来，只记不写库 */
    static final class CapturingRecorder extends ChannelMessageRecorder {
        record Sent(String channel, String api, String bizNo, boolean ok, String reason) {
        }

        final List<Sent> sent = new ArrayList<>();

        CapturingRecorder() {
            super(null, 90);
        }

        @Override
        public void sent(String payChannel, String api, String bizNo,
                         boolean ok, String reason, Map<String, ?> body) {
            sent.add(new Sent(payChannel, api, bizNo, ok, reason));
        }
    }

    /** 按脚本回应的假通道 */
    static final class ScriptedClient implements ChannelClient {
        Map<String, Object> reply = Map.of();
        ChannelException boom;

        @Override
        public Map<String, Object> post(String api, Map<String, Object> body) {
            if (boom != null) {
                throw boom;
            }
            return reply;
        }
    }

    /** 最小可用的网关：退款一条路够验骨架 */
    static final class TestGateway extends AbstractPayGateway {
        TestGateway(ChannelClient c, ChannelMessageRecorder r) {
            super(c, alwaysCapable(), r);
        }

        @Override
        public String payChannel() {
            return "TESTCH";
        }

        @Override
        protected Call buildSubsidy(TxContext ctx, long a, String no, String d) {
            return refundCall(no);
        }

        @Override
        protected Call buildSubsidyReturn(TxContext ctx, long a, String no, String d) {
            return refundCall(no);
        }

        @Override
        protected Call buildSplit(TxContext ctx, long a, String no) {
            return refundCall(no);
        }

        @Override
        protected Call buildSplitReverse(TxContext ctx, long a, String no) {
            return refundCall(no);
        }

        @Override
        protected Call buildRefund(TxContext ctx, long a, String no, String reason) {
            return refundCall(no);
        }

        private Call refundCall(String requestNo) {
            return new Call("/test/refund", Map.of("out_refund_no", requestNo, "sign", "SIG"),
                    "refund_id");
        }

        @Override
        protected String failureOf(String api, Map<String, Object> resp) {
            return "FAIL".equals(resp.get("result")) ? "通道说失败" : null;
        }

        /** 这组用例只测发送侧留痕，回查另有测试 */
        @Override
        public QueryResult query(String outTradeNo) {
            return QueryResult.notFound();
        }
    }

    /**
     * 能力位全开的假主数据。
     *
     * <p>其余方法抛异常而不是返回空值：这组用例只该碰 {@code supportsSubsidy}，
     * <b>碰到别的就是被测代码走了预期外的路</b>，那要当场炸而不是拿一个 null 继续。
     */
    private static PayChannelMasterService alwaysCapable() {
        return new PayChannelMasterService() {
            @Override
            public boolean supportsSubsidy(String payChannel) {
                return true;
            }

            @Override
            public Optional<SysPayChannel> find(String c) {
                throw new UnsupportedOperationException("这组用例不该走到 find");
            }

            @Override
            public List<SysPayChannel> enabled(String market) {
                throw new UnsupportedOperationException("这组用例不该走到 enabled");
            }

            @Override
            public List<String> marketsOf(String payChannel) {
                throw new UnsupportedOperationException("这组用例不该走到 marketsOf");
            }

            @Override
            public String settleCycle(String payChannel) {
                throw new UnsupportedOperationException("这组用例不该走到 settleCycle");
            }

            @Override
            public List<SysPayChannel> all() {
                throw new UnsupportedOperationException("这组用例不该走到 all");
            }

            @Override
            public SysPayChannel updateSettings(
                    String payChannel, Boolean enabled, String markets, String a, String b) {
                throw new UnsupportedOperationException("这组用例不该走到 updateSettings");
            }
        };
    }

    private static final PayGateway.TxContext CTX =
            new PayGateway.TxContext("SUB-1", "TX-1", "OT-1", 10000L);

    @Test
    @DisplayName("★★ 成功也要留一行 —— 「这笔到底发出去没有」是对账第一个要问的")
    void successIsRecorded() {
        ScriptedClient c = new ScriptedClient();
        c.reply = Map.of("refund_id", "RF-9");
        CapturingRecorder r = new CapturingRecorder();

        new TestGateway(c, r).refund(CTX, 100L, "RQ-1", "七天无理由");

        assertThat(r.sent).singleElement().satisfies(s -> {
            assertThat(s.ok()).isTrue();
            assertThat(s.bizNo()).isEqualTo("RQ-1");
            assertThat(s.api()).isEqualTo("/test/refund");
            assertThat(s.channel()).isEqualTo("TESTCH");
        });
    }

    @Test
    @DisplayName("★★ 通道回执失败：报文要带上原因，否则只知道失败不知道为什么")
    void channelFailureIsRecordedWithReason() {
        ScriptedClient c = new ScriptedClient();
        c.reply = Map.of("result", "FAIL");
        CapturingRecorder r = new CapturingRecorder();

        new TestGateway(c, r).refund(CTX, 100L, "RQ-2", "x");

        assertThat(r.sent).singleElement().satisfies(s -> {
            assertThat(s.ok()).isFalse();
            assertThat(s.reason()).isEqualTo("通道说失败");
        });
    }

    @Test
    @DisplayName("★★ 回执缺通道单号：也要落 —— 这种回执最难复现，没报文就查不动")
    void missingIdFieldIsRecorded() {
        ScriptedClient c = new ScriptedClient();
        c.reply = Map.of("something_else", "1");
        CapturingRecorder r = new CapturingRecorder();

        new TestGateway(c, r).refund(CTX, 100L, "RQ-3", "x");

        assertThat(r.sent).singleElement().satisfies(s -> {
            assertThat(s.ok()).isFalse();
            assertThat(s.reason()).contains("refund_id");
        });
    }

    @Test
    @DisplayName("★★★ 调用异常也要落 —— 超时的时候「发出去没有」是真的不知道")
    void channelExceptionIsRecorded() {
        ScriptedClient c = new ScriptedClient();
        c.boom = new ChannelClient.ChannelException("读超时", true);
        CapturingRecorder r = new CapturingRecorder();

        new TestGateway(c, r).refund(CTX, 100L, "RQ-4", "x");

        assertThat(r.sent).singleElement().satisfies(s -> {
            assertThat(s.ok()).isFalse();
            // 可重试与否要写进报文：它决定这笔是等下一轮还是转人工
            assertThat(s.reason()).contains("读超时").contains("可重试");
        });
    }
}
