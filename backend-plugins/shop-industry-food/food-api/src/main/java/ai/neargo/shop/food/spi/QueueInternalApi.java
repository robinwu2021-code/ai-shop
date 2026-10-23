package ai.neargo.shop.food.spi;

import ai.neargo.shop.common.PageData;
import ai.neargo.shop.food.dto.QueueContracts.ChangeReq;
import ai.neargo.shop.food.dto.QueueContracts.IssueReq;
import ai.neargo.shop.food.dto.QueueContracts.Query;
import ai.neargo.shop.food.dto.QueueContracts.QueueVO;
import ai.neargo.shop.food.dto.QueueContracts.SeatQueueReq;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;
import java.time.LocalDate;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.service.annotation.PostExchange;

/** Trusted platform coordinates and original event keys cross the existing FOOD service-token boundary. */
public interface QueueInternalApi {
    @PostExchange("/internal/food/queue/issue") QueueVO issue(@RequestBody IssueCommand command);
    @PostExchange("/internal/food/queue/list") PageData<QueueVO> list(@RequestBody ListQuery command);
    @PostExchange("/internal/food/queue/get") QueueVO get(@RequestBody Identity command);
    @PostExchange("/internal/food/queue/callNext") QueueVO callNext(@RequestBody CallNextCommand command);
    @PostExchange("/internal/food/queue/change") QueueVO change(@RequestBody ChangeCommand command);
    @PostExchange("/internal/food/queue/seat") QueueVO seat(@RequestBody SeatCommand command);
    @PostExchange("/internal/food/queue/recoverSeating") QueueVO recoverSeating(@RequestBody Identity command);

    record IssueCommand(@NotBlank @Size(max=32) String entity,@NotBlank @Size(max=32) String store,
            @NotBlank @Size(max=32) String actor,@NotNull @Valid IssueReq request,
            @NotBlank @Size(max=128) String key) { }
    record ListQuery(@NotBlank @Size(max=32) String entity,@NotBlank @Size(max=32) String store,
            @NotBlank @Size(max=32) String actor,@NotNull @Valid Query query) { }
    record Identity(@NotBlank @Size(max=32) String entity,@NotBlank @Size(max=32) String store,
            @NotBlank @Size(max=32) String actor,@NotBlank @Size(max=32) String ticketNo) { }
    record CallNextCommand(@NotBlank @Size(max=32) String entity,@NotBlank @Size(max=32) String store,
            @NotBlank @Size(max=32) String actor,@NotNull LocalDate businessDate,
            @NotBlank @Size(max=128) String key) { }
    record ChangeCommand(@NotBlank @Size(max=32) String entity,@NotBlank @Size(max=32) String store,
            @NotBlank @Size(max=32) String actor,@NotBlank @Size(max=32) String ticketNo,
            @NotBlank @Size(max=16) String action,@NotNull @Valid ChangeReq request,
            @NotBlank @Size(max=128) String key) { }
    record SeatCommand(@NotBlank @Size(max=32) String entity,@NotBlank @Size(max=32) String store,
            @NotBlank @Size(max=32) String actor,@NotBlank @Size(max=32) String ticketNo,
            @NotNull @Valid SeatQueueReq request) { }
}
