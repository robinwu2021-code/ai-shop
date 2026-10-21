package ai.neargo.shop.food.spi;

import ai.neargo.shop.food.dto.ConfigRevisionContentVO;
import ai.neargo.shop.food.dto.ConfigRevisionVO;
import ai.neargo.shop.food.dto.ListConfigRevisionsQuery;
import ai.neargo.shop.food.dto.PublishConfigReq;
import ai.neargo.shop.food.dto.PublishConfigResultVO;
import ai.neargo.shop.spi.inventory.MaterialCatalogFacts;
import ai.neargo.shop.spi.product.CatalogRevisionDeserializer;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;
import java.time.OffsetDateTime;
import java.util.List;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.service.annotation.PostExchange;
import tools.jackson.databind.annotation.JsonDeserialize;

/** Publication and immutable reads travel through the existing FOOD service-token channel. */
public interface ConfigRevisionInternalApi {
    String RECIPE_PATH = "/internal/food/config-revisions/publish-recipe";
    String MODIFIER_PATH = "/internal/food/config-revisions/publish-modifier";
    String BUNDLE_PATH = "/internal/food/config-revisions/publish-bundle";
    String GROUP_PATH = "/internal/food/config-revisions/publish-option-group";
    String LIST_PATH = "/internal/food/config-revisions/list";
    String GET_PATH = "/internal/food/config-revisions/get";
    String RESOLVE_PATH = "/internal/food/config-revisions/resolve";
    @PostExchange(RECIPE_PATH)
    PublishConfigResultVO publishRecipe(@RequestBody Command command);
    @PostExchange(MODIFIER_PATH)
    PublishConfigResultVO publishModifier(@RequestBody Command command);
    @PostExchange(BUNDLE_PATH)
    PublishConfigResultVO publishBundle(@RequestBody Command command);
    @PostExchange(GROUP_PATH)
    PublishConfigResultVO publishOptionGroup(@RequestBody Command command);
    @PostExchange(LIST_PATH)
    PageReceipt listRevisions(@RequestBody Lookup query);
    @PostExchange(GET_PATH)
    ConfigRevisionContentVO getRevision(@RequestBody Identity query);
    @PostExchange(RESOLVE_PATH)
    ConfigRevisionContentVO resolveRevision(@RequestBody Resolve query);

    record PageReceipt(List<ConfigRevisionVO> records,
            @JsonDeserialize(using=CatalogRevisionDeserializer.class) Long total,
            @JsonDeserialize(using=CatalogRevisionDeserializer.class) Long page,
            @JsonDeserialize(using=CatalogRevisionDeserializer.class) Long size) { }

    record Command(@NotBlank @Size(max=32)
            @JsonDeserialize(using=MaterialCatalogFacts.TextDeserializer.class) String entityNo,
            @NotBlank @Size(max=32)
            @JsonDeserialize(using=MaterialCatalogFacts.TextDeserializer.class) String storeNo,
            @NotBlank @Size(max=32)
            @JsonDeserialize(using=MaterialCatalogFacts.TextDeserializer.class) String configNo,
            @NotBlank @Size(max=32)
            @JsonDeserialize(using=MaterialCatalogFacts.TextDeserializer.class) String actorNo,
            @NotBlank @Size(max=128)
            @JsonDeserialize(using=MaterialCatalogFacts.TextDeserializer.class) String idempotencyKey,
            @NotNull @Valid PublishConfigReq publication) { }
    record Lookup(@NotBlank @Size(max=32)
            @JsonDeserialize(using=MaterialCatalogFacts.TextDeserializer.class) String entityNo,
            @NotBlank @Size(max=32)
            @JsonDeserialize(using=MaterialCatalogFacts.TextDeserializer.class) String storeNo,
            @NotNull @Valid ListConfigRevisionsQuery query) { }
    record Identity(@NotBlank @Size(max=32)
            @JsonDeserialize(using=MaterialCatalogFacts.TextDeserializer.class) String entityNo,
            @NotBlank @Size(max=32)
            @JsonDeserialize(using=MaterialCatalogFacts.TextDeserializer.class) String storeNo,
            @NotBlank @Size(max=32)
            @JsonDeserialize(using=MaterialCatalogFacts.TextDeserializer.class) String revisionNo) { }
    record Resolve(@NotBlank @Size(max=32)
            @JsonDeserialize(using=MaterialCatalogFacts.TextDeserializer.class) String entityNo,
            @NotBlank @Size(max=32)
            @JsonDeserialize(using=MaterialCatalogFacts.TextDeserializer.class) String storeNo,
            @NotBlank @Size(max=24)
            @JsonDeserialize(using=MaterialCatalogFacts.TextDeserializer.class) String configKind,
            @NotBlank @Size(max=32)
            @JsonDeserialize(using=MaterialCatalogFacts.TextDeserializer.class) String configNo,
            @NotNull OffsetDateTime asOf) { }
}
