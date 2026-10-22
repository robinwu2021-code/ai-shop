package ai.neargo.shop.food.spi;

import ai.neargo.shop.common.PageData;
import ai.neargo.shop.food.dto.CreateModifierReq;
import ai.neargo.shop.food.dto.CreateModifierResultVO;
import ai.neargo.shop.food.dto.ModifierMutationVO;
import ai.neargo.shop.food.dto.ModifierConfigVO;
import ai.neargo.shop.food.dto.ListModifiersQuery;
import ai.neargo.shop.food.dto.UpdateModifierReq;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.service.annotation.PostExchange;

/** Only platform-resolved scope crosses the original FOOD service-token transport. */
public interface ModifierInternalApi {
    String CREATE_PATH = "/internal/food/modifiers/create";
    String UPDATE_PATH = "/internal/food/modifiers/update";
    String LIST_PATH = "/internal/food/modifiers/list";
    @PostExchange(CREATE_PATH)
    CreateModifierResultVO createModifier(@RequestBody CreateCommand command);
    @PostExchange(UPDATE_PATH)
    ModifierMutationVO updateModifier(@RequestBody UpdateCommand command);
    @PostExchange(LIST_PATH)
    PageData<ModifierConfigVO> listModifiers(@RequestBody Lookup query);

    record CreateCommand(@NotBlank @Size(max=32) String entityNo, @NotBlank @Size(max=32) String storeNo,
            @NotBlank @Size(max=32) String actorNo, @NotBlank @Size(max=128) String idempotencyKey,
            @NotNull @Valid CreateModifierReq definition) { }
    record UpdateCommand(@NotBlank @Size(max=32) String entityNo, @NotBlank @Size(max=32) String storeNo,
            @NotBlank @Size(max=32) String modifierNo, @NotBlank @Size(max=32) String actorNo,
            @NotBlank @Size(max=128) String idempotencyKey, @NotNull @Valid UpdateModifierReq definition) { }
    record Lookup(@NotBlank @Size(max=32) String entityNo, @NotBlank @Size(max=32) String storeNo,
            @Valid ListModifiersQuery query) { }
}
