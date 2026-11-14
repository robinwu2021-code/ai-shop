package ai.neargo.shop.beauty.api.biz;

import ai.neargo.shop.auth.BizContext;
import ai.neargo.shop.auth.BizContextFilter;
import ai.neargo.shop.auth.SecurityUtils;
import ai.neargo.shop.beauty.config.ConditionalOnBeautyWebEnabled;
import ai.neargo.shop.beauty.dto.AvailabilityQuery;
import ai.neargo.shop.beauty.dto.AvailabilityVO;
import ai.neargo.shop.beauty.service.AppointmentAvailabilityService;
import ai.neargo.shop.common.PageData;
import ai.neargo.shop.spi.user.StoreDirectoryPort;
import jakarta.validation.Valid;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.context.annotation.Profile;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/** Public ingress derives scope and identity exclusively from the current authenticated context. */
@RestController("beautyBizAvailabilityController")
@Validated
@ConditionalOnBeautyWebEnabled
@Profile("api")
@RequestMapping("/biz/x/beauty/availability")
public class BizAvailabilityController {
    private final ObjectProvider<AppointmentAvailabilityService> appointmentAvailabilityService;
    private final ObjectProvider<StoreDirectoryPort> directories;
    public BizAvailabilityController(ObjectProvider<AppointmentAvailabilityService>
            appointmentAvailabilityService, ObjectProvider<StoreDirectoryPort> directories) {
        this.appointmentAvailabilityService = appointmentAvailabilityService;
        this.directories = directories;
    }

    /** queryAvailability retains the approved public contract and delegates business authorization. */
    @PostMapping("/queries")
    public AvailabilityVO queryAvailability(@Valid @RequestBody AvailabilityQuery query, @RequestHeader(value =
            BizContextFilter.STORE_HEADER, required = false) String requestedStore) {
        BookingStoreScope.require(directories, requestedStore);
        return appointmentAvailabilityService.getObject().queryAvailability(BizContext.requireMerchantNo(),
                BizContext.requireStoreNo(), SecurityUtils.currentUserNo(), query);
    }

}
