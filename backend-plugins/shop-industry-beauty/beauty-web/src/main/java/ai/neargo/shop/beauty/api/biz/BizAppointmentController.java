package ai.neargo.shop.beauty.api.biz;

import ai.neargo.shop.auth.BizContext;
import ai.neargo.shop.auth.BizContextFilter;
import ai.neargo.shop.auth.SecurityUtils;
import ai.neargo.shop.beauty.config.ConditionalOnBeautyWebEnabled;
import ai.neargo.shop.beauty.dto.AppointmentChangePreviewVO;
import ai.neargo.shop.beauty.dto.AppointmentDetailVO;
import ai.neargo.shop.beauty.dto.AppointmentPreviewVO;
import ai.neargo.shop.beauty.dto.AppointmentQuery;
import ai.neargo.shop.beauty.dto.AppointmentSummaryVO;
import ai.neargo.shop.beauty.dto.AppointmentVO;
import ai.neargo.shop.beauty.dto.CancelAppointmentReq;
import ai.neargo.shop.beauty.dto.CancellationVO;
import ai.neargo.shop.beauty.dto.ChangeAppointmentReq;
import ai.neargo.shop.beauty.dto.CreateAppointmentReq;
import ai.neargo.shop.beauty.dto.NoShowVO;
import ai.neargo.shop.beauty.dto.OpenAppointmentWorkOrderReq;
import ai.neargo.shop.beauty.dto.PreviewAppointmentChangeReq;
import ai.neargo.shop.beauty.dto.PreviewAppointmentReq;
import ai.neargo.shop.beauty.dto.RecordNoShowReq;
import ai.neargo.shop.beauty.dto.WorkOrderVO;
import ai.neargo.shop.beauty.service.AppointmentService;
import ai.neargo.shop.common.PageData;
import ai.neargo.shop.spi.user.StoreDirectoryPort;

import jakarta.validation.Valid;

import org.springframework.beans.factory.ObjectProvider;
import org.springframework.context.annotation.Profile;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.ModelAttribute;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/** Public ingress derives scope and identity exclusively from the current authenticated context. */
@RestController("beautyBizAppointmentController")
@Validated
@ConditionalOnBeautyWebEnabled
@Profile("api")
@RequestMapping("/biz/x/beauty/appointments")
public class BizAppointmentController {
    private final ObjectProvider<AppointmentService> appointmentService;
    private final ObjectProvider<StoreDirectoryPort> directories;
    public BizAppointmentController(ObjectProvider<AppointmentService> appointmentService,
            ObjectProvider<StoreDirectoryPort> directories) {
        this.appointmentService = appointmentService;
        this.directories = directories;
    }

    /** previewAppointment retains the approved public contract and delegates business authorization. */
    @PostMapping("/previews")
    public AppointmentPreviewVO previewAppointment(@Valid @RequestBody PreviewAppointmentReq req,
            @RequestHeader(value = BizContextFilter.STORE_HEADER, required = false) String requestedStore) {
        BookingStoreScope.require(directories, requestedStore);
        return appointmentService.getObject().previewAppointment(BizContext.requireMerchantNo(),
                BizContext.requireStoreNo(), SecurityUtils.currentUserNo(), req);
    }

    /** createAppointment retains the approved public contract and delegates business authorization. */
    @PostMapping
    public AppointmentVO createAppointment(@Valid @RequestBody CreateAppointmentReq req,
            @RequestHeader("Idempotency-Key") String idempotencyKey, @RequestHeader(value =
            BizContextFilter.STORE_HEADER, required = false) String requestedStore) {
        BookingStoreScope.require(directories, requestedStore);
        return appointmentService.getObject().createAppointment(BizContext.requireMerchantNo(),
                BizContext.requireStoreNo(), SecurityUtils.currentUserNo(), req, idempotencyKey);
    }

    /** listAppointments retains the approved public contract and delegates business authorization. */
    @GetMapping
    public PageData<AppointmentSummaryVO> listAppointments(@Valid @ModelAttribute AppointmentQuery query,
            @RequestHeader(value = BizContextFilter.STORE_HEADER, required = false) String requestedStore) {
        BookingStoreScope.require(directories, requestedStore);
        return appointmentService.getObject().listAppointments(BizContext.requireMerchantNo(),
                BizContext.requireStoreNo(), SecurityUtils.currentUserNo(), query);
    }

