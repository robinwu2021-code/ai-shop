package ai.neargo.shop.trade.order.refund.service;

import static ai.neargo.shop.trade.order.entity.OrdRefundExecution.DONE;
import static ai.neargo.shop.trade.order.entity.OrdRefundExecution.FAILED;
import static ai.neargo.shop.trade.order.entity.OrdRefundExecution.PENDING;
import static ai.neargo.shop.trade.order.entity.OrdRefundExecution.PREPARED;
import static ai.neargo.shop.trade.order.entity.OrdRefundExecution.PROCESSING;
import static ai.neargo.shop.trade.order.entity.OrdRefundExecution.REVERSAL;
import static ai.neargo.shop.trade.order.entity.OrdRefundExecution.SUCCEEDED;
import static ai.neargo.shop.trade.order.entity.OrdRefundExecution.UNKNOWN;

import ai.neargo.shop.common.BizException;
import ai.neargo.shop.common.ErrorCode;
import ai.neargo.shop.trade.entity.OrdSubOrder;
import ai.neargo.shop.trade.order.entity.OrdRefundExecution;
import ai.neargo.shop.trade.order.mapper.CanonicalRefundExecutionMapper;
import ai.neargo.shop.trade.order.mapper.CanonicalRefundExecutionMapper.CashRow;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import org.springframework.stereotype.Component;
import tools.jackson.databind.ObjectMapper;

/** Stores a basic execution's immutable plan and mutable recovery snapshot in its cash obligation. */
@Component
public class CanonicalRefundExecutionJournal {
    private final CanonicalRefundExecutionMapper mapper;
    private final ObjectMapper json;

    public CanonicalRefundExecutionJournal(CanonicalRefundExecutionMapper mapper, ObjectMapper json) {
        this.mapper = mapper;
        this.json = json;
    }

    public OrdRefundExecution read(String executionNo, boolean lock) {
        var stored = lock ? mapper.lock(executionNo) : mapper.find(executionNo);
        return stored == null ? null : decode(stored);
    }

    public OrdSubOrder lockSub(String subOrderNo) {
        return mapper.lockSub(subOrderNo);
    }

    public int fundedSource(OrdRefundExecution row, String entityNo) {
        return mapper.fundedSource(row.getOrderNo(), row.getSubOrderNo(), row.getAfterSaleNo(),
                row.getTenantNo(), entityNo);
    }

    public void insert(OrdRefundExecution row) {
        validateIdentity(row);
        var frozen = Frozen.from(row);
        String payload = json.writeValueAsString(frozen);
        require(mapper.insert(storage(row, payload, digest(payload), 0)) == 1);
        row.setVersion(0L);
    }

    public void save(OrdRefundExecution row) {
        var stored = mapper.find(row.getExecutionNo());
        require(stored != null);
        var current = decode(stored);
        require(Objects.equals(current.getVersion(), row.getVersion()));
        require(Frozen.from(current).equals(Frozen.from(row)));
        require(Objects.equals(current.getAfterSaleNo(), row.getAfterSaleNo())
                && Objects.equals(current.getSubOrderNo(), row.getSubOrderNo())
                && Objects.equals(current.getCurrency(), row.getCurrency())
                && Objects.equals(current.getAmountMinor(), row.getAmountMinor()));
        long nextVersion = Math.addExact(current.getVersion(), 1);
        require(mapper.update(current.getId(), (String) stored.get("result_json"),
                storage(row, (String) stored.get("payload_json"), (String) stored.get("input_hash"), nextVersion)) == 1);
        row.setVersion(nextVersion);
    }

    public List<OrdRefundExecution> due(long now, int limit) {
        return mapper.due(now, limit).stream().map(this::decode).toList();
    }

    public List<OrdRefundExecution> succeeded(String tenantNo, String subOrderNo, long through,
            List<String> processed) {
        return mapper.succeeded(subOrderNo, through).stream()
                .filter(row -> !processed.contains(row.get("execution_no")))
                .map(this::decode)
                .filter(row -> Objects.equals(tenantNo, row.getTenantNo()))
                .toList();
    }

    private CashRow storage(OrdRefundExecution row, String payload, String hash, long version) {
        String state = switch (row.getStatus()) {
            case PREPARED -> PENDING;
            case PROCESSING, UNKNOWN, FAILED, SUCCEEDED -> row.getStatus();
            default -> throw BizException.of(ErrorCode.CONFLICT);
        };
        Long finishedAt = SUCCEEDED.equals(state) ? row.getSucceededAt() : null;
        String proof = SUCCEEDED.equals(state) ? row.getPaymentNo() : null;
        if (SUCCEEDED.equals(state)) {
            require(finishedAt != null && proof != null);
        }
        return new CashRow(row.getExecutionNo(), row.getAfterSaleNo(), row.getSubOrderNo(),
                source(row), row.getCurrency(), row.getAmountMinor(), payload, hash, state,
                json.writeValueAsString(Progress.from(row, version)), proof, row.getErrorCode(),
                isRecoverable(row) ? row.getNextAttemptAt() : Long.MAX_VALUE, finishedAt);
    }

