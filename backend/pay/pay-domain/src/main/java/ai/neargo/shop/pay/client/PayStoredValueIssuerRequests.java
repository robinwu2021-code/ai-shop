package ai.neargo.shop.pay.client;
import ai.neargo.shop.spi.storedvalue.StoredValueIssuers.*;
public final class PayStoredValueIssuerRequests {
    private PayStoredValueIssuerRequests() { }
    public record PrepareStore(StoreAuthority scope) { }
    public record Find(AuthorizedScope scope) { }
    public record Configure(AuthorizedScope scope, Config command, String key) { }
}
