package ai.neargo.shop.logistics.compensation;

import ai.neargo.shop.event.OutboxEventBus;
import ai.neargo.shop.event.SysOutboxMapper;
import ai.neargo.shop.logistics.capability.StatusProbe;
import ai.neargo.shop.logistics.config.LogisticsProperties;
import ai.neargo.shop.logistics.entity.LgsWaybill;
import ai.neargo.shop.logistics.mapper.LogisticsMappers.WaybillMapper;
import ai.neargo.shop.logistics.probe.WaybillProber;
import ai.neargo.shop.logistics.routing.ChannelRouter;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/** 推送沉默的单怎么问、问不了的怎么记（TDD-物流模块 M8）。选哪些单由 {@code CompensationSelectionTest} 对真库测。 */
class CompensationSweeperTest {

    private final WaybillMapper waybills = mock(WaybillMapper.class);
    private final ChannelRouter router = mock(ChannelRouter.class);
    private final WaybillProber prober = mock(WaybillProber.class);
    private final LogisticsProperties props = new LogisticsProperties();
    private final StatusProbe wx = probe("wx");
    private final StatusProbe kuaidi100 = probe("kuaidi100");

    private final CompensationSweeper sweeper = new CompensationSweeper(waybills, mock(OutboxEventBus.class), props,
            router, prober, mock(SysOutboxMapper.class),
            new TransactionTemplate(mock(PlatformTransactionManager.class)), () -> 1_000_000_000L);

    private static StatusProbe probe(String channel) {
        StatusProbe p = mock(StatusProbe.class);
        when(p.channel()).thenReturn(channel);
        return p;
    }

    private static LgsWaybill silent(String no, String profile, String token) {
        LgsWaybill w = new LgsWaybill();
        w.setShipmentNo(no);
        w.setProfile(profile);
        w.setDisplayToken(token);
        w.setCarrier("SF");
        return w;
    }

    @Test
    @DisplayName("★★★ 微信支付单有 token → 问微信；线下单、没换到 token 的 → 记「无法探测」，不去问")
    void onlyWxWithTokenIsProbed() {
        LgsWaybill ok = silent("S1", LgsWaybill.PROFILE_WX, "tk1");
        LgsWaybill self = silent("S2", LgsWaybill.PROFILE_SELF, null);
        LgsWaybill noToken = silent("S3", LgsWaybill.PROFILE_WX, null);
        when(waybills.selectList(any())).thenReturn(List.of(ok, self, noToken));
        when(router.probes(any(), any())).thenReturn(List.of(wx));
        when(prober.probe(eq(ok), eq(wx), anyLong())).thenReturn(new WaybillProber.Outcome(true, true));

        CompensationSweeper.Result r = sweeper.sweep(200);

        assertThat(r.scanned()).isEqualTo(3);
        assertThat(r.answered()).isEqualTo(1);
        assertThat(r.advanced()).isEqualTo(1);
        assertThat(r.unprobeable()).as("问不了的要数出来 —— 长期不为 0 说明 token 没换上").isEqualTo(2);
        verify(prober, never()).probe(eq(self), any(), anyLong());
        verify(prober, never()).probe(eq(noToken), any(), anyLong());
    }

    @Test
    @DisplayName("★★★ 快递100 在 probe-surfaces 里没放行作业 → 作业不用它问（短期额度只够订阅）")
    void kuaidi100IsNotUsedUnlessAllowedForJob() {
        LgsWaybill self = silent("S4", LgsWaybill.PROFILE_SELF, null);
        when(waybills.selectList(any())).thenReturn(List.of(self));
        when(router.probes(any(), any())).thenReturn(List.of(wx, kuaidi100));
        props.setProbeSurfaces(Map.of("kuaidi100", List.of("BIZ")));

        assertThat(sweeper.sweep(200).unprobeable()).isEqualTo(1);
        verify(prober, never()).probe(any(), any(), anyLong());

        props.setProbeSurfaces(Map.of("kuaidi100", List.of("JOB")));
        when(prober.probe(eq(self), eq(kuaidi100), anyLong())).thenReturn(new WaybillProber.Outcome(true, false));
        CompensationSweeper.Result r = sweeper.sweep(200);
        assertThat(r.answered()).as("配了 JOB 才用").isEqualTo(1);
        assertThat(r.advanced()).isZero();
    }

    @Test
    @DisplayName("★★ 渠道没答上（超时、报错）→ 不算探测成功，也不拖垮这一轮")
    void unansweredIsCountedNotThrown() {
        LgsWaybill a = silent("S5", LgsWaybill.PROFILE_WX, "tk5");
        LgsWaybill b = silent("S6", LgsWaybill.PROFILE_WX, "tk6");
        when(waybills.selectList(any())).thenReturn(List.of(a, b));
        when(router.probes(any(), any())).thenReturn(List.of(wx));
        when(prober.probe(eq(a), eq(wx), anyLong())).thenReturn(new WaybillProber.Outcome(false, false));
        when(prober.probe(eq(b), eq(wx), anyLong())).thenReturn(new WaybillProber.Outcome(true, false));

        CompensationSweeper.Result r = sweeper.sweep(200);

        assertThat(r.answered()).isEqualTo(1);
        assertThat(r.detail()).contains("扫描 2").contains("探测成功 1").contains("推进 0").contains("无法探测 0");
    }
}
