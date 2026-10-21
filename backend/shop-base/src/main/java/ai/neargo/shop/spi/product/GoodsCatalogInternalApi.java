package ai.neargo.shop.spi.product;

import ai.neargo.shop.spi.product.GoodsCatalogQueryPort.GoodsFact;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.service.annotation.PostExchange;

/** Shared product metadata wire contract, with no restaurant runtime dependency. */
public interface GoodsCatalogInternalApi {
    String LOOKUP_PATH = "/internal/product/catalog/goods/lookup";

    @PostExchange(LOOKUP_PATH)
    GoodsFact lookup(@RequestBody Lookup request);

    record Lookup(@NotBlank @Size(max = 32) String entityNo,
            @NotBlank @Size(max = 32) String storeNo,
            @NotBlank @Size(max = 32) String goodsNo) { }
}
