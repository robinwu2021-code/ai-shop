package ai.neargo.shop.beauty.api.biz;

import ai.neargo.shop.auth.BizContext;
import ai.neargo.shop.auth.BizContextFilter;
import ai.neargo.shop.auth.SecurityUtils;
import ai.neargo.shop.beauty.config.ConditionalOnBeautyWebEnabled;
import ai.neargo.shop.beauty.dto.ArrivalVO;
import ai.neargo.shop.beauty.dto.CheckInReq;
import ai.neargo.shop.beauty.dto.ReceiveWalkInReq;
import ai.neargo.shop.beauty.dto.ReceptionExceptionReq;
import ai.neargo.shop.beauty.dto.ReceptionExceptionVO;
import ai.neargo.shop.beauty.dto.WalkInVO;
import ai.neargo.shop.beauty.service.ReceptionService;
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

/**
 * Current authenticated identity and trusted store scope are the only authority passed to owners.
 */
@RestController("beautyBizReceptionController")
@Validated
@ConditionalOnBeautyWebEnabled
@Profile("api")
@RequestMapping("/biz/x/beauty")
public class BizReceptionController {
    private final ObjectProvider<ReceptionService> receptionService;
    private final ObjectProvider<StoreDirectoryPort> directories;

    /** Lazy API dependencies keep disabled and ops assembly free of business writers. */
    public BizReceptionController(
            ObjectProvider<ReceptionService> receptionService,
            ObjectProvider<StoreDirectoryPort> directories) {
        this.receptionService = receptionService;
        this.directories = directories;
    }

    /** Delegates CHECK_IN without creating alternative business state or authority. */
    @PostMapping("/arrivals")
    public ArrivalVO checkIn(
            @Valid @RequestBody CheckInReq req,
            @RequestHeader("Idempotency-Key") String idempotencyKey,
            @RequestHeader(value = BizContextFilter.STORE_HEADER, required = false)
                    String requestedStore) {
        BookingStoreScope.require(directories, requestedStore);
        BookingStoreScope.requireKey(idempotencyKey);
        return receptionService
                .getObject()
                .checkIn(
                        BizContext.requireMerchantNo(),
                        BizContext.requireStoreNo(),
                        SecurityUtils.currentUserNo(),
                        req,
                        idempotencyKey);
    }

    /** Delegates RECEIVE_WALK_IN without creating alternative business state or authority. */
    @PostMapping("/walk-ins")
    public WalkInVO receiveWalkIn(
            @Valid @RequestBody ReceiveWalkInReq req,
            @RequestHeader("Idempotency-Key") String idempotencyKey,
            @RequestHeader(value = BizContextFilter.STORE_HEADER, required = false)
                    String requestedStore) {
        BookingStoreScope.require(directories, requestedStore);
        BookingStoreScope.requireKey(idempotencyKey);
        BookingStoreScope.requireBodyStore(req.storeNo());
        return receptionService
                .getObject()
                .receiveWalkIn(
                        BizContext.requireMerchantNo(),
                        BizContext.requireStoreNo(),
                        SecurityUtils.currentUserNo(),
                        req,
                        idempotencyKey);
    }

    /** Delegates COORDINATE_EXCEPTION without creating alternative business state or authority. */
    @PostMapping("/reception-exceptions")
    public ReceptionExceptionVO coordinateReceptionException(
            @Valid @RequestBody ReceptionExceptionReq req,
            @RequestHeader("Idempotency-Key") String idempotencyKey,
            @RequestHeader(value = BizContextFilter.STORE_HEADER, required = false)
                    String requestedStore) {
        BookingStoreScope.require(directories, requestedStore);
        BookingStoreScope.requireKey(idempotencyKey);
        return receptionService
                .getObject()
                .coordinateReceptionException(
                        BizContext.requireMerchantNo(),
                        BizContext.requireStoreNo(),
                        SecurityUtils.currentUserNo(),
                        req,
                        idempotencyKey);
    }
}
