package ai.neargo.shop.payclient.port;

import ai.neargo.shop.spi.storedvalue.StoredValueWallets;

import ai.neargo.shop.pay.client.PayInternalApi;
import ai.neargo.shop.pay.client.PayStoredValueRequests;
import ai.neargo.shop.payclient.RemoteEntitlementCalls;
import ai.neargo.shop.spi.entitlement.EntitlementContext;
import ai.neargo.shop.spi.entitlement.SourceRef;
import ai.neargo.shop.spi.storedvalue.StoredValueUseQuotePort;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;

/** Independent-pay transport for the same read-only offer used by embedded core orchestration. */
@Component
@ConditionalOnProperty(name = "shop.pay.deployment", havingValue = "standalone")
public class RemoteStoredValueUseQuotePort implements StoredValueUseQuotePort {
    private final PayInternalApi pay;

    public RemoteStoredValueUseQuotePort(PayInternalApi pay) {
        this.pay = pay;
    }

    @Override
    public Quote quote(EntitlementContext context, SourceRef source) {
        return RemoteEntitlementCalls.call(() -> pay.svUseQuote(new PayStoredValueRequests.UseQuote(context, source)));
    }
    @Override public Quote quote(EntitlementContext context,SourceRef source,StoredValueWallets.Choice choice){return RemoteEntitlementCalls.call(()->pay.svUseQuote(new PayStoredValueRequests.UseQuote(context,source,choice)));}
}
