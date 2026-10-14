package ai.neargo.shop.logistics.port;

import ai.neargo.shop.logistics.capability.StatusProbe;
import ai.neargo.shop.logistics.config.LogisticsProperties;
import ai.neargo.shop.logistics.domain.PhoneCipher;
import ai.neargo.shop.logistics.domain.WaybillProgress;
import ai.neargo.shop.logistics.domain.WaybillStatus;
import ai.neargo.shop.logistics.entity.LgsWaybill;
import ai.neargo.shop.logistics.mapper.LogisticsMappers.WaybillMapper;
import ai.neargo.shop.logistics.mapper.LogisticsMappers.WaybillNodeMapper;
import ai.neargo.shop.logistics.routing.ChannelRouter;
import ai.neargo.shop.spi.logistics.LogisticsPort.TrackQuery;
import ai.neargo.shop.spi.logistics.LogisticsPort.TrackView;
import ai.neargo.shop.spi.logistics.TraceResult;
import ai.neargo.shop.spi.logistics.TraceStatus;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.atomic.AtomicLong;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/** 物流页读取：10 分钟读缓存、SELF 永不问微信、问失败照样返回（TDD-物流模块 AC6）。 */
class LogisticsPortImplTest {

    private final WaybillMapper waybills = mock(WaybillMapper.class);
    private final WaybillNodeMapper nodes = mock(WaybillNodeMapper.class);
    private final ChannelRouter router = mock(ChannelRouter.class);
    private final WaybillProgress progress = mock(WaybillProgress.class);
    private final LogisticsProperties props = new LogisticsProperties();
    private final AtomicLong now = new AtomicLong(1_000_000_000L);
    private final List<String> probes = new ArrayList<>();

    private LgsWaybill wb(String profile, String status) {
        LgsWaybill w = new LgsWaybill();
        w.setId(1L);
        w.setShipmentNo("SH1");
        w.setBizRef("SUB1");
        w.setCarrier("STO");
        w.setWaybillNo("773");
        w.setProfile(profile);
        w.setStatus(status);
        w.setDisplayToken("tk");
        when(waybills.selectOne(any())).thenReturn(w);
        when(nodes.selectList(any())).thenReturn(List.of());
        return w;
    }

    private void wxProbe(RuntimeException fail) {
        StatusProbe p = new StatusProbe() {
            public String channel() {
                return "wx";
            }

            public Optional<TraceResult> probe(ProbeCmd cmd) {
                probes.add(cmd.waybillToken());
                if (fail != null) {
                    throw fail;
                }
                return Optional.of(new TraceResult("773", "STO", TraceStatus.IN_TRANSIT, "wx", List.of()));
            }
        };
        when(router.probes(any(), any())).thenReturn(List.of(p));
        when(router.codeOf(any(), any())).thenReturn(Optional.of("STO"));
        when(progress.apply(any(), any(), any(), any(), any(), org.mockito.ArgumentMatchers.anyLong()))
                .thenReturn(WaybillStatus.advance("PICKED_UP", TraceStatus.IN_TRANSIT));
    }

    private LogisticsPortImpl port() {
        return new LogisticsPortImpl(waybills, nodes, router, progress, new PhoneCipher(props), props, now::get);
    }

    @Test
    @DisplayName("★★★ 10 分钟内第二次打开不再问微信；过了 10 分钟再问")
    void tenMinuteReadCache() {
        LgsWaybill w = wb(LgsWaybill.PROFILE_WX, "PICKED_UP");
        wxProbe(null);
        LogisticsPortImpl p = port();
        p.track(TrackQuery.subOrder("SUB1", "MP", false));
        assertThat(probes).hasSize(1);
        w.setWxStatusCheckedAt(now.get());
        now.addAndGet(9 * 60_000L);
        p.track(TrackQuery.subOrder("SUB1", "MP", false));
        assertThat(probes).as("10 分钟内重复打开，不该再耗微信的次数（每用户每天约 100 次）").hasSize(1);
        now.addAndGet(2 * 60_000L);
        p.track(TrackQuery.subOrder("SUB1", "MP", false));
        assertThat(probes).hasSize(2);
    }

    @Test
    @DisplayName("★★★ 线下付款单（SELF）永不问微信；已签收的不问；APP 不走插件")
    void noProbeCases() {
        wb(LgsWaybill.PROFILE_SELF, "PICKED_UP");
        wxProbe(null);
        TrackView v = port().track(TrackQuery.subOrder("SUB1", "MP", false)).orElseThrow();
        assertThat(probes).isEmpty();
        assertThat(v.displayMode()).isEqualTo("self-map");

        wb(LgsWaybill.PROFILE_WX, WaybillStatus.DELIVERED);
        port().track(TrackQuery.subOrder("SUB1", "MP", false));
        assertThat(probes).as("终态不问").isEmpty();

        wb(LgsWaybill.PROFILE_WX, "PICKED_UP");
        TrackView app = port().track(TrackQuery.subOrder("SUB1", "APP", false)).orElseThrow();
        assertThat(app.displayMode()).as("App 里没有微信插件").isEqualTo("self-map");
        assertThat(app.displayToken()).isNull();
    }

    @Test
    @DisplayName("★★ 问微信失败：照样返回库里的，不让买家等也不报错")
    void probeFailureStillReturns() {
        wb(LgsWaybill.PROFILE_WX, "PICKED_UP");
        wxProbe(new IllegalStateException("timeout"));
        TrackView v = port().track(TrackQuery.subOrder("SUB1", "MP", false)).orElseThrow();
        assertThat(v.displayMode()).isEqualTo("wx-plugin");
        assertThat(v.displayToken()).isEqualTo("tk");
    }
}
