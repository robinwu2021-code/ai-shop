package ai.neargo.shop.paybridge;

import ai.neargo.common.data.scope.DataScopeContext;
import ai.neargo.shop.fulfillment.service.LogisticsService;
import ai.neargo.shop.fulfillment.service.LogisticsService.WxBindTarget;
import ai.neargo.shop.pay.service.PaymentLedgerService;
import ai.neargo.shop.spi.logistics.TraceDisplay.DisplayPayload;
import ai.neargo.shop.spi.logistics.TraceDisplay.ShipmentCtx;
import ai.neargo.shop.spi.logistics.TraceDisplay.Surface;
import ai.neargo.shop.spi.logistics.TraceDisplayPort;
import ai.neargo.shop.trade.entity.OrdItem;
import ai.neargo.shop.trade.entity.OrdOrder;
import ai.neargo.shop.trade.entity.OrdSubOrder;
import ai.neargo.shop.trade.mapper.TradeMappers;
import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import java.util.List;
import java.util.Optional;

/**
 * 微信物流插件的 {@code waybill_token} 绑定（TDD-物流轨迹多渠道 §2.3）。
 *
 * <p>小程序订单要打开微信全屏物流页，得先拿 {@code waybill_token}。换 token 要三样只有 shop-app
 * 这层同时拿得到的东西：付款人 openid（支付域台账）、微信支付交易号 {@code trans_id}
 * （{@code ord_order.pay_trade_no}）、商品名/图（订单项）。所以绑定触发放这里，
 * 换完把展示渠道写回 fulfillment（{@link LogisticsService#applyWxDisplay}）。
 *
 * <p><b>为什么要预先备好落库、而不是详情页现换</b>：微信 {@code trace_waybill} 有调用次数上限
 * （{@code 9300513}）。买家反复下拉刷新不该打穿它 —— token 由本服务定时预备、落在运单上，
 * 读路径只读不换（{@link ai.neargo.shop.fulfillment.port.ShipmentTraceQueryPortImpl}）。
 *
 * <p><b>换不到就留空</b>：缺 openid/trans_id（线下付款、非微信单）、或微信还没这条运单
 * （刚发货 {@code 9300559}）时，{@link TraceDisplayPort#decide} 落到自建渠道，这里不置 wx-plugin，
 * 读路径自然显示自建地图。下一轮再试（微信那边有了运单就能换上）。
 */
@Service
public class WxWaybillBindService {

    private static final Logger log = LoggerFactory.getLogger(WxWaybillBindService.class);

    private final LogisticsService logistics;
    private final TraceDisplayPort displayPort;
    private final PaymentLedgerService ledger;
    private final TradeMappers.SubOrderMapper subOrderMapper;
    private final TradeMappers.OrderMapper orderMapper;
    private final TradeMappers.OrderItemMapper itemMapper;

    public WxWaybillBindService(LogisticsService logistics, TraceDisplayPort displayPort,
                                PaymentLedgerService ledger,
                                TradeMappers.SubOrderMapper subOrderMapper,
                                TradeMappers.OrderMapper orderMapper,
                                TradeMappers.OrderItemMapper itemMapper) {
        this.logistics = logistics;
        this.displayPort = displayPort;
        this.ledger = ledger;
        this.subOrderMapper = subOrderMapper;
        this.orderMapper = orderMapper;
        this.itemMapper = itemMapper;
    }

    /** @param attempted 本轮尝试备的单数 @param prepared 其中真换到微信 token 的单数 */
    public record BindResult(int attempted, int prepared) {
    }

    /**
     * 给待备的在途运单备微信展示载荷。
     */
    public BindResult bindPending(int limit) {
        List<WxBindTarget> targets = logistics.wxBindTargets(limit);
        int prepared = 0;
        for (WxBindTarget t : targets) {
            String orderNo = orderNoOf(t.subOrderNo());
            String openid = orderNo == null ? null : ledger.paidPayment(orderNo)
                    .map(PaymentLedgerService.PaidPayment::payerOpenid).orElse(null);
            String transId = orderNo == null ? null : payTradeNoOf(orderNo);
            OrdItem item = firstItem(t.subOrderNo());
            ShipmentCtx ctx = new ShipmentCtx(
                    t.shipmentNo(), t.carrier(), t.waybillNo(), null,
                    openid, transId,
                    item == null ? null : item.getTitle(),
                    item == null ? null : item.getCover(),
                    orderNo == null ? null : "/pages/order/index?orderNo=" + orderNo,
                    null, null,
                    // 申通/中通等运力换 token 必填（微信 receiver_phone），缺了回 9300561
                    receiverPhoneOf(t.subOrderNo()));
            Optional<DisplayPayload> p = displayPort.decide(Surface.MP, ctx);
            if (p.isPresent() && p.get().persist() && notBlank(p.get().token())) {
                logistics.applyWxDisplay(t.shipmentNo(), p.get().channel(), p.get().token(), null);
                prepared++;
            } else {
                // 没换到：记原因给运营看（缺 openid/transId 时 lastFailReason 为 null，
                // 只占位 displayPreparedAt 止住 TTL 内重复调微信）。渠道留空=读路径落自建
                logistics.applyWxDisplay(t.shipmentNo(), null, null, displayPort.lastFailReason());
            }
        }
        if (!targets.isEmpty()) {
            log.info("[wx-bind] 待备 {} 单，换到 token {} 单", targets.size(), prepared);
        }
        return new BindResult(targets.size(), prepared);
    }

    private String orderNoOf(String subOrderNo) {
        OrdSubOrder s = DataScopeContext.executeWithoutScope(() ->
                subOrderMapper.selectOne(Wrappers.<OrdSubOrder>lambdaQuery()
                        .eq(OrdSubOrder::getSubOrderNo, subOrderNo).last("limit 1")));
        return s == null ? null : s.getOrderNo();
    }

    /** 收件人手机号：微信 trace_waybill 对申通/中通等运力必填（receiver_phone）。自提单无收件人为空 */
    private String receiverPhoneOf(String subOrderNo) {
        OrdSubOrder s = DataScopeContext.executeWithoutScope(() ->
                subOrderMapper.selectOne(Wrappers.<OrdSubOrder>lambdaQuery()
                        .eq(OrdSubOrder::getSubOrderNo, subOrderNo).last("limit 1")));
        return s == null ? null : s.getReceiverPhone();
    }

    private String payTradeNoOf(String orderNo) {
        OrdOrder o = DataScopeContext.executeWithoutScope(() ->
                orderMapper.selectOne(Wrappers.<OrdOrder>lambdaQuery()
                        .eq(OrdOrder::getOrderNo, orderNo).last("limit 1")));
        return o == null ? null : o.getPayTradeNo();
    }

    private OrdItem firstItem(String subOrderNo) {
        return DataScopeContext.executeWithoutScope(() ->
                itemMapper.selectOne(Wrappers.<OrdItem>lambdaQuery()
                        .eq(OrdItem::getSubOrderNo, subOrderNo).last("limit 1")));
    }

    private static boolean notBlank(String s) {
        return s != null && !s.isBlank();
    }
}
