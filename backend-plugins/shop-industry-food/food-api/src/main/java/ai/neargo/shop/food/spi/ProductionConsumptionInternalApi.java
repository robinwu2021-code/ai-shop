package ai.neargo.shop.food.spi;

import ai.neargo.shop.food.dto.ProductionContracts.ConsumptionVO;
import ai.neargo.shop.food.dto.ProductionContracts.ListQuery;
import ai.neargo.shop.common.PageData;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.service.annotation.PostExchange;

/** Source-event identity is the natural replay key; platform service token supplies authority. */
public interface ProductionConsumptionInternalApi {
    @PostExchange("/internal/food/production/consume") ConsumptionVO consume(@RequestBody Command command);
    @PostExchange("/internal/food/production/get") ConsumptionVO get(@RequestBody Command command);
    @PostExchange("/internal/food/production/list") PageData<ConsumptionVO> list(@RequestBody ListCommand command);
    record ListCommand(@NotBlank @Size(max=32) String entity,@NotBlank @Size(max=32) String store,
            @NotBlank @Size(max=32) String actor,@Valid @NotNull ListQuery query) { }
    record Command(@NotBlank @Size(max=32) String entity,@NotBlank @Size(max=32) String store,
            @NotBlank @Size(max=32) String actor,@NotBlank @Size(max=32) String sourceEventNo) { }
}
