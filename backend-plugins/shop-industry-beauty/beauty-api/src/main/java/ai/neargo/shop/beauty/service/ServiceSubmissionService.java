package ai.neargo.shop.beauty.service;

import ai.neargo.shop.beauty.dto.RecoverSubmissionReq;
import ai.neargo.shop.beauty.dto.RecoveryRequestVO;
import ai.neargo.shop.beauty.dto.ServiceSubmissionVO;
import ai.neargo.shop.beauty.dto.SubmitServicesReq;

/** Local durable source intents; only original owner operations may be resumed. */
public interface ServiceSubmissionService {
    ServiceSubmissionVO submitForWorkOrder(
            String entityNo,
            String currentStoreNo,
            String actorNo,
            String workOrderNo,
            SubmitServicesReq req,
            String idempotencyKey);

    ServiceSubmissionVO getSubmission(
            String entityNo, String currentStoreNo, String actorNo, String submissionNo);

    RecoveryRequestVO requestSubmissionRecovery(
            String entityNo,
            String currentStoreNo,
            String actorNo,
            String submissionNo,
            RecoverSubmissionReq req,
            String idempotencyKey);

    ServiceSubmissionVO recoverPending(
            String entityNo, String currentStoreNo, String actorNo, String submissionNo);
}
