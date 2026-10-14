package ai.neargo.shop.logistics.routing;

import ai.neargo.shop.event.SysOutbox;
import ai.neargo.shop.logistics.api.callback.LogisticsCallbackController;
import ai.neargo.shop.logistics.capability.ChannelOutcome;
import ai.neargo.shop.logistics.capability.PushReceiver;
import ai.neargo.shop.logistics.capability.TrackingSubscriber;
import ai.neargo.shop.logistics.config.LogisticsProperties;
import ai.neargo.shop.logistics.domain.PhoneCipher;
import ai.neargo.shop.logistics.entity.LgsWaybill;
import ai.neargo.shop.logistics.mapper.LogisticsMappers.WaybillMapper;
import ai.neargo.shop.logistics.push.PushIngestion;
import ai.neargo.shop.logistics.registration.SubscribeExecutor;
import ai.neargo.shop.spi.logistics.ShipmentSourcePort;
import ai.neargo.shop.spi.logistics.TraceResult;
import ai.neargo.shop.spi.logistics.TraceStatus;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import tools.jackson.databind.ObjectMapper;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.atLeastOnce;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * 加一家物流渠道 = 加一个实现、写进路由配置，<b>不动任何既有代码</b>（TDD-物流模块 AC14 / T5.8）。
 *
 * <p>注册一个只存在于测试里的渠道 {@code testch}（订阅 + 推送两种能力），只改配置把它排进订阅链：
 * 它要被路由选中、订阅成功后运单记在它名下、它推来的报文要经回调入口交到入库那一层。
 * 哪一步需要改既有代码才能通（比如回调 Controller 里写死了渠道名），这里就红。
 *
 * <p><b>不起 Spring 上下文</b>：起一个带自定义属性的上下文会另建一个 context，测试库脚本对共享 H2 重跑一遍、
 * 撞唯一键，全量里必红（2026-10-09 第一版就是这样：单独跑绿、全量红）。这里手工装配真的路由、执行器与回调入口。
 */
class ChannelExtensibilityTest {

    private final List<TrackingSubscriber.SubscribeCmd> subscribed = new ArrayList<>();

    private final TrackingSubscriber testchSubscriber = new TrackingSubscriber() {
        @Override
        public String channel() {
            return "testch";
        }

        @Override
        public boolean coversAllCarriers() {
            return true;
        }

        @Override
        public ChannelOutcome subscribe(SubscribeCmd cmd) {
            subscribed.add(cmd);
            return ChannelOutcome.ok("200", "ref-" + cmd.waybillNo());
        }
    };

    /** 报文就是「单号|状态」 —— 真渠道的格式各家不一样，回调入口不该知道任何一种 */
    private final PushReceiver testchReceiver = new PushReceiver() {
        @Override
        public String channel() {
            return "testch";
        }

        @Override
        public boolean coversAllCarriers() {
            return true;
        }

        @Override
        public Parsed parse(Request request) {
            String[] p = request.body().split("\\|");
            TraceStatus s = TraceStatus.valueOf(p[1]);
            return new Parsed(true, null, p[0], new TraceResult(p[0], null, s, "testch",
                    List.of(new TraceResult.TraceNode(1_000L, s, "测试渠道节点", null))), false, null);
        }

        @Override
        public String ack() {
            return "OK-testch";
        }
    };

    private ChannelRouter router(LogisticsProperties props) {
        return new ChannelRouter(props, mock(CarrierCodeBook.class), List.of(testchSubscriber),
                List.of(testchReceiver), List.of());
    }

    private static LogisticsProperties onlyConfigChanged() {
        LogisticsProperties props = new LogisticsProperties();
        props.setSubscribeEnabled(true);
        props.getRoutes().getSubscribe().setByDefault(List.of("testch"));
        return props;
    }

    @Test
    @DisplayName("★★★ 新渠道只改配置就被路由选中、订阅记在它名下、它的推送经回调入口交到入库 —— 不动任何既有代码")
    void newChannelIsRoutedAndReceivesPushes() {
        LogisticsProperties props = onlyConfigChanged();
        ChannelRouter router = router(props);

        assertThat(router.subscribers(null, "SF")).extracting(TrackingSubscriber::channel)
                .as("排进订阅链的新渠道没被选中").containsExactly("testch");

        // 订阅：真的执行器 + 真的路由
        WaybillMapper waybills = mock(WaybillMapper.class);
        LgsWaybill w = new LgsWaybill();
        w.setId(1L);
        w.setShipmentNo("SH-EXT-1");
        w.setBizRef("SUB-EXT-1");
        w.setCarrier("SF");
        w.setWaybillNo("EXT0001");
        w.setStatus("CREATED");
        w.setSubState(LgsWaybill.SUB_PENDING);
        w.setSubAttempts(0);
        when(waybills.selectOne(any())).thenReturn(w);
        ShipmentSourcePort source = mock(ShipmentSourcePort.class);
        when(source.sourceOf(anyString())).thenReturn(Optional.empty());
        LogisticsProperties keyed = new LogisticsProperties();
        keyed.setPhoneKey("k");
        SubscribeExecutor executor = new SubscribeExecutor(waybills, source, router, new PhoneCipher(keyed), props,
                new ObjectMapper());
        SysOutbox event = new SysOutbox();
        event.setPayload("{\"shipmentNo\":\"SH-EXT-1\"}");
        executor.consume(event);

        assertThat(subscribed).extracting(TrackingSubscriber.SubscribeCmd::waybillNo).containsExactly("EXT0001");
        ArgumentCaptor<LgsWaybill> patch = ArgumentCaptor.forClass(LgsWaybill.class);
        verify(waybills, atLeastOnce()).updateById(patch.capture());
        assertThat(patch.getAllValues()).anySatisfy(p -> {
            assertThat(p.getSubState()).isEqualTo(LgsWaybill.SUB_DONE);
            assertThat(p.getSubChannel()).isEqualTo("testch");
        });

        // 推送：真的回调入口 + 真的路由；入库那一层只看它收到了什么
        PushIngestion ingestion = mock(PushIngestion.class);
        String ack = new LogisticsCallbackController(router, ingestion).push("testch", Map.of(), "EXT0001|IN_TRANSIT");

        assertThat(ack).as("回执是渠道自己给的，回调入口原样写回").isEqualTo("OK-testch");
        ArgumentCaptor<PushReceiver.Parsed> parsed = ArgumentCaptor.forClass(PushReceiver.Parsed.class);
        verify(ingestion).ingest(eq("testch"), parsed.capture(), anyString());
        assertThat(parsed.getValue().waybillNo()).isEqualTo("EXT0001");
        assertThat(parsed.getValue().trace().status()).isEqualTo(TraceStatus.IN_TRANSIT);
    }
}
