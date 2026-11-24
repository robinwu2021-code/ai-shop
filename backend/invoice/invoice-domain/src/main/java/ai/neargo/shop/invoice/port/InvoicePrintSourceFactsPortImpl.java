package ai.neargo.shop.invoice.port;

import ai.neargo.shop.invoice.entity.IvcAllowance;
import ai.neargo.shop.invoice.entity.IvcInvoice;
import ai.neargo.shop.invoice.mapper.InvoiceMappers.AllowanceMapper;
import ai.neargo.shop.invoice.mapper.InvoiceMappers.InvoiceMapper;
import ai.neargo.shop.print.api.FrozenJson;
import ai.neargo.shop.print.api.GuardDecision;
import ai.neargo.shop.print.api.PrintExecutionGuard;
import ai.neargo.shop.print.api.PrintFact;
import ai.neargo.shop.print.api.PrintFactCursor;
import ai.neargo.shop.print.api.PrintFactPage;
import ai.neargo.shop.print.api.PrintFactRef;
import ai.neargo.shop.print.api.PrintFailure;
import ai.neargo.shop.print.api.PrintScope;
import ai.neargo.shop.print.api.PrintSourceReference;
import ai.neargo.shop.print.api.Printability;
import ai.neargo.shop.spi.printing.PrintSourceFactsPort;
import com.baomidou.mybatisplus.core.conditions.query.QueryWrapper;
import java.time.Instant;
import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.Set;
import org.springframework.stereotype.Component;

/**
 * 发票的三种打印件交给共享打印中心（TDD-发票模块 §9）：
 *
 * <ul>
 *   <li>{@code INVOICE / INVOICE_PROOF} 电子发票证明联 —— 法定凭证，只在应印时（未存载具、未捐赠）可出；正本只出一次，再出走补印；
 *   <li>{@code INVOICE / INVOICE_DETAIL} 交易明细 —— 每张发票都有；存载具 / 捐赠时它就是顾客手上的收据；
 *   <li>{@code INVOICE_ALLOWANCE / INVOICE_ALLOWANCE} 折让证明单 —— 每笔折让一张，留买受人签收。
 * </ul>
 *
 * <p>事实都是开立 / 折让当下冻结的快照（{@code proof_snapshot} / {@code detail_snapshot} / {@code print_snapshot}），
 * 这里只读不重算。已作废的发票：证明联与明细都不再出纸。
 */
@Component
public class InvoicePrintSourceFactsPortImpl implements PrintSourceFactsPort {

    public static final String TYPE = "INVOICE";
    public static final String SCENE = "INVOICE_PROOF";
    public static final String DETAIL_SCENE = "INVOICE_DETAIL";
    public static final String ALLOWANCE_TYPE = "INVOICE_ALLOWANCE";
    public static final String ALLOWANCE_SCENE = "INVOICE_ALLOWANCE";

    private final InvoiceMapper invoices;
    private final AllowanceMapper allowances;

    public InvoicePrintSourceFactsPortImpl(InvoiceMapper invoices, AllowanceMapper allowances) {
        this.invoices = invoices;
        this.allowances = allowances;
    }

    @Override
    public Set<String> sourceTypes() {
        return Set.of(TYPE, ALLOWANCE_TYPE);
    }

    @Override
    public Set<String> scenes() {
        return Set.of(SCENE, DETAIL_SCENE, ALLOWANCE_SCENE);
    }

    /** 证明联的事实身份；开立后发 {@code BusinessPrintFactReady} 也用它。 */
    public static PrintFactRef ref(IvcInvoice inv) {
        return new PrintFactRef(inv.getTenantNo(), inv.getEntityNo(), inv.getStoreNo(), TYPE, inv.getInvoiceNo(),
                "1", SCENE, inv.getProofSnapshotHash());
    }

    public static PrintFactRef detailRef(IvcInvoice inv) {
        return new PrintFactRef(inv.getTenantNo(), inv.getEntityNo(), inv.getStoreNo(), TYPE, inv.getInvoiceNo(),
                "1", DETAIL_SCENE, inv.getDetailSnapshotHash());
    }

    public static PrintFactRef ref(IvcInvoice inv, IvcAllowance a) {
        return new PrintFactRef(inv.getTenantNo(), inv.getEntityNo(), inv.getStoreNo(), ALLOWANCE_TYPE,
                a.getAllowanceNo(), "1", ALLOWANCE_SCENE, a.getPrintSnapshotHash());
    }

    @Override
    public List<PrintFactRef> referencesByOrder(PrintScope scope, String orderNo) {
        var out = new ArrayList<PrintFactRef>();
        for (var inv : invoices.selectList(scoped(scope).and(w -> w.eq("invoice_no", orderNo).or()
                .eq("source_no", orderNo).or().eq("fiscal_number", orderNo)).orderByAsc("id").last("LIMIT 51"))) {
            out.addAll(invoiceRefs(inv));
            for (var a : allowancesOf(inv)) out.add(ref(inv, a));
        }
        return out;
    }

