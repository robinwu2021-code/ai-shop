package ai.neargo.shop.paybridge;

import ai.neargo.shop.trade.order.mapper.StoreOrderMapper;

import ai.neargo.shop.trade.order.mapper.ShippingUploadMapper;

import ai.neargo.common.data.scope.DataScopeContext;
import ai.neargo.shop.fulfillment.service.LogisticsService;
import ai.neargo.shop.fulfillment.service.LogisticsService.SignedShipment;
import ai.neargo.shop.spi.trade.WxShippingPort;
import ai.neargo.shop.event.outbox.OutboxLease;
import ai.neargo.shop.event.outbox.DeliveryResult;
import ai.neargo.shop.trade.entity.OrdSubOrder;
import ai.neargo.shop.trade.entity.TrdShippingUpload;
import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 签收 → 提醒买家确认收货（TDD-物流域-完整方案 批 A）。
 *
 * <p><b>为什么要我们主动调</b>：微信把「已揽件 / 派件中 / 已签收」三条消息推给<b>买家</b>，
 * 但<b>不回调给开发者</b>。所以「这单签收了」只能靠我们自己查轨迹得出
 * （{@code logistics-trace} 作业），再调微信的确认收货提醒，让买家去点确认 ——
 * 买家确认（或微信到期自动确认）之后这笔钱才进入结算。
 *
 * <p><b>为什么放在 paybridge 而不是 fulfillment</b>：这件事要同时看三个域 ——
 * 运单签没签（履约）、这个支付单报没报过（交易台账）、商户号与单号（支付）。
 * 与 {@link WxWaybillBindService} 同一个位置、同一个理由。
 *
 * <p><b>幂等落在支付单这一层</b>：微信规定每个订单仅可调用一次，而一个支付单可能对应
 * 多张子单/运单。所以标记记在 {@code trd_shipping_upload.confirm_notified_at}（本表按
 * {@code order_no} 一行），而不是记在运单上 —— 记在运单上会让一单两运单的订单调两次，
 * 第二次必然失败，而那一次失败会耗掉「每单一次」里的那一次。
 */
@Service
public class WxConfirmReceiveService {

    private static final Logger log = LoggerFactory.getLogger(WxConfirmReceiveService.class);

    /** 微信只对「物流快递」允许提醒确认收货 */
    private static final int LOGISTICS_EXPRESS = 1;

    private final LogisticsService logistics;
    private final WxShippingPort shipping;
    private final ShippingUploadMapper uploadMapper;
    private final StoreOrderMapper subOrderMapper;

    public WxConfirmReceiveService(LogisticsService logistics, WxShippingPort shipping,
                                   ShippingUploadMapper uploadMapper,
                                   StoreOrderMapper subOrderMapper) {
        this.logistics = logistics;
        this.shipping = shipping;
        this.uploadMapper = uploadMapper;
        this.subOrderMapper = subOrderMapper;
    }

    /**
     * @param scanned  本轮扫到的已签收运单
     * @param notified 其中真的向微信发出了提醒的支付单数
     */
    public record NotifyResult(int scanned, int notified) {
    }

    /** 扫已签收的运单，给还没提醒过的支付单发一次确认收货提醒。 */
    public NotifyResult notifyPending(int limit) {
        List<SignedShipment> signed = logistics.signedShipments(limit);
        if (signed.isEmpty()) {
            return new NotifyResult(0, 0);
        }
        /*
         * 多张子单签收时间不同 → 取**最晚**那个。微信要求 received_time 晚于发货时间，
         * 而同一支付单里最后签收的那一张必然晚于任何一次发货；取最早的那张反而可能撞上
         * 「比另一张子单的发货时间还早」。
         */
        Map<String, Long> byOrder = new LinkedHashMap<>();
        for (SignedShipment s : signed) {
            String orderNo = orderNoOf(s.subOrderNo());
            if (orderNo != null) {
                byOrder.merge(orderNo, s.signedAt(), Math::max);
            }
        }
        int notified = 0;
        for (Map.Entry<String, Long> e : byOrder.entrySet()) {
            if (notifyOne(e.getKey(), e.getValue())) {
                notified++;
            }
        }
        if (notified > 0 || !byOrder.isEmpty()) {
            log.info("[wxconfirm] 扫到已签收 {} 单（{} 个支付单），发出提醒 {} 单",
                    signed.size(), byOrder.size(), notified);
        }
        return new NotifyResult(signed.size(), notified);
    }

