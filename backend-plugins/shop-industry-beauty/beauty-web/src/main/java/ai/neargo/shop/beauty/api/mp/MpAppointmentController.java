package ai.neargo.shop.beauty.api.mp;

import ai.neargo.shop.auth.SecurityUtils;
import ai.neargo.shop.beauty.config.ConditionalOnBeautyWebEnabled;
import ai.neargo.shop.beauty.dto.AppointmentChangePreviewVO;
import ai.neargo.shop.beauty.dto.AppointmentPreviewVO;
import ai.neargo.shop.beauty.dto.CancelAppointmentReq;
import ai.neargo.shop.beauty.dto.CancellationVO;
import ai.neargo.shop.beauty.dto.ChangeAppointmentReq;
import ai.neargo.shop.beauty.dto.CreateMyAppointmentReq;
import ai.neargo.shop.beauty.dto.MyAppointmentDetailVO;
import ai.neargo.shop.beauty.dto.MyAppointmentQuery;
import ai.neargo.shop.beauty.dto.MyAppointmentVO;
import ai.neargo.shop.beauty.dto.PreviewMyAppointmentChangeReq;
import ai.neargo.shop.beauty.dto.PreviewMyAppointmentReq;
import ai.neargo.shop.beauty.service.AppointmentService;
import ai.neargo.shop.common.BizException;
import ai.neargo.shop.common.ErrorCode;
import ai.neargo.shop.common.PageData;
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
@RestController("beautyMpAppointmentController")
@Validated
@ConditionalOnBeautyWebEnabled
@Profile("api")
@RequestMapping("/mp/x/beauty/appointments")
public class MpAppointmentController {
    private final ObjectProvider<AppointmentService> appointmentService;
    public MpAppointmentController(ObjectProvider<AppointmentService> appointmentService) {
        this.appointmentService = appointmentService;
    }

    /** previewMyAppointment retains the approved public contract and delegates business authorization. */
    @PostMapping("/previews")
    public AppointmentPreviewVO previewMyAppointment(@Valid @RequestBody PreviewMyAppointmentReq req) {
        requireConsumer();
        return appointmentService.getObject().previewMyAppointment(SecurityUtils.currentUserNo(), req);
    }

    /** createMyAppointment retains the approved public contract and delegates business authorization. */
    @PostMapping
    public MyAppointmentVO createMyAppointment(@Valid @RequestBody CreateMyAppointmentReq req,
            @RequestHeader("Idempotency-Key") String idempotencyKey) {
        requireConsumer();
        return appointmentService.getObject().createMyAppointment(SecurityUtils.currentUserNo(), req, idempotencyKey);
    }

    /** listMyAppointments retains the approved public contract and delegates business authorization. */
    @GetMapping
    public PageData<MyAppointmentVO> listMyAppointments(@Valid @ModelAttribute MyAppointmentQuery query) {
        requireConsumer();
        return appointmentService.getObject().listMyAppointments(SecurityUtils.currentUserNo(), query);
    }

    /** getMyAppointment retains the approved public contract and delegates business authorization. */
    @GetMapping("/{appointmentNo}")
    public MyAppointmentDetailVO getMyAppointment(@PathVariable("appointmentNo") String appointmentNo) {
        requireConsumer();
        return appointmentService.getObject().getMyAppointment(SecurityUtils.currentUserNo(), appointmentNo);
    }

    /** previewMyAppointmentChange retains the approved public contract and delegates business authorization. */
    @PostMapping("/{appointmentNo}/changes/previews")
    public AppointmentChangePreviewVO previewMyAppointmentChange(@PathVariable("appointmentNo") String
            appointmentNo, @Valid @RequestBody PreviewMyAppointmentChangeReq req) {
        requireConsumer();
        return appointmentService.getObject().previewMyAppointmentChange(SecurityUtils.currentUserNo(),
                appointmentNo, req);
    }

    /** changeMyAppointment retains the approved public contract and delegates business authorization. */
    @PostMapping("/{appointmentNo}/changes")
    public MyAppointmentVO changeMyAppointment(@PathVariable("appointmentNo") String appointmentNo, @Valid
            @RequestBody ChangeAppointmentReq req, @RequestHeader("Idempotency-Key") String idempotencyKey) {
        requireConsumer();
        return appointmentService.getObject().changeMyAppointment(SecurityUtils.currentUserNo(), appointmentNo,
                req, idempotencyKey);
    }

    /** cancelMyAppointment retains the approved public contract and delegates business authorization. */
    @PostMapping("/{appointmentNo}/cancellations")
    public CancellationVO cancelMyAppointment(@PathVariable("appointmentNo") String appointmentNo, @Valid
            @RequestBody CancelAppointmentReq req, @RequestHeader("Idempotency-Key") String idempotencyKey) {
        requireConsumer();
        return appointmentService.getObject().cancelMyAppointment(SecurityUtils.currentUserNo(), appointmentNo,
                req, idempotencyKey);
    }

    private void requireConsumer() {
        var user = SecurityUtils.requireUser();
        if (!"MAIN".equals(user.tenantNo()) || !"CONSUMER".equals(user.realm().name())
                || !"USR".equals(user.subjectKind().name())) { throw BizException.of(ErrorCode.FORBIDDEN); }
    }
}
