package ai.neargo.shop.payclient;

import ai.neargo.shop.pay.client.EntitlementRemoteCalls;
import ai.neargo.shop.svc.ServiceName;
import java.util.function.Supplier;

/** Main application routes to the pay owner, with the same failure semantics as reverse fact lookups. */
public final class RemoteEntitlementCalls {
    private RemoteEntitlementCalls() { }
    public static <T> T call(Supplier<T> operation) {
        return EntitlementRemoteCalls.call(ServiceName.PAY, operation);
    }
}
