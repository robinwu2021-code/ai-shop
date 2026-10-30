package ai.neargo.shop.beauty.api.mp;

import ai.neargo.shop.auth.SecurityUtils;
import ai.neargo.shop.beauty.api.biz.BookingStoreScope;
import ai.neargo.shop.beauty.config.ConditionalOnBeautyWebEnabled;
import ai.neargo.shop.beauty.dto.MyWorkOrderVO;
import ai.neargo.shop.beauty.service.WorkOrderService;
import ai.neargo.shop.common.BizException;
import ai.neargo.shop.common.ErrorCode;

import org.springframework.beans.factory.ObjectProvider;
import org.springframework.context.annotation.Profile;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * Current authenticated identity and trusted store scope are the only authority passed to owners.
 */
@RestController("beautyMpWorkOrderController")
@Validated
@ConditionalOnBeautyWebEnabled
@Profile("api")
@RequestMapping("/mp/x/beauty/work-orders")
public class MpWorkOrderController {
    private final ObjectProvider<WorkOrderService> workOrderService;

    /** Lazy API dependencies keep disabled and ops assembly free of business writers. */
    public MpWorkOrderController(ObjectProvider<WorkOrderService> workOrderService) {
        this.workOrderService = workOrderService;
    }

    /** Delegates GET_MY_WORK_ORDER without creating alternative business state or authority. */
    @GetMapping("/{workOrderNo}")
    public MyWorkOrderVO getMyWorkOrder(@PathVariable("workOrderNo") String workOrderNo) {
        BookingStoreScope.requireResourceNo(workOrderNo);
        requireConsumer();
        return workOrderService
                .getObject()
                .getMyWorkOrder(SecurityUtils.currentUserNo(), workOrderNo);
    }

    private void requireConsumer() {
        var user = SecurityUtils.requireUser();
        if (!"MAIN".equals(user.tenantNo())
                || !"CONSUMER".equals(user.realm().name())
                || !"USR".equals(user.subjectKind().name())) {
            throw BizException.of(ErrorCode.FORBIDDEN);
        }
    }
}
