package ai.neargo.shop.food.spi;

import ai.neargo.shop.food.dto.SalesRestrictionReq;
import ai.neargo.shop.food.dto.SalesRestrictionQuery;
import ai.neargo.shop.food.dto.SalesRestrictionVO;
import ai.neargo.shop.food.dto.SalesRestrictionEvaluationVO;
import ai.neargo.shop.spi.inventory.MaterialCatalogFacts;
import ai.neargo.shop.spi.product.CatalogRevisionDeserializer;
import com.fasterxml.jackson.annotation.JsonAnySetter;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;
import java.util.List;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.service.annotation.PostExchange;
import tools.jackson.databind.annotation.JsonDeserialize;

/** Trusted FOOD transport carries resolved ownership rather than public caller-supplied identity. */
public interface SalesRestrictionInternalApi {
    String MANAGE_PATH = "/internal/food/sales-restrictions/manage";
    String LIST_PATH = "/internal/food/sales-restrictions/list";
    String EVALUATE_PATH = "/internal/food/sales-restrictions/evaluate";
    @PostExchange(MANAGE_PATH) SalesRestrictionVO manage(@RequestBody Command command);
    @PostExchange(LIST_PATH) PageReceipt list(@RequestBody Lookup query);
    @PostExchange(EVALUATE_PATH) SalesRestrictionEvaluationVO evaluate(@RequestBody Evaluation query);
    record PageReceipt(List<SalesRestrictionVO> records,
            @JsonDeserialize(using=CatalogRevisionDeserializer.class) Long total,
            @JsonDeserialize(using=CatalogRevisionDeserializer.class) Long page,
            @JsonDeserialize(using=CatalogRevisionDeserializer.class) Long size) { }
    record Command(@NotBlank @Size(max=32)
            @JsonDeserialize(using=MaterialCatalogFacts.TextDeserializer.class) String entityNo,
            @NotBlank @Size(max=32)
            @JsonDeserialize(using=MaterialCatalogFacts.TextDeserializer.class) String storeNo,
            @NotBlank @Size(max=32)
            @JsonDeserialize(using=MaterialCatalogFacts.TextDeserializer.class) String actorNo,
            @NotBlank @Size(max=128)
            @JsonDeserialize(using=MaterialCatalogFacts.TextDeserializer.class) String idempotencyKey,
            @NotNull @Valid SalesRestrictionReq definition) {
        @JsonAnySetter public void rejectUnknown(String key, Object value) {
            throw new IllegalArgumentException("Unknown restriction transport field");
        }
    }
    record Lookup(@NotBlank @Size(max=32)
            @JsonDeserialize(using=MaterialCatalogFacts.TextDeserializer.class) String entityNo,
            @NotBlank @Size(max=32)
            @JsonDeserialize(using=MaterialCatalogFacts.TextDeserializer.class) String storeNo,
            @Valid SalesRestrictionQuery query) {
        @JsonAnySetter public void rejectUnknown(String key, Object value) {
            throw new IllegalArgumentException("Unknown restriction transport field");
        }
    }
    record Evaluation(@NotBlank @Size(max=32)
            @JsonDeserialize(using=MaterialCatalogFacts.TextDeserializer.class) String entityNo,
            @NotBlank @Size(max=32)
            @JsonDeserialize(using=MaterialCatalogFacts.TextDeserializer.class) String storeNo,
            @NotBlank @Size(max=32)
            @JsonDeserialize(using=MaterialCatalogFacts.TextDeserializer.class) String skuNo,
            @NotBlank @Size(max=32)
            @JsonDeserialize(using=MaterialCatalogFacts.TextDeserializer.class) String orderSource,
            @NotBlank @Size(max=32)
            @JsonDeserialize(using=MaterialCatalogFacts.TextDeserializer.class) String fulfillment) {
        @JsonAnySetter public void rejectUnknown(String key, Object value) {
            throw new IllegalArgumentException("Unknown restriction transport field");
        }
    }
}
