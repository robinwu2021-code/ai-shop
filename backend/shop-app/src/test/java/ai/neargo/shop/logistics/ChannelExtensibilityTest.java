package ai.neargo.shop.logistics;

import ai.neargo.shop.event.OutboxDispatcher;
import ai.neargo.shop.event.OutboxEventBus;
import ai.neargo.shop.logistics.capability.ChannelOutcome;
import ai.neargo.shop.logistics.capability.PushReceiver;
import ai.neargo.shop.logistics.capability.TrackingSubscriber;
import ai.neargo.shop.logistics.entity.LgsWaybill;
import ai.neargo.shop.logistics.event.WaybillRegistered;
import ai.neargo.shop.logistics.mapper.LogisticsMappers.WaybillMapper;
import ai.neargo.shop.logistics.routing.ChannelRouter;
import ai.neargo.shop.spi.logistics.TraceResult;
import ai.neargo.shop.spi.logistics.TraceStatus;
import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;
import org.springframework.http.MediaType;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.web.context.WebApplicationContext;

import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;

/**
 * 加一家物流渠道 = 加一个包、写进路由配置，<b>不动任何既有代码</b>（TDD-物流模块 AC14 / T5.8）。
 *
 * <p>这里注册一个只存在于测试里的渠道 {@code testch}（订阅 + 推送两种能力），只改配置把它排进订阅链：
 * 它要被路由选中、订阅成功后运单记在它名下、它推来的轨迹要能走通入库。
 * 哪一步需要改既有代码才能通（比如 Controller 里写死了渠道名），这里就红。
 */
@SpringBootTest(properties = {
        "shop.logistics.subscribe-enabled=true",
        "shop.logistics.routes.subscribe.by-default=testch"
})
@ActiveProfiles("test")
@Import(ChannelExtensibilityTest.TestChannel.class)
class ChannelExtensibilityTest {

    private static final String PREFIX = "SH-EXT-";

    @TestConfiguration
    static class TestChannel {

        static final List<TrackingSubscriber.SubscribeCmd> SUBSCRIBED = new CopyOnWriteArrayList<>();

        @Bean
        TrackingSubscriber testchSubscriber() {
            return new TrackingSubscriber() {
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
                    SUBSCRIBED.add(cmd);
                    return ChannelOutcome.ok("200", "ref-" + cmd.waybillNo());
                }
            };
        }

        /** 报文就是「单号|状态」 —— 真渠道的格式各家不一样，Controller 不该知道任何一种 */
        @Bean
        PushReceiver testchReceiver() {
            return new PushReceiver() {
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
                            List.of(new TraceResult.TraceNode(System.currentTimeMillis(), s, "测试渠道节点", null))),
                            false, null);
                }

                @Override
                public String ack() {
                    return "OK-testch";
                }
            };
        }
    }

    @Autowired
    private ChannelRouter router;
    @Autowired
    private WaybillMapper waybills;
    @Autowired
    private OutboxEventBus events;
    @Autowired
    private OutboxDispatcher dispatcher;
    @Autowired
    private WebApplicationContext context;

    @AfterEach
    void cleanup() {
        waybills.delete(Wrappers.<LgsWaybill>query().likeRight("shipment_no", PREFIX));
    }

    @Test
    @DisplayName("★★★ 新渠道只改配置就被路由选中、订阅记在它名下、它的推送走通入库 —— 不动任何既有代码")
    void newChannelIsRoutedAndReceivesPushes() throws Exception {
        assertThat(router.subscribers(null, "SF")).extracting(TrackingSubscriber::channel).first()
                .as("排进订阅链的新渠道没被选中").isEqualTo("testch");

        LgsWaybill w = new LgsWaybill();
        w.setShipmentNo(PREFIX + "1");
        w.setBizType(LgsWaybill.BIZ_SUB_ORDER);
        w.setBizRef("SUB-EXT-1");
        w.setCarrier("SF");
        w.setWaybillNo("EXT0001");
        w.setStatus("CREATED");
        w.setSubState(LgsWaybill.SUB_PENDING);
        waybills.insert(w);
        events.publish(new WaybillRegistered(w.getShipmentNo()));
        for (int i = 0; i < 50 && dispatcher.pendingCount() > 0; i++) {
            dispatcher.dispatchPending();
        }

        LgsWaybill subscribed = waybills.selectById(w.getId());
        assertThat(subscribed.getSubState()).isEqualTo(LgsWaybill.SUB_DONE);
        assertThat(subscribed.getSubChannel()).isEqualTo("testch");
        assertThat(TestChannel.SUBSCRIBED).extracting(TrackingSubscriber.SubscribeCmd::waybillNo).contains("EXT0001");

        MockMvc mvc = MockMvcBuilders.webAppContextSetup(context).build();
        String ack = mvc.perform(post("/callback/logistics/testch").contentType(MediaType.TEXT_PLAIN)
                        .content("EXT0001|IN_TRANSIT"))
                .andReturn().getResponse().getContentAsString();

        assertThat(ack).as("回执是渠道自己给的，Controller 原样写回").isEqualTo("OK-testch");
        assertThat(waybills.selectById(w.getId()).getStatus()).isEqualTo("IN_TRANSIT");
    }
}
