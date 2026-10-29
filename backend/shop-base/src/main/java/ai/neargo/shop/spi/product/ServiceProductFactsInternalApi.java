package ai.neargo.shop.spi.product;

import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.service.annotation.PostExchange;

/** Read-only product facts across deployment boundaries; credentials are supplied by the shared InternalHttp. */
public interface ServiceProductFactsInternalApi {
    String PATH = "/internal/core/products/service-facts";
    /** Fetches product-owned facts only; the internal caller cannot alter duration, price or shelf state. */
    @PostExchange(PATH)
    ServiceProductFactsPort.Fact get(@RequestBody Lookup request);
    record Lookup(String entityNo, String storeNo, String skuNo) { }
}
