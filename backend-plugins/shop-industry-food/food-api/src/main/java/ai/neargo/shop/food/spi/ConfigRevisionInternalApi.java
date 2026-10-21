package ai.neargo.shop.food.spi;

import ai.neargo.shop.common.PageData;
import ai.neargo.shop.food.dto.ConfigRevisionVO;
import ai.neargo.shop.food.dto.ConfigRevisionContentVO;
import ai.neargo.shop.food.dto.ListConfigRevisionsQuery;
import ai.neargo.shop.food.dto.PublishConfigReq;
import ai.neargo.shop.food.dto.PublishConfigResultVO;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;
import java.time.OffsetDateTime;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.service.annotation.PostExchange;

/** Publication and immutable reads travel through the existing FOOD service-token channel. */
public interface ConfigRevisionInternalApi {
    String MODIFIER_PATH = "/internal/food/config-revisions/publish-modifier";
    String GROUP_PATH = "/internal/food/config-revisions/publish-option-group";
    String LIST_PATH = "/internal/food/config-revisions/list";
    String GET_PATH = "/internal/food/config-revisions/get";
    String RESOLVE_PATH = "/internal/food/config-revisions/resolve";
    @PostExchange(MODIFIER_PATH)
    PublishConfigResultVO publishModifier(@RequestBody Command command);
    @PostExchange(GROUP_PATH)
    PublishConfigResultVO publishOptionGroup(@RequestBody Command command);
    @PostExchange(LIST_PATH)
    PageData<ConfigRevisionVO> listRevisions(@RequestBody Lookup query);
    @PostExchange(GET_PATH)
    ConfigRevisionContentVO getRevision(@RequestBody Identity query);
    @PostExchange(RESOLVE_PATH)
    ConfigRevisionContentVO resolveRevision(@RequestBody Resolve query);

    record Command(@NotBlank @Size(max=32) String entityNo, @NotBlank @Size(max=32) String storeNo,
            @NotBlank @Size(max=32) String configNo, @NotBlank @Size(max=32) String actorNo,
            @NotBlank @Size(max=128) String idempotencyKey, @NotNull @Valid PublishConfigReq publication) { }
    record Lookup(@NotBlank @Size(max=32) String entityNo, @NotBlank @Size(max=32) String storeNo,
            @NotNull @Valid ListConfigRevisionsQuery query) { }
    record Identity(@NotBlank @Size(max=32) String entityNo, @NotBlank @Size(max=32) String storeNo,
            @NotBlank @Size(max=32) String revisionNo) { }
    record Resolve(@NotBlank @Size(max=32) String entityNo, @NotBlank @Size(max=32) String storeNo,
            @NotBlank @Size(max=24) String configKind, @NotBlank @Size(max=32) String configNo,
            @NotNull OffsetDateTime asOf) { }
}
