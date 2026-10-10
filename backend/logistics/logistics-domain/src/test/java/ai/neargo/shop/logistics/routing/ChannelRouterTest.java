package ai.neargo.shop.logistics.routing;

import ai.neargo.shop.logistics.capability.ChannelOutcome;
import ai.neargo.shop.logistics.capability.PushReceiver;
import ai.neargo.shop.logistics.capability.StatusProbe;
import ai.neargo.shop.logistics.capability.TrackingSubscriber;
import ai.neargo.shop.logistics.config.LogisticsProperties;
import ai.neargo.shop.logistics.entity.LgsCarrierCode;
import ai.neargo.shop.logistics.mapper.LogisticsMappers.CarrierCodeMapper;
import ai.neargo.shop.spi.logistics.TraceResult;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * 渠道路由（TDD-物流模块 AC13）。
 *
 * <p>编码表照 V387 的种子摆：快递100 覆盖全部、圆通直连只有 YTO 一行。
 */
class ChannelRouterTest {

    private final CarrierCodeMapper mapper = mock(CarrierCodeMapper.class);

    {
        when(mapper.selectList(any())).thenReturn(List.of(
                row("YTO", "kuaidi100", "yuantong"), row("STO", "kuaidi100", "shentong"),
                row("YTO", "yto", "YTO"), row("STO", "wx", "STO"), row("YTO", "wx", "YTO")));
    }

    private static LgsCarrierCode row(String carrier, String channel, String code) {
        LgsCarrierCode r = new LgsCarrierCode();
        r.setCarrier(carrier);
        r.setChannel(channel);
        r.setCode(code);
        return r;
    }

    record Sub(String channel, boolean available) implements TrackingSubscriber {
        public ChannelOutcome subscribe(SubscribeCmd cmd) {
            return ChannelOutcome.ok("200", null);
        }
    }

    record Recv(String channel, boolean available) implements PushReceiver {
        public Parsed parse(Request request) {
            return Parsed.rejected();
        }

        public String ack() {
            return "ok";
        }
    }

    record Probe(String channel, boolean available) implements StatusProbe {
        public Optional<TraceResult> probe(ProbeCmd cmd) {
            return Optional.empty();
        }
    }

    private ChannelRouter router(LogisticsProperties p, boolean ytoPushReady) {
        return new ChannelRouter(p, new CarrierCodeBook(mapper),
                List.of(new Sub("yto", true), new Sub("kuaidi100", true)),
                List.of(new Recv("yto", ytoPushReady), new Recv("kuaidi100", true)),
                List.of(new Probe("wx", true), new Probe("kuaidi100", true)));
    }

    private static List<String> names(List<? extends TrackingSubscriber> l) {
        return l.stream().map(TrackingSubscriber::channel).toList();
    }

    @Test
    @DisplayName("★★★ 默认链：圆通单先圆通直连、再快递100")
    void ytoWaybillPrefersYto() {
        assertThat(names(router(new LogisticsProperties(), true).subscribers(null, "YTO")))
                .containsExactly("yto", "kuaidi100");
    }

    @Test
    @DisplayName("★★★ 圆通直连不覆盖申通：申通单直接走快递100")
    void otherCarrierSkipsYto() {
        assertThat(names(router(new LogisticsProperties(), true).subscribers(null, "STO")))
                .containsExactly("kuaidi100");
    }

    @Test
    @DisplayName("★★★ 推送接收不可用 → 订阅也算不可用：订了收不到推送，运单会静默沉默")
    void subscribeUnavailableWithoutPush() {
        assertThat(names(router(new LogisticsProperties(), false).subscribers(null, "YTO")))
                .as("圆通推送没调通时还把圆通单订到圆通，订阅返回成功、此后一条推送都收不到")
                .containsExactly("kuaidi100");
    }

    @Test
    @DisplayName("★★ 门店路由压过承运商路由与默认")
    void storeRouteOverridesCarrier() {
        LogisticsProperties p = new LogisticsProperties();
        p.getRoutes().getSubscribe().setByCarrier(Map.of("YTO", List.of("yto")));
        p.getRoutes().getSubscribe().setByStore(Map.of("ST-1", List.of("kuaidi100")));
        ChannelRouter r = router(p, true);
        assertThat(names(r.subscribers("ST-1", "YTO"))).containsExactly("kuaidi100");
        assertThat(names(r.subscribers("ST-2", "YTO"))).containsExactly("yto");
    }

    @Test
    @DisplayName("★★ 链尾不自动补 stub：都不可用就是空，让上层标 FATAL")
    void noImplicitStubForSubscribe() {
        LogisticsProperties p = new LogisticsProperties();
        p.getChannels().put("kuaidi100", disabled());
        assertThat(router(p, false).subscribers(null, "YTO")).isEmpty();
    }

    @Test
    @DisplayName("★★ enabled: false 是紧急关闭开关")
    void disabledChannelSkipped() {
        LogisticsProperties p = new LogisticsProperties();
        p.getChannels().put("yto", disabled());
        assertThat(names(router(p, true).subscribers(null, "YTO"))).containsExactly("kuaidi100");
    }

    @Test
    @DisplayName("★ 探测链默认只有微信")
    void probeDefaultsToWx() {
        assertThat(router(new LogisticsProperties(), true).probes(null, "STO"))
                .extracting(StatusProbe::channel).containsExactly("wx");
    }

    @Test
    @DisplayName("★★ 渠道编码双向转换，反查不分大小写（快递100 推回来是小写）")
    void codeRoundTrip() {
        ChannelRouter r = router(new LogisticsProperties(), true);
        assertThat(r.codeOf("STO", new Sub("kuaidi100", true))).contains("shentong");
        assertThat(r.carrierOf("kuaidi100", "SHENTONG")).contains("STO");
        assertThat(r.carrierOf("wx", "STO")).contains("STO");
        assertThat(r.carrierOf("kuaidi100", "nope")).isEmpty();
    }

    private static LogisticsProperties.Toggle disabled() {
        LogisticsProperties.Toggle t = new LogisticsProperties.Toggle();
        t.setEnabled(false);
        return t;
    }
}
