package ai.neargo.commerce.starter.http;

import ai.neargo.commerce.api.common.ActorContext;
import java.util.Optional;

/**
 * Optional host gate in front of operator money actions (refund approve, offline confirmation).
 * Commerce still executes with the operator's own live session; the host only decides whether this
 * exact request has been approved yet. Absent bean = no gate.
 */
public interface OpsApprovalGatePort {
    String MALL_REFUND_APPROVE = "MALL_REFUND_APPROVE";
    String MALL_OFFLINE_CONFIRM = "MALL_OFFLINE_CONFIRM";

    /**
     * @param subjectNo refund number, or the confirmation action number
     * @param request the request body as received
     * @return empty = proceed now; present = held (submitted or waiting for approval), respond with it
     */
    Optional<Object> hold(String action, ActorContext operator, String storeNo, String orderNo, String subjectNo, Object request);
}
