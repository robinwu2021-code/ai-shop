package ai.neargo.shop.beauty.api.biz;

import ai.neargo.shop.auth.BizContext;
import ai.neargo.shop.auth.BizContextFilter;
import ai.neargo.shop.auth.SecurityUtils;
import ai.neargo.shop.beauty.config.ConditionalOnBeautyWebEnabled;
import ai.neargo.shop.beauty.dto.ClaimGuestReq;
import ai.neargo.shop.beauty.dto.GuestClaimVO;
import ai.neargo.shop.beauty.dto.GuestRegistrationVO;
import ai.neargo.shop.beauty.dto.RegisterGuestReq;
import ai.neargo.shop.beauty.service.GuestRegistrationService;
import ai.neargo.shop.spi.user.StoreDirectoryPort;

import jakarta.validation.Valid;

import org.springframework.beans.factory.ObjectProvider;
import org.springframework.context.annotation.Profile;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * Current authenticated identity and trusted store scope are the only authority passed to owners.
 */
@RestController("beautyBizGuestRegistrationController")
@Validated
@ConditionalOnBeautyWebEnabled
@Profile("api")
@RequestMapping("/biz/x/beauty/guest-registrations")
public class BizGuestRegistrationController {
    private final ObjectProvider<GuestRegistrationService> guestRegistrationService;
    private final ObjectProvider<StoreDirectoryPort> directories;

    /** Lazy API dependencies keep disabled and ops assembly free of business writers. */
    public BizGuestRegistrationController(
            ObjectProvider<GuestRegistrationService> guestRegistrationService,
            ObjectProvider<StoreDirectoryPort> directories) {
        this.guestRegistrationService = guestRegistrationService;
        this.directories = directories;
    }

    /** Delegates REGISTER_GUEST without creating alternative business state or authority. */
    @PostMapping
    public GuestRegistrationVO registerGuest(
            @Valid @RequestBody RegisterGuestReq req,
            @RequestHeader("Idempotency-Key") String idempotencyKey,
            @RequestHeader(value = BizContextFilter.STORE_HEADER, required = false)
                    String requestedStore) {
        BookingStoreScope.require(directories, requestedStore);
        BookingStoreScope.requireKey(idempotencyKey);
        return guestRegistrationService
                .getObject()
                .registerGuest(
                        BizContext.requireMerchantNo(),
                        BizContext.requireStoreNo(),
                        SecurityUtils.currentUserNo(),
                        req,
                        idempotencyKey);
    }

    /** Delegates CLAIM_GUEST without creating alternative business state or authority. */
    @PostMapping("/{guestNo}/claims")
    public GuestClaimVO claimGuest(
            @PathVariable("guestNo") String guestNo,
            @Valid @RequestBody ClaimGuestReq req,
            @RequestHeader("Idempotency-Key") String idempotencyKey,
            @RequestHeader(value = BizContextFilter.STORE_HEADER, required = false)
                    String requestedStore) {
        BookingStoreScope.requireResourceNo(guestNo);
        BookingStoreScope.require(directories, requestedStore);
        BookingStoreScope.requireKey(idempotencyKey);
        return guestRegistrationService
                .getObject()
                .claimGuest(
                        BizContext.requireMerchantNo(),
                        BizContext.requireStoreNo(),
                        SecurityUtils.currentUserNo(),
                        guestNo,
                        req,
                        idempotencyKey);
    }
}
