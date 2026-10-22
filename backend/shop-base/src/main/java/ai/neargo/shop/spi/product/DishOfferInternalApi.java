package ai.neargo.shop.spi.product;

import ai.neargo.shop.spi.product.DishOfferQueryPort.Offer;
import com.fasterxml.jackson.annotation.JsonAnySetter;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;
import java.util.List;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.service.annotation.PostExchange;

/** Original platform authority exposes bounded LOCAL offers to the standalone food host. */
public interface DishOfferInternalApi {
    String BATCH_PATH = "/internal/product/dish-offers/batch";

    @PostExchange(BATCH_PATH)
    List<Offer> lookupBatch(@RequestBody BatchLookup request);

    record BatchLookup(@NotBlank @Size(max=32) String entityNo, @NotBlank @Size(max=32) String storeNo,
            @NotNull @Size(min=1,max=100) List<@NotBlank @Size(max=32) String> skuNos) {
        @JsonAnySetter
        public void rejectUnknown(String name, Object value) {
            throw new IllegalArgumentException("Unknown offer field");
        }
    }
}
