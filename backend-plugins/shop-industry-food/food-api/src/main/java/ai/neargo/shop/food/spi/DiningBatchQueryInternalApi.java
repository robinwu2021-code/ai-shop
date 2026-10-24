package ai.neargo.shop.food.spi;

import ai.neargo.shop.common.PageData;
import ai.neargo.shop.food.dto.DiningBatchSummaryVO;
import ai.neargo.shop.food.dto.ListDiningBatchesQuery;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.service.annotation.PostExchange;

/** Typed original service transport carries trusted staff context and only bounded local-source filters. */
public interface DiningBatchQueryInternalApi {
    String LIST_PATH="/internal/food/batch-query/list";
    @PostExchange(LIST_PATH) PageData<DiningBatchSummaryVO> list(@RequestBody Listing request);
    record Listing(@NotBlank @Size(max=32) String entityNo,@NotBlank @Size(max=32) String storeNo,
            @NotBlank @Size(max=32) String actorNo,@Valid @NotNull ListDiningBatchesQuery query) { }
}
