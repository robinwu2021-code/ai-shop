package ai.neargo.shop.food.spi;

import ai.neargo.shop.food.dto.CreateRecipeReq;
import ai.neargo.shop.food.dto.ListRecipesQuery;
import ai.neargo.shop.food.dto.RecipeResultVO;
import ai.neargo.shop.food.dto.RecipeVO;
import ai.neargo.shop.food.dto.ReplaceRecipeDefinitionReq;
import ai.neargo.shop.spi.inventory.MaterialCatalogFacts;
import ai.neargo.shop.spi.product.CatalogRevisionDeserializer;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;
import java.util.List;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.service.annotation.PostExchange;
import tools.jackson.databind.annotation.JsonDeserialize;

/** Only authenticated platform scope crosses the original FOOD service-token transport. */
public interface RecipeInternalApi {
    String CREATE_PATH = "/internal/food/recipes/create";
    String REPLACE_PATH = "/internal/food/recipes/replace-definition";
    String GET_PATH = "/internal/food/recipes/get";
    String LIST_PATH = "/internal/food/recipes/list";

    @PostExchange(CREATE_PATH)
    RecipeResultVO createRecipe(@RequestBody CreateCommand command);
    @PostExchange(REPLACE_PATH)
    RecipeResultVO replaceDefinition(@RequestBody UpdateCommand command);
    @PostExchange(GET_PATH)
    RecipeResultVO getRecipe(@RequestBody GetQuery query);
    @PostExchange(LIST_PATH)
    PageReceipt listRecipes(@RequestBody Lookup query);

    /** Same public page shape, with transport-local strict counters before receipt validation. */
    record PageReceipt(List<RecipeVO> records,
            @JsonDeserialize(using=CatalogRevisionDeserializer.class) Long total,
            @JsonDeserialize(using=CatalogRevisionDeserializer.class) Long page,
            @JsonDeserialize(using=CatalogRevisionDeserializer.class) Long size) { }

    record CreateCommand(@NotBlank @Size(max=32)
            @JsonDeserialize(using=MaterialCatalogFacts.TextDeserializer.class) String entityNo,
            @NotBlank @Size(max=32)
            @JsonDeserialize(using=MaterialCatalogFacts.TextDeserializer.class) String storeNo,
            @NotBlank @Size(max=32)
            @JsonDeserialize(using=MaterialCatalogFacts.TextDeserializer.class) String actorNo,
            @NotBlank @Size(max=128)
            @JsonDeserialize(using=MaterialCatalogFacts.TextDeserializer.class) String idempotencyKey,
            @NotNull @Valid CreateRecipeReq definition) { }
    record UpdateCommand(@NotBlank @Size(max=32)
            @JsonDeserialize(using=MaterialCatalogFacts.TextDeserializer.class) String entityNo,
            @NotBlank @Size(max=32)
            @JsonDeserialize(using=MaterialCatalogFacts.TextDeserializer.class) String storeNo,
            @NotBlank @Size(max=32)
            @JsonDeserialize(using=MaterialCatalogFacts.TextDeserializer.class) String recipeNo,
            @NotBlank @Size(max=32)
            @JsonDeserialize(using=MaterialCatalogFacts.TextDeserializer.class) String actorNo,
            @NotBlank @Size(max=128)
            @JsonDeserialize(using=MaterialCatalogFacts.TextDeserializer.class) String idempotencyKey,
            @NotNull @Valid ReplaceRecipeDefinitionReq definition) { }
    record GetQuery(@NotBlank @Size(max=32)
            @JsonDeserialize(using=MaterialCatalogFacts.TextDeserializer.class) String entityNo,
            @NotBlank @Size(max=32)
            @JsonDeserialize(using=MaterialCatalogFacts.TextDeserializer.class) String storeNo,
            @NotBlank @Size(max=32)
            @JsonDeserialize(using=MaterialCatalogFacts.TextDeserializer.class) String recipeNo) { }
    record Lookup(@NotBlank @Size(max=32)
            @JsonDeserialize(using=MaterialCatalogFacts.TextDeserializer.class) String entityNo,
            @NotBlank @Size(max=32)
            @JsonDeserialize(using=MaterialCatalogFacts.TextDeserializer.class) String storeNo,
            @Valid ListRecipesQuery query) { }
}
