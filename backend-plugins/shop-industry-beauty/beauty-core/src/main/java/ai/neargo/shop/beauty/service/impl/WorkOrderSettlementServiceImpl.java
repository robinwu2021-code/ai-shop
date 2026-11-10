package ai.neargo.shop.beauty.service.impl;

import ai.neargo.shop.auth.SecurityUtils;
import ai.neargo.shop.beauty.dto.PreviewWorkOrderSettlementReq;
import ai.neargo.shop.beauty.dto.SourceBillVO;
import ai.neargo.shop.beauty.dto.SourceObservationVO;
import ai.neargo.shop.beauty.dto.WorkOrderBillVO;
import ai.neargo.shop.beauty.dto.WorkOrderSettlementPreviewVO;
import ai.neargo.shop.beauty.dto.WorkOrderSettlementVO;
import ai.neargo.shop.beauty.dto.WorkOrderSettlementVO.SourceDecision;
import ai.neargo.shop.beauty.service.WorkOrderBillService;
import ai.neargo.shop.beauty.service.WorkOrderSettlementService;
import ai.neargo.shop.common.BizException;
import ai.neargo.shop.common.ErrorCode;
import ai.neargo.shop.idem.IdempotencyScope;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import tools.jackson.databind.ObjectMapper;

/** A financial read model over the original bill, with no collection or asset writer dependency. */
public final class WorkOrderSettlementServiceImpl implements WorkOrderSettlementService {
    private final WorkOrderBillService bills;
    private final ObjectMapper json;

    public WorkOrderSettlementServiceImpl(WorkOrderBillService bills, ObjectMapper json) {
        this.bills = bills;
        this.json = json;
    }

    @Override
    public WorkOrderSettlementVO get(String entity, String store, String actor, String workOrderNo) {
        // Original owner checks the current actor and scoped root on every read and preview.
        var bill = bills.getBill(entity, store, actor, workOrderNo);
        var decisions = bill.sources().stream().map(this::decision).toList();
        var reasons = new ArrayList<>(bill.blockingReasons());
        boolean complete = bill.complete() && !decisions.isEmpty()
                && decisions.stream().noneMatch(d -> d.state().equals("INCOMPLETE") || d.state().equals("UNSUPPORTED"));
        if (!complete) reasons.add("SOURCE_INCOMPLETE");
        decisions.forEach(d -> reasons.addAll(d.blockingReasons()));
        reasons.add("DEPOSIT_ALLOCATION_NOT_AUTHORIZED");
        reasons.add("ASSET_COMBINATION_NOT_AUTHORIZED");
        reasons.add("SETTLEMENT_EXECUTION_NOT_AUTHORIZED");
        return new WorkOrderSettlementVO(bill, fingerprint(entity, store, actor, bill), decisions,
                complete, false, List.of(), reasons.stream().distinct().toList());
    }

    @Override
    public WorkOrderSettlementPreviewVO preview(String entity, String store, String actor, String workOrderNo,
                                                PreviewWorkOrderSettlementReq request) {
        var current = get(entity, store, actor, workOrderNo);
        if (!current.fingerprint().equals(request.expectedFingerprint())) throw BizException.of(ErrorCode.CONFLICT);
        if (current.bill().sources().stream().noneMatch(s -> request.currency().equals(s.currency()))) {
            throw BizException.of(ErrorCode.BAD_REQUEST);
        }
        return new WorkOrderSettlementPreviewVO(current, request.currency(), request.walletMinor(),
                request.externalMinor(), request.method(), false, current.blockingReasons());
    }

    private SourceDecision decision(SourceBillVO source) {
        String state;
        var reasons = new ArrayList<>(source.blockingReasons());
        if (!source.complete()) {
            state = "INCOMPLETE";
            reasons.add("SOURCE_INCOMPLETE");
        } else if ("MANUAL_OFFLINE".equals(source.admissionKind())) {
            state = "MANUAL_DECLARED";
            reasons.add("MANUAL_RECEIPT_ALREADY_DECLARED");
        } else if ("LEGACY_FUNDED".equals(source.admissionKind())) {
            if (source.refundedMinor() == null || source.outstandingMinor() == null) {
                state = "INCOMPLETE";
                reasons.add("SOURCE_MONEY_INCOMPLETE");
            } else if (source.refundedMinor() > 0) {
                state = "REFUND_RECOLLECTION_CLOSED";
                reasons.add("REFUND_RECOLLECTION_NOT_AUTHORIZED");
            } else if (source.outstandingMinor() > 0) {
                state = "COLLECTION_CONSENT_REQUIRED";
                reasons.add("SOURCE_COLLECTION_CONSENT_REQUIRED");
            } else {
                state = "PREPAID";
                reasons.add("SOURCE_ALREADY_PREPAID");
            }
        } else {
            state = "UNSUPPORTED";
            reasons.add("SOURCE_PAYMENT_CONTRACT_UNSUPPORTED");
        }
        return new SourceDecision(source.checkoutOperationNo(), source.orderNo(), source.sourceNo(), state,
                false, reasons.stream().distinct().toList());
    }

    private String fingerprint(String entity, String store, String actor, WorkOrderBillVO bill) {
        var user = SecurityUtils.requireUser();
        var canonical = new WorkOrderBillVO(bill.workOrderNo(), bill.scopeRevision(), bill.localWatermark(), null,
                bill.sources().stream().sorted(Comparator.comparing(SourceBillVO::checkoutOperationNo))
                        .map(this::stableSource).toList(),
                bill.currencies(), bill.complete(), bill.blockingReasons());
        return IdempotencyScope.requestHash("BEAUTY_SETTLEMENT_READ_V1", entity, store, actor,
                user.realm().name(), user.subjectKind().name(), json.writeValueAsString(canonical));
    }

    /** Observe time and the read ticket are not changes in the original business facts. */
    private SourceBillVO stableSource(SourceBillVO source) {
        var o = source.observation();
        var observation = o == null ? null : new SourceObservationVO(o.state(), 0, o.sourceRevision(),
                o.sourceHash(), o.lastVerifiedRevision(), o.lastVerifiedHash(), null, o.reasonCode(),
                false, o.admissionKind(), o.manualState(), o.manualRevision(), o.manualHash());
        return new SourceBillVO(source.checkoutOperationNo(), source.orderNo(), source.sourceNo(),
                source.currency(), source.dueMinor(), source.receivedMinor(), source.refundedMinor(),
                source.netReceivedMinor(), source.outstandingMinor(), source.amountScope(), source.receipts(),
                source.tasks(), observation, source.complete(), source.blockingReasons(), source.admissionKind(),
                source.financialVerification(), source.manualReceipt(), source.manualDeclaredMinor());
    }
}
