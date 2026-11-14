package ai.neargo.shop.beauty.api.biz;

import ai.neargo.shop.auth.BizContext;
import ai.neargo.shop.auth.BizContextFilter;
import ai.neargo.shop.auth.SecurityUtils;
import ai.neargo.shop.beauty.config.ConditionalOnBeautyWebEnabled;
import ai.neargo.shop.beauty.dto.ArrivalChallengeVO;
import ai.neargo.shop.beauty.service.ReceptionService;
import ai.neargo.shop.spi.user.StoreDirectoryPort;

import org.springframework.beans.factory.ObjectProvider;
import org.springframework.context.annotation.Profile;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * Current authenticated identity and trusted store scope are the only authority passed to owners.
 */
@RestController("beautyBizArrivalChallengeController")
@Validated
@ConditionalOnBeautyWebEnabled
@Profile("api")
@RequestMapping("/biz/x/beauty/arrival-challenges")
public class BizArrivalChallengeController {
    private final ObjectProvider<ReceptionService> receptionService;
    private final ObjectProvider<StoreDirectoryPort> directories;

    /** Lazy API dependencies keep disabled and ops assembly free of business writers. */
    public BizArrivalChallengeController(
            ObjectProvider<ReceptionService> receptionService,
            ObjectProvider<StoreDirectoryPort> directories) {
        this.receptionService = receptionService;
        this.directories = directories;
    }

    /**
     * Delegates ISSUE_ARRIVAL_CHALLENGE without creating alternative business state or authority.
     */
    @PostMapping
    public ArrivalChallengeVO issueArrivalChallenge(
            @RequestHeader("Idempotency-Key") String idempotencyKey,
            @RequestHeader(value = BizContextFilter.STORE_HEADER, required = false)
                    String requestedStore) {
        BookingStoreScope.require(directories, requestedStore);
        BookingStoreScope.requireKey(idempotencyKey);
        return receptionService
                .getObject()
                .issueArrivalChallenge(
                        BizContext.requireMerchantNo(),
                        BizContext.requireStoreNo(),
                        SecurityUtils.currentUserNo(),
                        idempotencyKey);
    }
}
