package ai.neargo.shop.food.spi;

import ai.neargo.shop.common.PageData;
import ai.neargo.shop.food.dto.BundleConfigVO;
import ai.neargo.shop.food.dto.BundleMutationVO;
import ai.neargo.shop.food.dto.CreateBundleReq;
import ai.neargo.shop.food.dto.CreateBundleResultVO;
import ai.neargo.shop.food.dto.GetBundleVO;
import ai.neargo.shop.food.dto.ListBundlesQuery;
import ai.neargo.shop.food.dto.ReplaceBundleDefinitionReq;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.service.annotation.PostExchange;

/** Only authenticated platform scope crosses the original FOOD service-token transport. */
public interface BundleInternalApi {
    String CREATE_PATH = "/internal/food/bundles/create";
    String REPLACE_PATH = "/internal/food/bundles/replace-definition";
    String GET_PATH = "/internal/food/bundles/get";
    String LIST_PATH = "/internal/food/bundles/list";

    @PostExchange(CREATE_PATH)
    CreateBundleResultVO createBundle(@RequestBody CreateCommand command);
    @PostExchange(REPLACE_PATH)
    BundleMutationVO replaceDefinition(@RequestBody UpdateCommand command);
    @PostExchange(GET_PATH)
    GetBundleVO getBundle(@RequestBody GetQuery query);
    @PostExchange(LIST_PATH)
    PageData<BundleConfigVO> listBundles(@RequestBody Lookup query);

    record CreateCommand(@NotBlank @Size(max=32) String entityNo, @NotBlank @Size(max=32) String storeNo,
            @NotBlank @Size(max=32) String actorNo, @NotBlank @Size(max=128) String idempotencyKey,
            @NotNull @Valid CreateBundleReq definition) { }
    record UpdateCommand(@NotBlank @Size(max=32) String entityNo, @NotBlank @Size(max=32) String storeNo,
            @NotBlank @Size(max=32) String skuNo, @NotBlank @Size(max=32) String actorNo,
            @NotBlank @Size(max=128) String idempotencyKey,
            @NotNull @Valid ReplaceBundleDefinitionReq definition) { }
    record GetQuery(@NotBlank @Size(max=32) String entityNo, @NotBlank @Size(max=32) String storeNo,
            @NotBlank @Size(max=32) String skuNo) { }
    record Lookup(@NotBlank @Size(max=32) String entityNo, @NotBlank @Size(max=32) String storeNo,
            @Valid ListBundlesQuery query) { }
}
