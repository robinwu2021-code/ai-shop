package ai.neargo.shop.payclient;

import ai.neargo.shop.pay.acquiring.service.MerchantPaymentQueryService.*;
import ai.neargo.shop.pay.client.PayBizInternalApi;
import ai.neargo.shop.pay.client.PayBizInternalApi.PaymentSearchReq;
import ai.neargo.shop.pay.client.PortResult;
import ai.neargo.shop.spi.pay.InternalHeaders;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.service.annotation.GetExchange;
import org.springframework.web.service.annotation.PostExchange;

/** Existing PAY read contracts only. Search uses the existing nonmutating POST query contract. */
public interface SandboxPaymentReadApi {
    @GetExchange(PayBizInternalApi.PREFIX + "/collect-status")
    PortResult<CollectStatus> status(@PathVariable String storeNo, @RequestParam String operatorNo,
            @RequestHeader(InternalHeaders.CALLER) String caller,
            @RequestHeader(InternalHeaders.CREDENTIAL) String credential);

    @PostExchange(PayBizInternalApi.PREFIX + "/payment-orders/search")
    PortResult<PaymentPage> orders(@PathVariable String storeNo, @RequestBody PaymentSearchReq query,
            @RequestHeader(InternalHeaders.CALLER) String caller,
            @RequestHeader(InternalHeaders.CREDENTIAL) String credential);

    @GetExchange(PayBizInternalApi.PREFIX + "/payment-orders/summary")
    PortResult<Summary> summary(@PathVariable String storeNo, @RequestParam String operatorNo,
            @RequestParam(required = false) String from, @RequestParam(required = false) String to,
            @RequestHeader(InternalHeaders.CALLER) String caller,
            @RequestHeader(InternalHeaders.CREDENTIAL) String credential);

    @GetExchange(PayBizInternalApi.PREFIX + "/payment-orders/lookup")
    PortResult<Lookup> lookup(@PathVariable String storeNo, @RequestParam String operatorNo,
            @RequestParam(required = false) Long amountMinor,
            @RequestParam(required = false) String within,
            @RequestParam(required = false) String tradeNoSuffix,
            @RequestHeader(InternalHeaders.CALLER) String caller,
            @RequestHeader(InternalHeaders.CREDENTIAL) String credential);

    @GetExchange(PayBizInternalApi.PREFIX + "/payment-orders/{payOrderNo}")
    PortResult<Detail> detail(@PathVariable String storeNo, @PathVariable String payOrderNo,
            @RequestParam String operatorNo,
            @RequestHeader(InternalHeaders.CALLER) String caller,
            @RequestHeader(InternalHeaders.CREDENTIAL) String credential);
}
