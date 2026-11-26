package ai.neargo.shop.invoice.service.impl;

import ai.neargo.shop.common.config.ModuleDataSources;
import ai.neargo.shop.common.BizException;
import ai.neargo.shop.common.ErrorCode;
import ai.neargo.shop.invoice.entity.IvcAllowance;
import ai.neargo.shop.invoice.entity.IvcInvoice;
import ai.neargo.shop.invoice.entity.IvcTransmission;
import ai.neargo.shop.invoice.entity.IvcTransmissionAttempt;
import ai.neargo.shop.invoice.mapper.InvoiceMappers.AllowanceMapper;
import ai.neargo.shop.invoice.mapper.InvoiceMappers.InvoiceMapper;
import ai.neargo.shop.invoice.mapper.InvoiceMappers.TransmissionMapper;
import ai.neargo.shop.invoice.mapper.InvoiceTransmissionAuditMapper;
import ai.neargo.shop.invoice.service.InvoiceTransmissionAuditService;
import ai.neargo.shop.print.api.PrintHashes;
import ai.neargo.shop.spi.invoice.InvoicePort.Scope;
import com.baomidou.mybatisplus.core.conditions.query.QueryWrapper;
import com.baomidou.mybatisplus.core.conditions.update.UpdateWrapper;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.UUID;
import java.util.function.BooleanSupplier;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.support.TransactionTemplate;

/** Claim journal writes commit in the main data source before a supplier is called. */
@Service
public class InvoiceTransmissionJournal implements InvoiceTransmissionAuditService {
    static final int STALE_MINUTES = 10;
    private static final String STARTED = "STARTED";
    private static final String FINISHED = "FINISHED";
    private static final String SENDING = "SENDING";

    private final InvoiceTransmissionAuditMapper audit;
    private final TransmissionMapper transmissions;
    private final InvoiceMapper invoices;
    private final AllowanceMapper allowances;
    private final TransactionTemplate transactions;

    InvoiceTransmissionJournal(InvoiceTransmissionAuditMapper audit, TransmissionMapper transmissions,
            InvoiceMapper invoices, AllowanceMapper allowances,
            @Qualifier(ModuleDataSources.INVOICE_TX_MANAGER) PlatformTransactionManager manager) {
        this.audit = audit;
        this.transmissions = transmissions;
        this.invoices = invoices;
        this.allowances = allowances;
        transactions = new TransactionTemplate(manager);
        transactions.setPropagationBehavior(TransactionDefinition.PROPAGATION_REQUIRES_NEW);
    }

    @Override
    public List<Attempt> list(Scope scope, String invoiceNo) {
        var invoice = invoices.selectOne(new QueryWrapper<IvcInvoice>().eq("tenant_no", scope.tenantNo())
                .eq("entity_no", scope.entityNo()).eq("store_no", scope.storeNo()).eq("invoice_no", invoiceNo));
        if (invoice == null) throw BizException.of(ErrorCode.INVOICE_NOT_FOUND);
        var refs = new ArrayList<String>();
        refs.add(invoiceNo);
        allowances.selectList(new QueryWrapper<IvcAllowance>().eq("tenant_no", scope.tenantNo())
                        .eq("entity_no", scope.entityNo()).eq("invoice_no", invoiceNo))
                .forEach(row -> refs.add(row.getAllowanceNo()));
        var rows = audit.selectList(new QueryWrapper<IvcTransmissionAttempt>().eq("tenant_no", scope.tenantNo())
                .eq("entity_no", scope.entityNo()).eq("store_no", scope.storeNo()).in("ref_no", refs)
                .orderByAsc("id"));
        var finished = new HashMap<String, IvcTransmissionAttempt>();
        for (var row : rows) {
            if (FINISHED.equals(row.getPhase())) finished.put(row.getAttemptNo(), row);
        }
        return rows.stream().filter(row -> STARTED.equals(row.getPhase()))
                .map(row -> view(row, finished.get(row.getAttemptNo()))).toList();
    }

    IvcTransmissionAttempt claim(IvcTransmission task, String actorNo, Long accountRevision, LocalDateTime now) {
        return transactions.execute(status -> {
            long index = Math.max(task.getAttemptSequence(), task.getAttempts()) + 1;
            var update = current(task).set("status", SENDING).set("updated_at", now).set("attempt_sequence", index);
            if (SENDING.equals(task.getStatus())) update.le("updated_at", now.minusMinutes(STALE_MINUTES));
            if (transmissions.update(null, update) != 1) return null;
            finishDisplaced(task, "STALE_CLAIM", now);
            var started = start(task, task.getMessageType(), task.getRefNo(), actorNo, accountRevision, index,
                    PrintHashes.sha256(task.getPayload()), now);
            audit.insert(started);
            return started;
        });
    }

    void markHeld(IvcTransmission task, LocalDateTime now) {
        transactions.executeWithoutResult(status -> {
            var update = current(task).set("status", "HELD").set("updated_at", now);
            if (SENDING.equals(task.getStatus())) update.le("updated_at", now.minusMinutes(STALE_MINUTES));
            if (transmissions.update(null, update) == 1) finishDisplaced(task, "ACCOUNT_HELD", now);
        });
    }

