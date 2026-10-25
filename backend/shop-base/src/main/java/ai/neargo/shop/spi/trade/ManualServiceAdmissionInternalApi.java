package ai.neargo.shop.spi.trade;

import ai.neargo.shop.spi.trade.CheckoutPort.OperationRef;
import ai.neargo.shop.spi.trade.CheckoutPort.CheckoutResult;
import ai.neargo.shop.spi.trade.ManualServiceAdmissionPort.PrepareCounterReq;
import ai.neargo.shop.spi.trade.ManualServiceAdmissionPort.CounterCheckoutQuote;
import ai.neargo.shop.spi.trade.ManualServiceAdmissionPort.AcceptCounterReq;
import ai.neargo.shop.spi.trade.ManualServiceAdmissionPort.CounterConfirmation;
import ai.neargo.shop.spi.trade.ManualServiceAdmissionPort.PlaceCounterReq;
import ai.neargo.shop.spi.security.InternalCall;
import ai.neargo.shop.spi.security.InternalReply;
import ai.neargo.shop.spi.trade.CheckoutPort.CheckoutQuote;
import ai.neargo.shop.spi.trade.CheckoutPort.CheckoutScope;
import ai.neargo.shop.spi.trade.CheckoutPort.PrepareCheckoutReq;
import ai.neargo.shop.spi.trade.CheckoutPort.SourceLookup;
import ai.neargo.shop.spi.trade.ManualServiceAdmissionPort.ConfirmManualReceipt;
import ai.neargo.shop.spi.trade.ManualServiceAdmissionPort.ManualAdmissionView;
import ai.neargo.shop.spi.trade.ManualServiceAdmissionPort.ManualLookup;
import ai.neargo.shop.spi.trade.ManualServiceAdmissionPort.ManualSourceFacts;

import com.fasterxml.jackson.annotation.JsonAnySetter;

import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.service.annotation.PostExchange;

/**
 * Four consumed bound operations; no machine endpoint grants consumer purchase or service start.
 */
public interface ManualServiceAdmissionInternalApi {
    String BASE = "/internal/trade/manual-service-admission/";
    String ENDPOINT = "MANUAL_SERVICE_ADMISSION";

    @PostExchange(BASE + "counter-prepare")
    InternalReply<CounterCheckoutQuote> prepareCounter(
            @RequestBody InternalCall<Scoped<PrepareCounterReq>> request);

    @PostExchange(BASE + "counter-read")
    InternalReply<CounterCheckoutQuote> readCounterQuote(
            @RequestBody InternalCall<Scoped<OperationRef>> request);

    @PostExchange(BASE + "counter-accept")
    InternalReply<CounterConfirmation> acceptCounter(
            @RequestBody InternalCall<Scoped<AcceptCounterReq>> request);

    @PostExchange(BASE + "counter-place")
    InternalReply<CheckoutResult> placeCounter(
            @RequestBody InternalCall<Scoped<PlaceCounterReq>> request);

    @PostExchange(BASE + "prepare")
    InternalReply<CheckoutQuote> prepare(
            @RequestBody InternalCall<Scoped<PrepareCheckoutReq>> request);

    @PostExchange(BASE + "confirm")
    InternalReply<ManualAdmissionView> confirm(
            @RequestBody InternalCall<Scoped<ConfirmManualReceipt>> request);

    @PostExchange(BASE + "query")
    InternalReply<ManualAdmissionView> query(
            @RequestBody InternalCall<Scoped<ManualLookup>> request);

    @PostExchange(BASE + "source")
    InternalReply<ManualSourceFacts> source(
            @RequestBody InternalCall<Scoped<SourceLookup>> request);

    /** Scope is compared to authenticated binding before the owner resolves current permissions. */
    record Scoped<T>(CheckoutScope scope, T request) {
        public Scoped {
            if (scope == null || request == null) {
                throw new IllegalArgumentException("invalid manual scope");
            }
        }

        @JsonAnySetter
        public void unknown(String field, Object value) {
            throw new IllegalArgumentException("unknown manual field");
        }

        @Override
        public String toString() {
            return "Scoped[REDACTED]";
        }
    }
}