    /** getAppointment retains the approved public contract and delegates business authorization. */
    @GetMapping("/{appointmentNo}")
    public AppointmentDetailVO getAppointment(@PathVariable("appointmentNo") String appointmentNo,
            @RequestHeader(value = BizContextFilter.STORE_HEADER, required = false) String requestedStore) {
        BookingStoreScope.require(directories, requestedStore);
        return appointmentService.getObject().getAppointment(BizContext.requireMerchantNo(),
                BizContext.requireStoreNo(), SecurityUtils.currentUserNo(), appointmentNo);
    }

    /** previewAppointmentChange retains the approved public contract and delegates business authorization. */
    @PostMapping("/{appointmentNo}/changes/previews")
    public AppointmentChangePreviewVO previewAppointmentChange(@PathVariable("appointmentNo") String
            appointmentNo, @Valid @RequestBody PreviewAppointmentChangeReq req, @RequestHeader(value =
            BizContextFilter.STORE_HEADER, required = false) String requestedStore) {
        BookingStoreScope.require(directories, requestedStore);
        return appointmentService.getObject().previewAppointmentChange(BizContext.requireMerchantNo(),
                BizContext.requireStoreNo(), SecurityUtils.currentUserNo(), appointmentNo, req);
    }

    /**
     * changeAppointment retains the approved public contract and delegates business authorization.
     */
    @PostMapping("/{appointmentNo}/changes")
    public AppointmentVO changeAppointment(
            @PathVariable("appointmentNo") String appointmentNo,
            @Valid @RequestBody ChangeAppointmentReq req,
            @RequestHeader("Idempotency-Key") String idempotencyKey,
            @RequestHeader(value = BizContextFilter.STORE_HEADER, required = false)
                    String requestedStore) {
        BookingStoreScope.require(directories, requestedStore);
        return appointmentService.getObject().changeAppointment(BizContext.requireMerchantNo(),
                BizContext.requireStoreNo(), SecurityUtils.currentUserNo(), appointmentNo, req, idempotencyKey);
    }

    /**
     * cancelAppointment retains the approved public contract and delegates business authorization.
     */
    @PostMapping("/{appointmentNo}/cancellations")
    public CancellationVO cancelAppointment(
            @PathVariable("appointmentNo") String appointmentNo,
            @Valid @RequestBody CancelAppointmentReq req,
            @RequestHeader("Idempotency-Key") String idempotencyKey,
            @RequestHeader(value = BizContextFilter.STORE_HEADER, required = false)
                    String requestedStore) {
        BookingStoreScope.require(directories, requestedStore);
        return appointmentService.getObject().cancelAppointment(BizContext.requireMerchantNo(),
                BizContext.requireStoreNo(), SecurityUtils.currentUserNo(), appointmentNo, req, idempotencyKey);
    }

    /** recordNoShow retains the approved public contract and delegates business authorization. */
    @PostMapping("/{appointmentNo}/no-shows")
    public NoShowVO recordNoShow(
            @PathVariable("appointmentNo") String appointmentNo,
            @Valid @RequestBody RecordNoShowReq req,
            @RequestHeader("Idempotency-Key") String idempotencyKey,
            @RequestHeader(value = BizContextFilter.STORE_HEADER, required = false)
                    String requestedStore) {
        BookingStoreScope.require(directories, requestedStore);
        return appointmentService.getObject().recordNoShow(BizContext.requireMerchantNo(),
                BizContext.requireStoreNo(), SecurityUtils.currentUserNo(), appointmentNo, req, idempotencyKey);
    }

    /**
     * Opening reuses the existing durable Service; the URL never creates a second business
     * namespace.
     */
    @PostMapping("/{appointmentNo}/work-orders")
    public WorkOrderVO openWorkOrderFromAppointment(
            @PathVariable("appointmentNo") String appointmentNo,
            @Valid @RequestBody OpenAppointmentWorkOrderReq req,
            @RequestHeader("Idempotency-Key") String idempotencyKey,
            @RequestHeader(value = BizContextFilter.STORE_HEADER, required = false)
                    String requestedStore) {
        BookingStoreScope.requireResourceNo(appointmentNo);
        BookingStoreScope.require(directories, requestedStore);
        BookingStoreScope.requireKey(idempotencyKey);
        return appointmentService
                .getObject()
                .openWorkOrderFromAppointment(
                        BizContext.requireMerchantNo(),
                        BizContext.requireStoreNo(),
                        SecurityUtils.currentUserNo(),
                        appointmentNo,
                        req,
                        idempotencyKey);
    }
}
