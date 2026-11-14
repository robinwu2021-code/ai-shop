package ai.neargo.shop.spi.entitlement;

import ai.neargo.shop.spi.entitlement.ServiceWorkOrderUsePort.Binding;
import ai.neargo.shop.spi.entitlement.ServiceWorkOrderUsePort.Lookup;
import ai.neargo.shop.spi.security.InternalCall;
import ai.neargo.shop.spi.security.InternalReply;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.service.annotation.PostExchange;

/** First reservation is human-bound. Original observation cannot mint another reservation. */
public interface ServiceWorkOrderUseInternalApi {
    String ENDPOINT = "BEAUTY_ENTITLEMENT_USE";
    String RESERVE = "/internal/beauty/entitlement-uses/reserve";
    String INSPECT = "/internal/beauty/entitlement-uses/inspect";
    String RECONCILE = "/internal/beauty/entitlement-uses/reconcile";
    String FACTS = "/internal/core/work-order-entitlement-uses/facts";

    @PostExchange(RESERVE)
    InternalReply<Binding> reserve(@RequestBody InternalCall<Lookup> call);
    @PostExchange(INSPECT)
    Binding inspect(@RequestBody Lookup lookup);
    @PostExchange(RECONCILE)
    Binding reconcile(@RequestBody Lookup lookup);
}
