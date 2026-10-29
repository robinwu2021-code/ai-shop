package ai.neargo.shop.pay.service.impl;

import ai.neargo.shop.common.PayChannels;
import ai.neargo.shop.pay.channel.PayGateway;
import ai.neargo.shop.pay.channel.api.AccessMode;
import ai.neargo.shop.pay.channel.context.ChannelRoute;
import ai.neargo.shop.pay.channel.legacy.LegacyChannelBridge;
import ai.neargo.shop.pay.service.OrdinaryPaymentInitiationService;
import ai.neargo.shop.pay.service.PaymentLedgerService;
import ai.neargo.shop.spi.settle.SettlePort.PaymentOpen;
import ai.neargo.shop.spi.settle.SettlePort.PayInitResult;
import ai.neargo.shop.spi.settle.SettlePort.PaymentSettled;
import ai.neargo.shop.pay.client.PayOrdinaryPaymentRequests;
import ai.neargo.shop.pay.client.PayOrdinaryPaymentRequests.PayerIdentity;
import java.util.Objects;
import java.util.Map;
import org.springframework.stereotype.Service;

/** Shared ordinary initiation; settlement generation and refund projections remain app orchestration. */
@Service
public class OrdinaryPaymentInitiationServiceImpl implements OrdinaryPaymentInitiationService {
    private final PaymentLedgerService ledger;
    /** 渠道层（TDD-支付服务-I2 S4 管线；W2 S5 去掉路由表与「直调原网关」的旁路） */
    private final LegacyChannelBridge channels;

    public OrdinaryPaymentInitiationServiceImpl(PaymentLedgerService ledger, LegacyChannelBridge channels) {
        this.ledger = ledger;
        this.channels = channels;
    }

    @Override
    public String prepare(PaymentOpen request, PayerIdentity payer) {
        if (request == null || payer == null
                || !Objects.equals(request.userNo(), payer.userNo())
                || !Objects.equals(request.orderNo(), payer.orderNo())
                || !Objects.equals(PayOrdinaryPaymentRequests.hash(request), payer.requestHash())) {
            throw new IllegalArgumentException("PAY_PAYER_CONTEXT_CONFLICT");
        }
        if ("WECHAT".equals(request.payChannel())
                && (payer.openId() == null || payer.openId().isBlank()
                    || payer.appId() == null || payer.appId().isBlank())) {
            throw new IllegalArgumentException("PAY_PAYER_IDENTITY_REQUIRED");
        }
        ChannelRoute route = channelRoute(request);
        if (route != null && !channels.supportsRoutedAccount(route)) {
            throw new IllegalArgumentException("ACQUIRING_ACCOUNT_UNVERIFIED");
        }
        String outTradeNo = ledger.open(request);
        var frozen = ledger.paymentRequest(outTradeNo).orElseThrow(
                () -> new IllegalArgumentException("PAY_REQUEST_SNAPSHOT_REQUIRED"));
        if ("WECHAT".equals(frozen.payChannel())) {
            ledger.freezePayer(outTradeNo, frozen.userNo(), payer.openId(), payer.appId());
        }
        return outTradeNo;
    }

    @Override
    public PayInitResult initiate(PaymentOpen request, PayerIdentity payer) {
        String outTradeNo = prepare(request, payer);
        var frozen = ledger.paymentRequest(outTradeNo).orElseThrow(
                () -> new IllegalArgumentException("PAY_REQUEST_SNAPSHOT_REQUIRED"));
        if (!channels.supports(frozen.payChannel())) {
            return new PayInitResult(false, outTradeNo, frozen.payChannel(),
                    Map.of(), "支付通道未接入：" + frozen.payChannel());
        }
        String payerId = payer.openId();
        var route = frozen.acquiringRoute();
        String subMchId = route == null ? DirectAccounts.of(frozen).map(r -> r.subMerchantId()).orElse(null)
                : route.subMerchantId();
        // A thrown or ambiguous response leaves the same original intent pending for query/recovery.
        var command = new PayGateway.PrepayCommand(outTradeNo, frozen.amountMinor(),
                frozen.currency(), frozen.subject(), "JSAPI", subMchId, payerId);
        var response = route == null ? channels.prepay(frozen.payChannel(), command)
                : channels.prepayRouted(channelRoute(frozen), command);
        if (response == null) {
            throw new IllegalStateException("PAY_CHANNEL_RESPONSE_MISSING");
        }
        if (!response.success()) {
            // A local rejection on a reused intent cannot disprove an earlier uncertain dispatch.
            return new PayInitResult(false, outTradeNo, frozen.payChannel(), Map.of(), response.message());
        }
        ledger.recordPayer(outTradeNo, payerId, "WECHAT".equals(frozen.payChannel()) ? payer.appId() : null);
        if (PayChannels.FREE.equals(frozen.payChannel())) {
            ledger.settle(new PaymentSettled(outTradeNo, frozen.payChannel(), response.tradeNo(),
                    0L, System.currentTimeMillis(), frozen.currency()));
        }
        return new PayInitResult(true, outTradeNo, frozen.payChannel(), response.params(), null);
    }
    private ChannelRoute channelRoute(PaymentOpen request) {
        var route = request.acquiringRoute();
        return route == null ? null : new ChannelRoute(route.payChannel(), AccessMode.valueOf(route.accessMode()),
                route.market(), route.currency(), route.payMerchantId(), route.payMerchantNo(), route.subMerchantId(),
                route.keyVersion());
    }

}
