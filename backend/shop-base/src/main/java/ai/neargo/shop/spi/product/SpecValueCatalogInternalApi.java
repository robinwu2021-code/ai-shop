package ai.neargo.shop.spi.product;

import ai.neargo.shop.spi.product.SpecValueCatalogQueryPort.SpecValueFact;
import com.fasterxml.jackson.annotation.JsonAnySetter;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;
import java.util.List;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.service.annotation.PostExchange;

/** Uses the existing PLATFORM internal transport; user and store authorization stay at the caller boundary. */
public interface SpecValueCatalogInternalApi {
    String BATCH_PATH = "/internal/product/catalog/spec-values/batch";

    @PostExchange(BATCH_PATH)
    List<SpecValueFact> lookupBatch(@RequestBody BatchLookup request);

    record BatchLookup(@NotBlank @Size(max=32) String entityNo,
            @NotNull @Size(min=1, max=256) List<@NotBlank @Size(max=64) String> valueNos) {
        @JsonAnySetter
        public void rejectUnknown(String name, Object value) {
            throw new IllegalArgumentException("Unknown spec lookup field");
        }
    }
}