    /** @return 真的调了微信并成功才 true。包内可见：签收事件那一路（{@link WxConfirmOnSignedConsumer}）也走这里 */
    boolean notifyOne(String orderNo, long signedAtMillis) {
        return notifyAttempt(orderNo, signedAtMillis, true).notified();
    }

    /** Unified delivery exposes rejection separately from a successful notification. */
    DeliveryResult deliverOne(String orderNo, long signedAtMillis) {
        return notifyAttempt(orderNo, signedAtMillis, false).result();
    }

    private record Attempt(boolean notified, DeliveryResult result) { }

    private Attempt notifyAttempt(String orderNo, long signedAtMillis, boolean legacy) {
        TrdShippingUpload row = DataScopeContext.executeWithoutScope(() -> uploadMapper.selectOne(
                Wrappers.<TrdShippingUpload>lambdaQuery().eq(TrdShippingUpload::getOrderNo, orderNo).last("limit 1")));
        if (row == null) {
            return new Attempt(false, DeliveryResult.retry("NOT_READY", "Shipping upload not registered"));
        }
        if (row.getConfirmNotifiedAt() != null) { return new Attempt(false, DeliveryResult.sent()); }
        if (!TrdShippingUpload.SUCCESS.equals(row.getStatus())) {
            return new Attempt(false, DeliveryResult.retry("NOT_READY", "Shipping upload incomplete"));
        }
        if (row.getLogisticsType() == null || row.getLogisticsType() != LOGISTICS_EXPRESS) {
            return new Attempt(false, DeliveryResult.sent());
        }
        OutboxLease.check();
        var result = shipping.notifyConfirmReceive(
                new WxShippingPort.ConfirmCmd(row.getOutTradeNo(), signedAtMillis / 1000L));
        if (!result.success()) {
            if (result.retryable()) {
                if (!legacy && result.code() != 40001 && result.code() != 40014 && result.code() != 42001
                        && result.code() != 45009) {
                    return new Attempt(false, DeliveryResult.unknown(Integer.toString(result.code())));
                }
                return new Attempt(false, DeliveryResult.retry("CHANNEL_RETRYABLE", Integer.toString(result.code())));
            }
            if (legacy) { markNotified(row.getId()); }
            return new Attempt(false, DeliveryResult.failed("CHANNEL_REJECTED", Integer.toString(result.code())));
        }
        markNotified(row.getId());
        return new Attempt(true, DeliveryResult.sent());
    }

    /**
     * 只写这一列：新建一个只带 id 与目标列的实体交给 {@code updateById} ——
     * MyBatis-Plus 跳过 null 字段，所以生成的 SQL 只 set 这一列，
     * 不会把查出来的整行（含乐观锁版本号）写回去，和并发的上报任务互相踩。
     */
    private void markNotified(Long id) {
        TrdShippingUpload patch = new TrdShippingUpload();
        patch.setId(id);
        patch.setConfirmNotifiedAt(System.currentTimeMillis());
        DataScopeContext.executeWithoutScope(() -> uploadMapper.updateById(patch));
    }

    private String orderNoOf(String subOrderNo) {
        OrdSubOrder s = DataScopeContext.executeWithoutScope(() -> subOrderMapper.selectOne(
                Wrappers.<OrdSubOrder>lambdaQuery()
                        .eq(OrdSubOrder::getSubOrderNo, subOrderNo).last("limit 1")));
        return s == null ? null : s.getOrderNo();
    }
}
