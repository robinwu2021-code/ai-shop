package ai.neargo.shop.food.spi;

import ai.neargo.shop.food.dto.BusinessCloseContracts.DayVO;
import ai.neargo.shop.food.dto.BusinessCloseContracts.ReportVO;
import ai.neargo.shop.food.dto.BusinessCloseContracts.CloseVO;
import ai.neargo.shop.food.dto.BusinessCloseContracts.PreviewReq;
import ai.neargo.shop.food.dto.BusinessCloseContracts.SignReq;
import ai.neargo.shop.food.dto.BusinessCloseContracts.AdjustmentReq;
import ai.neargo.shop.food.dto.OperationVO;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;
import java.time.LocalDate;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.service.annotation.PostExchange;

/** Service-token authenticated platform scope; never accepts identity coordinates from public JSON. */
public interface BusinessCloseInternalApi {
    @PostExchange("/internal/food/business-close/open")
    DayVO open(@RequestBody OpenCommand command);
    @PostExchange("/internal/food/business-close/report")
    ReportVO report(@RequestBody ReportQuery command);
    @PostExchange("/internal/food/business-close/preview")
    CloseVO preview(@RequestBody PreviewCommand command);
    @PostExchange("/internal/food/business-close/get")
    CloseVO get(@RequestBody GetQuery command);
    @PostExchange("/internal/food/business-close/sign")
    CloseVO sign(@RequestBody SignCommand command);
    @PostExchange("/internal/food/business-close/adjust")
    OperationVO adjust(@RequestBody AdjustCommand command);

    record OpenCommand(@NotBlank @Size(max=32) String entity, @NotBlank @Size(max=32) String store,
            @NotBlank @Size(max=32) String actor, @NotNull LocalDate date,
            @NotBlank @Size(max=128) String key) { }
    record ReportQuery(@NotBlank @Size(max=32) String entity, @NotBlank @Size(max=32) String store,
            @NotBlank @Size(max=32) String actor, @NotNull @Valid PreviewReq request) { }
    record PreviewCommand(@NotBlank @Size(max=32) String entity, @NotBlank @Size(max=32) String store,
            @NotBlank @Size(max=32) String actor, @NotNull @Valid PreviewReq request,
            @NotBlank @Size(max=128) String key) { }
    record GetQuery(@NotBlank @Size(max=32) String entity, @NotBlank @Size(max=32) String store,
            @NotBlank @Size(max=32) String actor, @NotBlank @Size(max=32) String closeNo) { }
    record SignCommand(@NotBlank @Size(max=32) String entity, @NotBlank @Size(max=32) String store,
            @NotBlank @Size(max=32) String actor, @NotNull @Valid SignReq request,
            @NotBlank @Size(max=128) String key) { }
    record AdjustCommand(@NotBlank @Size(max=32) String entity, @NotBlank @Size(max=32) String store,
            @NotBlank @Size(max=32) String actor, @NotNull @Valid AdjustmentReq request,
            @NotBlank @Size(max=128) String key) { }
}
