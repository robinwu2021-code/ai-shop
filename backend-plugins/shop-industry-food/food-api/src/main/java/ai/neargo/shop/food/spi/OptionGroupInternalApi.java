package ai.neargo.shop.food.spi;

import ai.neargo.shop.common.PageData;
import ai.neargo.shop.food.dto.ReplaceOptionGroupItemsReq;
import ai.neargo.shop.food.dto.OptionGroupMutationVO;
import ai.neargo.shop.food.dto.CreateOptionGroupReq;
import ai.neargo.shop.food.dto.CreateOptionGroupResultVO;
import ai.neargo.shop.food.dto.ListOptionGroupsQuery;
import ai.neargo.shop.food.dto.OptionGroupConfigVO;
import ai.neargo.shop.food.dto.OptionGroupResultVO;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.service.annotation.PostExchange;

/** Carries only platform-resolved scope over the original authenticated FOOD service transport. */
public interface OptionGroupInternalApi {
    String CREATE_PATH = "/internal/food/option-groups/create";
    String GET_PATH = "/internal/food/option-groups/get";
    String LIST_PATH = "/internal/food/option-groups/list";

    @PostExchange(CREATE_PATH)
    CreateOptionGroupResultVO createOptionGroup(@RequestBody CreateCommand command);
    @PostExchange(GET_PATH)
    OptionGroupResultVO getOptionGroup(@RequestBody Identity query);
    @PostExchange(LIST_PATH)
    PageData<OptionGroupConfigVO> listOptionGroups(@RequestBody Lookup query);

    String REPLACE_PATH = "/internal/food/option-groups/replace-items";
    @PostExchange(REPLACE_PATH)
    OptionGroupMutationVO replaceOptionGroupItems(@RequestBody ReplaceCommand command);

    record ReplaceCommand(@NotBlank @Size(max=32) String entityNo, @NotBlank @Size(max=32) String storeNo,
            @NotBlank @Size(max=32) String groupNo, @NotBlank @Size(max=32) String actorNo,
            @NotBlank @Size(max=128) String idempotencyKey, @NotNull @Valid ReplaceOptionGroupItemsReq definition) { }

    record CreateCommand(@NotBlank @Size(max=32) String entityNo, @NotBlank @Size(max=32) String storeNo,
            @NotBlank @Size(max=32) String actorNo, @NotBlank @Size(max=128) String idempotencyKey,
            @Valid @NotNull CreateOptionGroupReq group) { }
    record Identity(@NotBlank @Size(max=32) String entityNo, @NotBlank @Size(max=32) String storeNo,
            @NotBlank @Size(max=32) String configNo) { }
    record Lookup(@NotBlank @Size(max=32) String entityNo, @NotBlank @Size(max=32) String storeNo,
            @Valid ListOptionGroupsQuery query) { }
}