    boolean complete(IvcTransmissionAttempt claim, String outcome, String safeCode, LocalDateTime now,
            BooleanSupplier updateTask) {
        return Boolean.TRUE.equals(transactions.execute(status -> {
            if (!updateTask.getAsBoolean()) return false;
            finish(claim, outcome, safeCode, now);
            return true;
        }));
    }

    boolean reconcile(IvcTransmission task, String gatewayRef, LocalDateTime now) {
        return Boolean.TRUE.equals(transactions.execute(status -> {
            var update = current(task).ne("status", "SENT").set("status", "SENT").set("gateway_ref", gatewayRef)
                    .set("last_error", null).set("deadline_level", 2).set("updated_at", now);
            if (transmissions.update(null, update) != 1) return false;
            finishDisplaced(task, "RECONCILED_BY_QUERY", now);
            return true;
        }));
    }

    IvcTransmissionAttempt startQuery(IvcTransmission task, String operation, String invoiceNo, String actorNo,
            Long accountRevision, LocalDateTime now) {
        return transactions.execute(status -> {
            var started = start(task, operation, invoiceNo, actorNo, accountRevision, null, null, now);
            audit.insert(started);
            return started;
        });
    }

    void finishQuery(IvcTransmissionAttempt query, String outcome, String safeCode, LocalDateTime now) {
        transactions.executeWithoutResult(status -> finish(query, outcome, safeCode, now));
    }

    private static UpdateWrapper<IvcTransmission> current(IvcTransmission task) {
        return new UpdateWrapper<IvcTransmission>().eq("id", task.getId()).eq("status", task.getStatus())
                .eq("attempt_sequence", task.getAttemptSequence()).eq("attempts", task.getAttempts());
    }

    private void finishDisplaced(IvcTransmission task, String safeCode, LocalDateTime now) {
        if (!SENDING.equals(task.getStatus()) || task.getAttemptSequence() == 0) return;
        var started = audit.selectOne(new QueryWrapper<IvcTransmissionAttempt>().eq("tenant_no", task.getTenantNo())
                .eq("transmission_no", task.getTransmissionNo()).eq("attempt_index", task.getAttemptSequence())
                .eq("phase", STARTED));
        if (started != null) finish(started, "UNKNOWN", safeCode, now);
    }

    private static IvcTransmissionAttempt start(IvcTransmission task, String operation, String refNo, String actorNo,
            Long accountRevision, Long index, String payloadHash, LocalDateTime at) {
        var row = new IvcTransmissionAttempt();
        row.setTenantNo(task.getTenantNo());
        row.setEntityNo(task.getEntityNo());
        row.setStoreNo(task.getStoreNo());
        row.setAttemptNo(UUID.randomUUID().toString());
        row.setTransmissionNo(task.getTransmissionNo());
        row.setAttemptIndex(index);
        row.setOperation(operation);
        row.setRefNo(refNo);
        row.setGatewayCode(task.getGatewayCode());
        row.setChannelAccountNo(task.getChannelAccountNo());
        row.setAccountRevision(accountRevision);
        row.setActorNo(actorNo);
        row.setPhase(STARTED);
        row.setOutcome(STARTED);
        row.setPayloadHash(payloadHash);
        row.setOccurredAt(at);
        return row;
    }

    private void finish(IvcTransmissionAttempt started, String outcome, String safeCode, LocalDateTime now) {
        var row = new IvcTransmissionAttempt();
        row.setTenantNo(started.getTenantNo());
        row.setEntityNo(started.getEntityNo());
        row.setStoreNo(started.getStoreNo());
        row.setAttemptNo(started.getAttemptNo());
        row.setTransmissionNo(started.getTransmissionNo());
        row.setAttemptIndex(started.getAttemptIndex());
        row.setOperation(started.getOperation());
        row.setRefNo(started.getRefNo());
        row.setGatewayCode(started.getGatewayCode());
        row.setChannelAccountNo(started.getChannelAccountNo());
        row.setAccountRevision(started.getAccountRevision());
        row.setActorNo(started.getActorNo());
        row.setPhase(FINISHED);
        row.setOutcome(outcome);
        row.setSafeCode(safeCode);
        row.setPayloadHash(started.getPayloadHash());
        row.setOccurredAt(now);
        audit.insert(row);
    }

    private static Attempt view(IvcTransmissionAttempt started, IvcTransmissionAttempt finished) {
        return new Attempt(started.getAttemptNo(), started.getTransmissionNo(), started.getAttemptIndex(),
                started.getOperation(), started.getGatewayCode(), started.getChannelAccountNo(), started.getActorNo(),
                finished == null ? STARTED : finished.getOutcome(), finished == null ? null : finished.getSafeCode(),
                started.getPayloadHash(), started.getOccurredAt(), finished == null ? null : finished.getOccurredAt(),
                started.getAccountRevision());
    }
}
