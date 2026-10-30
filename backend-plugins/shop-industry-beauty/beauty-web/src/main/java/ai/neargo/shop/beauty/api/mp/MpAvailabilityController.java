package ai.neargo.shop.beauty.api.mp;

import ai.neargo.shop.auth.SecurityUtils;
import ai.neargo.shop.beauty.config.ConditionalOnBeautyWebEnabled;
import ai.neargo.shop.beauty.dto.PublicAvailabilityQuery;
import ai.neargo.shop.beauty.dto.PublicAvailabilityVO;
import ai.neargo.shop.beauty.service.AppointmentAvailabilityService;
import ai.neargo.shop.common.BizException;
import ai.neargo.shop.common.ErrorCode;
import ai.neargo.shop.common.PageData;
import jakarta.validation.Valid;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.context.annotation.Profile;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/** Public ingress derives scope and identity exclusively from the current authenticated context. */
@RestController("beautyMpAvailabilityController")
@Validated
@ConditionalOnBeautyWebEnabled
@Profile("api")
@RequestMapping("/mp/x/beauty/availability")
public class MpAvailabilityController {
    private final ObjectProvider<AppointmentAvailabilityService> appointmentAvailabilityService;
    public MpAvailabilityController(ObjectProvider<AppointmentAvailabilityService> appointmentAvailabilityService) {
        this.appointmentAvailabilityService = appointmentAvailabilityService;
    }

    /** queryPublicAvailability retains the approved public contract and delegates business authorization. */
    @PostMapping("/queries")
    public PublicAvailabilityVO queryPublicAvailability(@Valid @RequestBody PublicAvailabilityQuery query) {
        return appointmentAvailabilityService.getObject().queryPublicAvailability(query);
    }
}
