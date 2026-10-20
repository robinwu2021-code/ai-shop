package ai.neargo.shop.food.spi;

import ai.neargo.shop.common.PageData;
import ai.neargo.shop.food.dto.CreateTableAreaReq;
import ai.neargo.shop.food.dto.ListTableAreasQuery;
import ai.neargo.shop.food.dto.SaveTableAreaReq;
import ai.neargo.shop.food.dto.SetStatusTableAreaReq;
import ai.neargo.shop.food.dto.TableAreaResultVO;
import ai.neargo.shop.food.dto.TableAreaVO;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.service.annotation.PostExchange;

/** Typed restaurant transport carries trusted platform context without creating a second business API. */
public interface TableInternalApi {
    String LIST_PATH = "/internal/food/table-areas/list";
    String CREATE_PATH = "/internal/food/table-areas/create";
    String SAVE_PATH = "/internal/food/table-areas/save";
    String STATUS_PATH = "/internal/food/table-areas/set-status";

    @PostExchange(LIST_PATH)
    PageData<TableAreaVO> listTableAreas(@RequestBody Lookup request);
    @PostExchange(CREATE_PATH)
    TableAreaResultVO createTableArea(@RequestBody CreateCommand request);
    @PostExchange(SAVE_PATH)
    TableAreaResultVO saveTableArea(@RequestBody SaveCommand request);
    @PostExchange(STATUS_PATH)
    TableAreaResultVO setStatusTableArea(@RequestBody StatusCommand request);

    record Lookup(@NotBlank @Size(max=32) String entityNo, @NotBlank @Size(max=32) String storeNo,
            @Valid ListTableAreasQuery query) { }
    record CreateCommand(@NotBlank @Size(max=32) String entityNo, @NotBlank @Size(max=32) String storeNo,
            @NotBlank @Size(max=32) String actorNo, @NotBlank @Size(max=128) String idempotencyKey,
            @Valid @NotNull CreateTableAreaReq area) { }
    record SaveCommand(@NotBlank @Size(max=32) String entityNo, @NotBlank @Size(max=32) String storeNo,
            @NotBlank @Size(max=32) String areaNo, @NotBlank @Size(max=32) String actorNo,
            @NotBlank @Size(max=128) String idempotencyKey, @Valid @NotNull SaveTableAreaReq area) { }
    record StatusCommand(@NotBlank @Size(max=32) String entityNo, @NotBlank @Size(max=32) String storeNo,
            @NotBlank @Size(max=32) String areaNo, @NotBlank @Size(max=32) String actorNo,
            @NotBlank @Size(max=128) String idempotencyKey, @Valid @NotNull SetStatusTableAreaReq area) { }
}
