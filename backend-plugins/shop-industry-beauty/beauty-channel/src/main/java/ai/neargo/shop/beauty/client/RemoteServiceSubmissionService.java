package ai.neargo.shop.beauty.client;

import ai.neargo.shop.beauty.dto.RecoverSubmissionReq;
import ai.neargo.shop.beauty.dto.RecoveryRequestVO;
import ai.neargo.shop.beauty.dto.ServiceSubmissionVO;
import ai.neargo.shop.beauty.dto.SubmitServicesReq;
import ai.neargo.shop.beauty.service.ServiceSubmissionService;
import ai.neargo.shop.beauty.spi.BeautyReceptionInternalApi;
import ai.neargo.shop.svc.InternalBindings;

import java.util.Objects;

/** Delegates to the same private owner without retrying uncertain business writes. */
public final class RemoteServiceSubmissionService implements ServiceSubmissionService {
    private final BeautyReceptionInternalApi api;
    private final InternalBindings bindings;

    /** Both typed transport and configured binding secret are mandatory in production. */
    public RemoteServiceSubmissionService(
            BeautyReceptionInternalApi api, InternalBindings bindings) {
        this.api = Objects.requireNonNull(api);
        this.bindings = Objects.requireNonNull(bindings);
    }

    @Override
    public ServiceSubmissionVO getSubmission(
            String entityNo, String currentStoreNo, String actorNo, String submissionNo) {
        return BeautyReceptionCalls.call(
                "BAPI-072",
                "GET_SUBMISSION",
                entityNo,
                currentStoreNo,
                actorNo,
                submissionNo,
                new BeautyReceptionInternalApi.SubmissionRef(submissionNo),
                bindings,
                api::getSubmission);
    }

    @Override
    public RecoveryRequestVO requestSubmissionRecovery(
            String entityNo,
            String currentStoreNo,
            String actorNo,
            String submissionNo,
            RecoverSubmissionReq req,
            String idempotencyKey) {
        return BeautyReceptionCalls.call(
                "BAPI-073",
                "REQUEST_SUBMISSION_RECOVERY",
                entityNo,
                currentStoreNo,
                actorNo,
                submissionNo,
                new BeautyReceptionInternalApi.RequestSubmissionRecovery(
                        submissionNo, req, idempotencyKey),
                bindings,
                api::requestSubmissionRecovery);
    }

    @Override
    public ServiceSubmissionVO recoverPending(
            String entityNo, String currentStoreNo, String actorNo, String submissionNo) {
        return BeautyReceptionCalls.call(
                "BAPI-073",
                "RECOVER_PENDING",
                entityNo,
                currentStoreNo,
                actorNo,
                submissionNo,
                new BeautyReceptionInternalApi.SubmissionRef(submissionNo),
                bindings,
                api::recoverPending);
    }

    @Override
    public ServiceSubmissionVO submitForWorkOrder(
            String entityNo,
            String currentStoreNo,
            String actorNo,
            String workOrderNo,
            SubmitServicesReq req,
            String idempotencyKey) {
        return BeautyReceptionCalls.call(
                "BAPI-056",
                "SUBMIT_SERVICES",
                entityNo,
                currentStoreNo,
                actorNo,
                workOrderNo,
                new BeautyReceptionInternalApi.SubmitServices(workOrderNo, req, idempotencyKey),
                bindings,
                api::submitServices);
    }
}
