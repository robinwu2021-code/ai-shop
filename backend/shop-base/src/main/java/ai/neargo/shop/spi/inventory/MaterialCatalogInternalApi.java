package ai.neargo.shop.spi.inventory;

import ai.neargo.shop.spi.inventory.MaterialCatalogQueryPort.MaterialFact;

import com.fasterxml.jackson.annotation.JsonAnySetter;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;

import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.service.annotation.PostExchange;

import tools.jackson.databind.annotation.JsonDeserialize;

import java.util.List;

/**
 * Uses the existing PLATFORM internal transport; user and store authorization stay at the caller
 * boundary.
 */
public interface MaterialCatalogInternalApi {
    String BATCH_PATH = "/internal/inventory/catalog/materials/batch";

    @PostExchange(BATCH_PATH)
    List<MaterialFact> lookupBatch(@RequestBody BatchLookup request);

    @PostExchange("/internal/inventory/catalog/sku-materials/batch")
    List<MaterialCatalogQueryPort.SkuMaterialFact> lookupSkus(@RequestBody SkuLookup request);

    record SkuLookup(String entityNo, String storeNo, List<String> skuNos) {}

    record BatchLookup(
            @NotBlank
                    @Size(max = 32)
                    @JsonDeserialize(using = MaterialCatalogFacts.TextDeserializer.class)
                    String entityNo,
            @NotNull
                    @Size(min = 1, max = 256)
                    @JsonDeserialize(contentUsing = MaterialCatalogFacts.TextDeserializer.class)
                    List<@NotBlank @Size(max = 32) String> itemIds) {
        @JsonAnySetter
        public void rejectUnknown(String name, Object value) {
            throw new IllegalArgumentException("Unknown material lookup field");
        }
    }
}