    private OrdRefundExecution decode(Map<String, Object> stored) {
        String payload = (String) stored.get("payload_json");
        require(payload != null && digest(payload).equals(stored.get("input_hash")));
        var frozen = json.readValue(payload, Frozen.class);
        var progress = json.readValue((String) stored.get("result_json"), Progress.class);
        var row = new OrdRefundExecution();
        row.setId(((Number) stored.get("id")).longValue());
        row.setExecutionNo((String) stored.get("execution_no"));
        row.setAfterSaleNo((String) stored.get("after_sale_no"));
        row.setSubOrderNo((String) stored.get("sub_order_no"));
        row.setCurrency((String) stored.get("currency"));
        row.setAmountMinor(((Number) stored.get("amount_minor")).longValue());
        row.setErrorCode((String) stored.get("error_code"));
        frozen.apply(row);
        progress.apply(row);
        require(Objects.equals(frozen.executionNo(), row.getExecutionNo())
                && Objects.equals(frozen.afterSaleNo(), row.getAfterSaleNo())
                && Objects.equals(frozen.subOrderNo(), row.getSubOrderNo())
                && Objects.equals(frozen.currency(), row.getCurrency())
                && Objects.equals(frozen.amountMinor(), row.getAmountMinor()));
        validateIdentity(row);
        var projected = storage(row, payload, digest(payload), progress.version());
        require(Objects.equals(projected.sourceNo(), stored.get("source_no"))
                && Objects.equals(projected.state(), stored.get("state"))
                && Objects.equals(projected.proof(), stored.get("authority_proof"))
                && Objects.equals(projected.finishedAt(), stored.get("finished_at"))
                && projected.nextAttemptAt() == ((Number) stored.get("next_attempt_at")).longValue());
        row.setDeleted(0);
        return row;
    }

    private static boolean isRecoverable(OrdRefundExecution row) {
        if ("REFUND_EXECUTION_CONFLICT".equals(row.getErrorCode())) {
            return false;
        }
        return switch (row.getStatus()) {
            case SUCCEEDED -> !DONE.equals(row.getResourceStatus()) || PENDING.equals(row.getClosureStatus());
            case FAILED -> REVERSAL.equals(row.getPhase()) && row.getPaymentNo() == null
                    && "REVERSE_UNCONFIRMED".equals(row.getErrorCode());
            default -> true;
        };
    }

    private static void validateIdentity(OrdRefundExecution row) {
        require(row.getAfterSaleNo() != null && ("RF-" + row.getAfterSaleNo()).equals(row.getExecutionNo())
                && row.getSubOrderNo() != null && row.getOrderNo() != null
                && row.getCurrency() != null && row.getAmountMinor() != null
                && row.getPlanJson() != null && row.getNextAttemptAt() != null
                && (row.getOriginPaymentNo() != null || FAILED.equals(row.getStatus())));
    }

    private static String source(OrdRefundExecution row) {
        return row.getOriginPaymentNo() == null ? row.getAfterSaleNo() : row.getOriginPaymentNo();
    }

    private static String digest(String value) {
        try {
            byte[] bytes = MessageDigest.getInstance("SHA-256").digest(value.getBytes(StandardCharsets.UTF_8));
            return HexFormat.of().formatHex(bytes);
        } catch (NoSuchAlgorithmException unavailable) {
            throw new IllegalStateException(unavailable);
        }
    }

    private static void require(boolean valid) {
        if (!valid) {
            throw BizException.of(ErrorCode.CONFLICT);
        }
    }

    private record Frozen(String executionNo, String afterSaleNo, String subOrderNo, String orderNo,
                          String tenantNo, String originPaymentNo, String payChannel, Long amountMinor,
                          String currency, String planJson, String stockNeedsJson, String stockAuthority,
                          Boolean restoreStock, Boolean restockApproved, Boolean zeroLiability,
                          String reason, Integer closureVersion, Boolean returnCoupon) {
        static Frozen from(OrdRefundExecution row) {
            return new Frozen(row.getExecutionNo(), row.getAfterSaleNo(), row.getSubOrderNo(),
                    row.getOrderNo(), row.getTenantNo(), row.getOriginPaymentNo(), row.getPayChannel(),
                    row.getAmountMinor(), row.getCurrency(), row.getPlanJson(), row.getStockNeedsJson(),
                    row.getStockAuthority(), row.getRestoreStock(), row.getRestockApproved(),
                    row.getZeroLiability(), row.getReason(), row.getClosureVersion(), row.getReturnCoupon());
        }

        void apply(OrdRefundExecution row) {
            row.setOrderNo(orderNo);
            row.setTenantNo(tenantNo);
            row.setOriginPaymentNo(originPaymentNo);
            row.setPayChannel(payChannel);
            row.setPlanJson(planJson);
            row.setStockNeedsJson(stockNeedsJson);
            row.setStockAuthority(stockAuthority);
            row.setRestoreStock(restoreStock);
            row.setRestockApproved(restockApproved);
            row.setZeroLiability(zeroLiability);
            row.setReason(reason);
            row.setClosureVersion(closureVersion);
            row.setReturnCoupon(returnCoupon);
        }
    }

    private record Progress(long version, String status, String phase, String paymentNo, long nextAttemptAt,
                            String resourceStatus, String resourceErrorCode, String closureStatus,
                            Long succeededAt) {
        static Progress from(OrdRefundExecution row, long version) {
            return new Progress(version, row.getStatus(), row.getPhase(), row.getPaymentNo(),
                    row.getNextAttemptAt(), row.getResourceStatus(), row.getResourceErrorCode(),
                    row.getClosureStatus(), row.getSucceededAt());
        }

        void apply(OrdRefundExecution row) {
            row.setVersion(version);
            row.setStatus(status);
            row.setPhase(phase);
            row.setPaymentNo(paymentNo);
            row.setNextAttemptAt(nextAttemptAt);
            row.setResourceStatus(resourceStatus);
            row.setResourceErrorCode(resourceErrorCode);
            row.setClosureStatus(closureStatus);
            row.setSucceededAt(succeededAt);
        }
    }
}
