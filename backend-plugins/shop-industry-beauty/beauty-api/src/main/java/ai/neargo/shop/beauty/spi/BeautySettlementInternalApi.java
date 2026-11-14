package ai.neargo.shop.beauty.spi;

import ai.neargo.shop.beauty.dto.PreviewWorkOrderSettlementReq;
import ai.neargo.shop.beauty.dto.WorkOrderSettlementPreviewVO;
import ai.neargo.shop.beauty.dto.WorkOrderSettlementVO;
import ai.neargo.shop.spi.security.InternalCall;
import ai.neargo.shop.spi.security.InternalReply;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.service.annotation.PostExchange;

/** Both routes are bound HUMAN reads, not payment commands or machine collection recovery. */
public interface BeautySettlementInternalApi {
    String ENDPOINT = "BEAUTY_WORK_ORDER_SETTLEMENT";
    String READ = "READ_SETTLEMENT";
    String PREVIEW = "PREVIEW_SETTLEMENT";
    String READ_PATH = "/internal/beauty/work-orders/settlement/queries";
    String PREVIEW_PATH = "/internal/beauty/work-orders/settlement/previews";

    record ReadReq(@NotNull @Pattern(regexp = "[A-Za-z0-9_.:-]{1,64}") String workOrderNo) {}
    record PreviewReq(@NotNull @Pattern(regexp = "[A-Za-z0-9_.:-]{1,64}") String workOrderNo,
                      @NotNull @Valid PreviewWorkOrderSettlementReq preview) {}

    @PostExchange(READ_PATH)
    InternalReply<WorkOrderSettlementVO> read(@RequestBody InternalCall<ReadReq> request);
    @PostExchange(PREVIEW_PATH)
    InternalReply<WorkOrderSettlementPreviewVO> preview(@RequestBody InternalCall<PreviewReq> request);
}
