package ai.neargo.shop.logistics.channel.kuaidi100;

import ai.neargo.shop.logistics.capability.ChannelOutcome.Kind;
import ai.neargo.shop.logistics.capability.PushReceiver;
import ai.neargo.shop.spi.logistics.TraceStatus;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/** 快递100 订阅返回码分类与推送解析（官方文档 2026-10-09 核对）。 */
class Kuaidi100PushTest {

    @Test
    @DisplayName("★★★ 返回码分类：200 / 501 成功；500 可重试；600 / 601 / 700 等不可重试；认不出的按不可重试")
    void classify() {
        assertThat(Kuaidi100Codes.classify("200", "").kind()).isEqualTo(Kind.OK);
        assertThat(Kuaidi100Codes.classify("501", "重复订阅").kind())
                .as("重复订阅不当成功，重试一次反而记成失败").isEqualTo(Kind.OK);
        assertThat(Kuaidi100Codes.classify("500", "").kind()).isEqualTo(Kind.RETRYABLE);
        for (String c : new String[]{"502", "600", "601", "700", "701", "702"}) {
            assertThat(Kuaidi100Codes.classify(c, "").kind()).as(c).isEqualTo(Kind.FATAL);
        }
        assertThat(Kuaidi100Codes.classify("999", "?").kind()).as("盲目重试会烧额度").isEqualTo(Kind.FATAL);
    }

    @Test
    @DisplayName("★★★ 验签：upper(md5(param + salt))；签错了一个字都不解析")
    void verify() {
        Kuaidi100PushReceiver r = new Kuaidi100PushReceiver("salt-1");
        String param = "{\"status\":\"polling\",\"lastResult\":{\"message\":\"ok\",\"state\":\"0\",\"com\":\"shentong\",\"nu\":\"A1\",\"data\":[]}}";
        assertThat(r.parse(new PushReceiver.Request(Map.of("param", param, "sign", "BAD"), null)).verified()).isFalse();
        PushReceiver.Parsed ok = r.parse(new PushReceiver.Request(
                Map.of("param", param, "sign", Kuaidi100Codes.sign(param, "salt-1")), null));
        assertThat(ok.verified()).isTrue();
        assertThat(ok.waybillNo()).isEqualTo("A1");
        assertThat(ok.channelCarrierCode()).isEqualTo("shentong");
    }

    @Test
    @DisplayName("★★ abort = 渠道停止跟踪；autoCheck=1 时取纠正后的公司并带出原值")
    void abortAndCorrection() {
        Kuaidi100PushReceiver r = new Kuaidi100PushReceiver("s");
        String param = "{\"status\":\"abort\",\"message\":\"3天查询无记录\",\"autoCheck\":\"1\",\"comOld\":\"yuantong\","
                + "\"comNew\":\"shentong\",\"lastResult\":{\"message\":\"ok\",\"state\":\"5\",\"com\":\"yuantong\",\"nu\":\"B2\","
                + "\"data\":[{\"context\":\"已存放至XX驿站\",\"ftime\":\"2026-10-09 09:00:00\",\"time\":\"2026-10-09 09:00:00\","
                + "\"status\":\"投柜或驿站\",\"statusCode\":\"501\"}]}}";
        PushReceiver.Parsed p = r.parse(new PushReceiver.Request(Map.of("param", param, "sign", Kuaidi100Codes.sign(param, "s")), null));
        assertThat(p.trackingEnded()).isTrue();
        assertThat(p.channelCarrierCode()).isEqualTo("shentong");
        assertThat(p.correctedFrom()).isEqualTo("yuantong");
        assertThat(p.trace().status()).isEqualTo(TraceStatus.DELIVERING);
        assertThat(p.trace().atLocker()).as("子状态 501 → 已到驿站").isTrue();
    }

    @Test
    @DisplayName("★ 回执是快递100 要的那一句原文")
    void ack() {
        assertThat(new Kuaidi100PushReceiver("s").ack())
                .isEqualTo("{\"result\":true,\"returnCode\":\"200\",\"message\":\"成功\"}");
    }
}
