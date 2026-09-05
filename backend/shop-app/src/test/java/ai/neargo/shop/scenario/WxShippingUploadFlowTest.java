package ai.neargo.shop.scenario;

import ai.neargo.common.data.scope.DataScopeContext;
import ai.neargo.shop.paybridge.WxShippingUploadService;
import ai.neargo.shop.spi.trade.WxShippingPort;
import ai.neargo.shop.trade.entity.TrdShippingUpload;
import ai.neargo.shop.trade.mapper.TradeMappers;
import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.ActiveProfiles;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 发货信息录入的上报台账 —— <b>入队与幂等</b>这一半。
 *
 * <p>「上报一次之后落成什么状态」那一半在 {@code WxShippingOutcomeTest}（纯函数）：
 * 为它起一个带假通道的新上下文，会把测试上下文缓存挤掉重建，
 * 而重建会让 H2 的初始化脚本再跑一遍、种子撞主键 —— 实测踩过。
 *
 * <p><b>不报的后果不是「少个功能」，是这笔钱结不出来</b>，
 * 而它在用户端毫无感知、商家几天后才发现。所以这里守的是
 * 「该报的一件不少、报过的不重复报、认不出来的绝不猜」。
 */
@SpringBootTest
@ActiveProfiles("test")
class WxShippingUploadFlowTest {

    @Autowired
    private WxShippingUploadService service;
    @Autowired
    private WxShippingPort channel;
    @Autowired
    private TradeMappers.ShippingUploadMapper mapper;

    private static int seq = 0;

    private TrdShippingUpload row(String orderNo) {
        return DataScopeContext.executeWithoutScope(() -> mapper.selectOne(
                Wrappers.<TrdShippingUpload>lambdaQuery()
                        .eq(TrdShippingUpload::getOrderNo, orderNo).last("LIMIT 1")));
    }

    @Test
    @DisplayName("★★★ 测试世界里装的是桩，且它明说自己没接通 —— 否则「一直没结到钱」会被查成通道故障")
    void stubSaysItIsNotConnected() {
        assertThat(channel.enabled())
                .as("桩把 enabled 报成 true 的话，运营看不出「这个号根本没开通」")
                .isFalse();
    }

    @Test
    @DisplayName("★★★ 入队只落库不调用 —— 一次网络抖动不该让用户那个动作失败")
    void enqueueOnlyWritesTheLedger() {
        String o = "OD-SHIP-" + (++seq);
        service.enqueue(o, "PY-" + seq, 4);

        TrdShippingUpload r = row(o);
        assertThat(r).isNotNull();
        assertThat(r.getStatus()).isEqualTo(TrdShippingUpload.PENDING);
        assertThat(r.getOutTradeNo()).isEqualTo("PY-" + seq);
        assertThat(r.getLogisticsType()).isEqualTo(4);
        assertThat(r.getAttempts()).isZero();
    }

    @Test
    @DisplayName("★★★ 认不出履约方式时不落库、不上报 —— 绝不兜一个默认值")
    void unknownFulfillmentIsNotGuessed() {
        String o = "OD-SHIP-U" + (++seq);
        service.enqueue(o, "PY-U" + seq, 0);

        assertThat(row(o))
                .as("兜 3（虚拟）的话微信不会拒 —— 报上去但语义是错的，没有任何地方会说一句")
                .isNull();
    }

    @Test
    @DisplayName("★★★ 没有支付单号时不落库 —— 上报靠它定位微信那笔单")
    void missingOutTradeNoIsNotQueued() {
        String o = "OD-SHIP-N" + (++seq);
        service.enqueue(o, "  ", 1);
        assertThat(row(o)).isNull();
    }

    @Test
    @DisplayName("★★ 一笔订单一行；上报成功后重复入队不会把它改回待上报")
    void enqueueIsIdempotentAndDoesNotResurrect() {
        String o = "OD-SHIP-I" + (++seq);
        service.enqueue(o, "PY-I" + seq, 1);
        assertThat(service.upload(row(o), "五常大米 10斤装", "SF7788", "SF", "oPAYER-1")).isTrue();
        assertThat(row(o).getStatus()).isEqualTo(TrdShippingUpload.SUCCESS);
        assertThat(row(o).getUploadedAt()).isNotNull();

        service.enqueue(o, "PY-I" + seq, 1);
        assertThat(row(o).getStatus())
                .as("已成功的被改回待上报 —— 补报任务会一直重复报同一笔")
                .isEqualTo(TrdShippingUpload.SUCCESS);
    }

    @Test
    @DisplayName("★★ 待上报的能被补报任务捞到，已成功的不再出现")
    void pendingListIsWhatTheJobWillPickUp() {
        String queued = "OD-SHIP-Q" + (++seq);
        String done = "OD-SHIP-D" + (++seq);
        service.enqueue(queued, "PY-Q" + seq, 4);
        service.enqueue(done, "PY-D" + seq, 4);
        service.upload(row(done), "商品", null, null, "oX");

        assertThat(service.pending(100)).extracting(TrdShippingUpload::getOrderNo)
                .contains(queued)
                .doesNotContain(done);
    }
}
