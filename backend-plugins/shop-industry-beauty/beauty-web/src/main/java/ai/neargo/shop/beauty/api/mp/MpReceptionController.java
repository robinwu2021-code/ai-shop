package ai.neargo.shop.beauty.api.mp;

import ai.neargo.shop.auth.SecurityUtils;
import ai.neargo.shop.beauty.config.ConditionalOnBeautyWebEnabled;
import ai.neargo.shop.beauty.dto.MyArrivalVO;
import ai.neargo.shop.beauty.dto.MyCheckInReq;
import ai.neargo.shop.beauty.service.ReceptionService;
import ai.neargo.shop.common.BizException;
import ai.neargo.shop.common.ErrorCode;

import jakarta.validation.Valid;

import org.springframework.beans.factory.ObjectProvider;
import org.springframework.context.annotation.Profile;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * Current authenticated identity and trusted store scope are the only authority passed to owners.
 */
@RestController("beautyMpReceptionController")
@Validated
@ConditionalOnBeautyWebEnabled
@Profile("api")
@RequestMapping("/mp/x/beauty/arrivals")
public class MpReceptionController {
    private final ObjectProvider<ReceptionService> receptionService;

    /** Lazy API dependencies keep disabled and ops assembly free of business writers. */
    public MpReceptionController(ObjectProvider<ReceptionService> receptionService) {
        this.receptionService = receptionService;
    }

    /**
     * Delegates CHECK_IN_MY_APPOINTMENT without creating alternative business state or authority.
     */
    @PostMapping
    public MyArrivalVO checkInMyAppointment(
            @Valid @RequestBody MyCheckInReq req,
            @RequestHeader("Idempotency-Key") String idempotencyKey) {
        requireConsumer();
        requireKey(idempotencyKey);
        return receptionService
                .getObject()
                .checkInMyAppointment(SecurityUtils.currentUserNo(), req, idempotencyKey);
    }

    private void requireConsumer() {
        var user = SecurityUtils.requireUser();
        if (!"MAIN".equals(user.tenantNo())
                || !"CONSUMER".equals(user.realm().name())
                || !"USR".equals(user.subjectKind().name())) {
            throw BizException.of(ErrorCode.FORBIDDEN);
        }
    }

    private void requireKey(String key) {
        if (key == null || key.isBlank() || key.length() > 128 || !key.equals(key.strip())) {
            throw BizException.of(ErrorCode.BAD_REQUEST);
        }
    }
}
