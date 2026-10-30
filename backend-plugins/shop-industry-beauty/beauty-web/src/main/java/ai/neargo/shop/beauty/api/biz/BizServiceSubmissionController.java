package ai.neargo.shop.beauty.api.biz;

import ai.neargo.shop.auth.BizContext;
import ai.neargo.shop.auth.BizContextFilter;
import ai.neargo.shop.auth.SecurityUtils;
import ai.neargo.shop.beauty.config.ConditionalOnBeautyWebEnabled;
import ai.neargo.shop.beauty.dto.RecoverSubmissionReq;
import ai.neargo.shop.beauty.dto.RecoveryRequestVO;
import ai.neargo.shop.beauty.dto.ServiceSubmissionVO;
import ai.neargo.shop.beauty.service.ServiceSubmissionService;
import ai.neargo.shop.spi.user.StoreDirectoryPort;

import jakarta.validation.Valid;

import org.springframework.beans.factory.ObjectProvider;
import org.springframework.context.annotation.Profile;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * Current authenticated identity and trusted store scope are the only authority passed to owners.
 */
@RestController("beautyBizServiceSubmissionController")
@Validated
@ConditionalOnBeautyWebEnabled
@Profile("api")
@RequestMapping("/biz/x/beauty/service-submissions")
public class BizServiceSubmissionController {
    private final ObjectProvider<ServiceSubmissionService> serviceSubmissionService;
    private final ObjectProvider<StoreDirectoryPort> directories;

    /** Lazy API dependencies keep disabled and ops assembly free of business writers. */
    public BizServiceSubmissionController(
            ObjectProvider<ServiceSubmissionService> serviceSubmissionService,
            ObjectProvider<StoreDirectoryPort> directories) {
        this.serviceSubmissionService = serviceSubmissionService;
        this.directories = directories;
    }

    /** Delegates GET_SUBMISSION without creating alternative business state or authority. */
    @GetMapping("/{submissionNo}")
    public ServiceSubmissionVO getSubmission(
            @PathVariable("submissionNo") String submissionNo,
            @RequestHeader(value = BizContextFilter.STORE_HEADER, required = false)
                    String requestedStore) {
        BookingStoreScope.requireResourceNo(submissionNo);
        BookingStoreScope.require(directories, requestedStore);
        return serviceSubmissionService
                .getObject()
                .getSubmission(
                        BizContext.requireMerchantNo(),
                        BizContext.requireStoreNo(),
                        SecurityUtils.currentUserNo(),
                        submissionNo);
    }

    /**
     * Delegates REQUEST_SUBMISSION_RECOVERY without creating alternative business state or
     * authority.
     */
    @PostMapping("/{submissionNo}/recovery-requests")
    public RecoveryRequestVO requestSubmissionRecovery(
            @PathVariable("submissionNo") String submissionNo,
            @Valid @RequestBody RecoverSubmissionReq req,
            @RequestHeader("Idempotency-Key") String idempotencyKey,
            @RequestHeader(value = BizContextFilter.STORE_HEADER, required = false)
                    String requestedStore) {
        BookingStoreScope.requireResourceNo(submissionNo);
        BookingStoreScope.require(directories, requestedStore);
        BookingStoreScope.requireKey(idempotencyKey);
        return serviceSubmissionService
                .getObject()
                .requestSubmissionRecovery(
                        BizContext.requireMerchantNo(),
                        BizContext.requireStoreNo(),
                        SecurityUtils.currentUserNo(),
                        submissionNo,
                        req,
                        idempotencyKey);
    }
}