    @Override
    public PrintFactRef resolveReference(PrintSourceReference r, PrintScope scope) {
        if (ALLOWANCE_TYPE.equals(r.type()) && ALLOWANCE_SCENE.equals(r.scene())) {
            var a = allowance(scope, r.factNo() == null ? r.no() : r.factNo());
            var inv = a == null ? null : invoice(scope, a.getInvoiceNo());
            if (inv == null || !(Objects.equals(r.no(), a.getAllowanceNo())
                    || Objects.equals(r.no(), inv.getInvoiceNo())))
                throw new PrintFailure("print.factNotFound");
            return ref(inv, a);
        }
        if (!TYPE.equals(r.type()) || !(SCENE.equals(r.scene()) || DETAIL_SCENE.equals(r.scene())))
            throw new PrintFailure("print.sourceMismatch");
        var inv = invoice(scope, r.factNo() == null ? r.no() : r.factNo());
        if (inv == null || !(Objects.equals(r.no(), inv.getInvoiceNo()) || Objects.equals(r.no(), inv.getSourceNo())))
            throw new PrintFailure("print.factNotFound");
        return SCENE.equals(r.scene()) ? ref(inv) : detailRef(inv);
    }

    @Override
    public PrintFact snapshot(PrintFactRef r) {
        var scope = new PrintScope(r.tenantNo(), r.entityNo(), r.storeNo());
        if (ALLOWANCE_TYPE.equals(r.sourceType())) {
            var a = allowance(scope, r.factNo());
            var inv = a == null ? null : invoice(scope, a.getInvoiceNo());
            if (inv == null || a.getPrintSnapshot() == null || !ref(inv, a).equals(r))
                throw new PrintFailure("print.factNotFound");
            return fact(r, inv, a.getAllowanceNo(), a.getCreatedAt(), a.getPrintSnapshot(), a.getPrintSnapshotHash(),
                    null);
        }
        var inv = invoice(scope, r.factNo());
        if (inv == null) throw new PrintFailure("print.factNotFound");
        if (DETAIL_SCENE.equals(r.scene())) {
            if (inv.getDetailSnapshot() == null || !detailRef(inv).equals(r))
                throw new PrintFailure("print.factNotFound");
            return fact(r, inv, inv.getInvoiceNo(), inv.getIssuedAt(), inv.getDetailSnapshot(),
                    inv.getDetailSnapshotHash(), null);
        }
        if (!ref(inv).equals(r)) throw new PrintFailure("print.factNotFound");
        String block = "N".equals(inv.getPrintMark()) ? "invoice.proofNotPrintable"
                : !inv.getProofSnapshot().contains("\"qrLeft\"") ? "invoice.qrKeyMissing" : null;
        return fact(r, inv, inv.getInvoiceNo(), inv.getIssuedAt(), inv.getProofSnapshot(), inv.getProofSnapshotHash(),
                block);
    }

    /**
     * 来源是子单的发票，打印件挂到那张子单上：打印中心按订单号查作业时连同主单的支付小票一起列出，
     * 门店在订单的打印记录里就能看到它的发票打印件。
     */
    private static PrintFact fact(PrintFactRef r, IvcInvoice inv, String instance, LocalDateTime at, String snapshot,
            String hash, String block) {
        String trigger = ALLOWANCE_TYPE.equals(r.sourceType()) ? "INVOICE_ALLOWANCE_ISSUED" : "INVOICE_ISSUED";
        String subOrderNo = "SUB_ORDER".equals(inv.getSourceType()) ? inv.getSourceNo() : null;
        return new PrintFact(r, null, subOrderNo, trigger, instance, at.toInstant(ZoneOffset.UTC), 1,
                new FrozenJson(snapshot, hash), block == null ? Printability.READY : Printability.BLOCKED, block,
                block == null ? List.of() : List.of(block));
    }

