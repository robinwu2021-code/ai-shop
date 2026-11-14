package ai.neargo.shop.food.spi;

import ai.neargo.shop.food.dto.BusinessOperationsContracts.CaseVO;
import ai.neargo.shop.food.dto.BusinessOperationsContracts.Evidence;
import ai.neargo.shop.food.dto.BusinessOperationsContracts.Source;
import ai.neargo.shop.food.dto.FoodSupportContracts.ReadinessVO;

import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;

import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.service.annotation.PostExchange;

import java.util.List;

/** One read-only typed contract for embedded and standalone FOOD support. */
public interface FoodSupportInternalApi {
    String ROOT = "/internal/food/support";

    @PostExchange(ROOT + "/readiness")
    ReadinessVO readiness(@RequestBody ReadinessQuery request);

    @PostExchange(ROOT + "/cases")
    List<CaseVO> cases(@RequestBody CasesQuery request);

    @PostExchange(ROOT + "/source")
    Evidence evidence(@RequestBody SourceQuery request);

    record ReadinessQuery() {}

    record CasesQuery(@NotBlank String entityNo, @NotBlank String storeNo, String status) {}

    record SourceQuery(
            @NotBlank String entityNo, @NotBlank String storeNo, @Valid @NotNull Source source) {}
}