    @Override
    public PrintFactPage scan(PrintFactCursor c, int limit) {
        if (!sourceTypes().contains(c.sourceKind()) || limit < 1 || limit > 500 || c.from() == null
                || c.to() == null || c.from().isAfter(c.to())) throw new PrintFailure("print.invalidScan");
        var refs = new ArrayList<PrintFactRef>();
        Instant lastAt = null;
        String lastNo = null;
        int found;
        if (ALLOWANCE_TYPE.equals(c.sourceKind())) {
            var q = new QueryWrapper<IvcAllowance>().eq("tenant_no", c.scope().tenantNo())
                    .eq("entity_no", c.scope().entityNo()).eq("store_no", c.scope().storeNo())
                    .ge("created_at", utc(c.from())).le("created_at", utc(c.to()));
            if (c.watermarkAt() != null)
                q.and(w -> w.gt("created_at", utc(c.watermarkAt()))
                        .or(x -> x.eq("created_at", utc(c.watermarkAt())).gt("allowance_no", c.watermarkNo())));
            var rows = allowances.selectList(q.orderByAsc("created_at", "allowance_no").last("LIMIT " + limit));
            for (var a : rows) {
                var inv = invoice(c.scope(), a.getInvoiceNo());
                if (inv != null && a.getPrintSnapshot() != null) refs.add(ref(inv, a));
                lastAt = a.getCreatedAt().toInstant(ZoneOffset.UTC);
                lastNo = a.getAllowanceNo();
            }
            found = rows.size();
        } else {
            var q = scoped(c.scope()).ge("created_at", utc(c.from())).le("created_at", utc(c.to()));
            if (c.watermarkAt() != null)
                q.and(w -> w.gt("created_at", utc(c.watermarkAt()))
                        .or(x -> x.eq("created_at", utc(c.watermarkAt())).gt("invoice_no", c.watermarkNo())));
            var rows = invoices.selectList(q.orderByAsc("created_at", "invoice_no").last("LIMIT " + limit));
            for (var inv : rows) {
                refs.addAll(invoiceRefs(inv));
                lastAt = inv.getCreatedAt().toInstant(ZoneOffset.UTC);
                lastNo = inv.getInvoiceNo();
            }
            found = rows.size();
        }
        var next = lastAt == null ? c
                : new PrintFactCursor(c.scope(), c.sourceKind(), c.from(), c.to(), lastAt, lastNo);
        return new PrintFactPage(refs, next, found == limit);
    }

    @Override
    public PrintExecutionGuard executionGuard(PrintFactRef r) {
        var fact = snapshot(r);
        if (TYPE.equals(r.sourceType())) {
            var inv = invoice(new PrintScope(r.tenantNo(), r.entityNo(), r.storeNo()), r.factNo());
            if ("REVOKING".equals(inv.getStatus()))
                return new PrintExecutionGuard(false, GuardDecision.HOLD_UNKNOWN, r.bizRevision(),
                        false, "INVOICE_REVOKING");
            if (Set.of("VOIDED", "REVOKED").contains(inv.getStatus()))
                return new PrintExecutionGuard(false, GuardDecision.CANCEL_UNSUBMITTED_EXECUTION, r.bizRevision(),
                        true, "INVOICE_" + inv.getStatus());
        }
        if (ALLOWANCE_TYPE.equals(r.sourceType())) {
            var a = allowance(new PrintScope(r.tenantNo(), r.entityNo(), r.storeNo()), r.factNo());
            if (a != null && "VOIDED".equals(a.getStatus()))
                return new PrintExecutionGuard(false, GuardDecision.CANCEL_UNSUBMITTED_EXECUTION, r.bizRevision(),
                        true, "ALLOWANCE_VOIDED");
        }
        boolean ready = fact.printability() == Printability.READY;
        return new PrintExecutionGuard(ready,
                ready ? GuardDecision.ALLOW_HISTORICAL_RECEIPT : GuardDecision.HOLD_UNKNOWN, r.bizRevision(), false,
                "IMMUTABLE_INVOICE_DOCUMENT");
    }

    /** 一张发票能出的件：应印时有证明联；有明细快照就有交易明细。 */
    private static List<PrintFactRef> invoiceRefs(IvcInvoice inv) {
        var out = new ArrayList<PrintFactRef>();
        if ("Y".equals(inv.getPrintMark())) out.add(ref(inv));
        if (inv.getDetailSnapshot() != null) out.add(detailRef(inv));
        return out;
    }

    private List<IvcAllowance> allowancesOf(IvcInvoice inv) {
        return allowances.selectList(new QueryWrapper<IvcAllowance>().eq("tenant_no", inv.getTenantNo())
                .eq("invoice_no", inv.getInvoiceNo()).isNotNull("print_snapshot").orderByAsc("id"));
    }

    private IvcInvoice invoice(PrintScope scope, String invoiceNo) {
        return invoices.selectOne(scoped(scope).eq("invoice_no", invoiceNo));
    }

    private IvcAllowance allowance(PrintScope scope, String allowanceNo) {
        return allowances.selectOne(new QueryWrapper<IvcAllowance>().eq("tenant_no", scope.tenantNo())
                .eq("entity_no", scope.entityNo()).eq("store_no", scope.storeNo()).eq("allowance_no", allowanceNo));
    }

    private static QueryWrapper<IvcInvoice> scoped(PrintScope s) {
        return new QueryWrapper<IvcInvoice>().eq("tenant_no", s.tenantNo()).eq("entity_no", s.entityNo())
                .eq("store_no", s.storeNo());
    }

    private static LocalDateTime utc(Instant at) {
        return LocalDateTime.ofInstant(at, ZoneOffset.UTC);
    }
}
