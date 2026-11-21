package ai.neargo.shop.invoice.port;

import ai.neargo.shop.invoice.entity.IvcInvoice;
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
import java.util.List;
import java.util.Objects;
import java.util.Set;
import org.springframework.stereotype.Component;

/**
 * 证明联的打印来源（打印中心场景 {@code INVOICE_PROOF}，来源类型 {@code INVOICE}）。
 *
 * <p>事实就是发票本身：开立时已把证明联内容冻结在 {@code ivc_invoice.proof_snapshot}，这里只读不重算。
 * 存载具 / 捐赠（{@code print_mark=N}）或缺 QR 密钥时返回 BLOCKED 并给出原因，不出一张不合规的证明联；
 * 已作废的发票不允许再出纸。补印走打印中心的 REPRINT，版面由打印中心标「补印」并留原因与操作人。
 */
@Component
public class InvoicePrintSourceFactsPortImpl implements PrintSourceFactsPort {

    public static final String TYPE = "INVOICE";
    public static final String SCENE = "INVOICE_PROOF";

    private final InvoiceMapper invoices;

    public InvoicePrintSourceFactsPortImpl(InvoiceMapper invoices) {
        this.invoices = invoices;
    }

    @Override
    public Set<String> sourceTypes() {
        return Set.of(TYPE);
    }

    @Override
    public Set<String> scenes() {
        return Set.of(SCENE);
    }

    @Override
    public List<PrintFactRef> referencesByOrder(PrintScope scope, String orderNo) {
        return invoices.selectList(scoped(scope).and(w -> w.eq("invoice_no", orderNo).or().eq("source_no", orderNo))
                .orderByAsc("id").last("LIMIT 101")).stream().map(InvoicePrintSourceFactsPortImpl::ref).toList();
    }

    @Override
    public PrintFactRef resolveReference(PrintSourceReference r, PrintScope scope) {
        if (!TYPE.equals(r.type()) || !SCENE.equals(r.scene())) throw new PrintFailure("print.sourceMismatch");
        var inv = invoices.selectOne(scoped(scope).eq("invoice_no", r.factNo() == null ? r.no() : r.factNo()));
        if (inv == null || !(Objects.equals(r.no(), inv.getInvoiceNo()) || Objects.equals(r.no(), inv.getSourceNo())))
            throw new PrintFailure("print.factNotFound");
        return ref(inv);
    }

    @Override
    public PrintFact snapshot(PrintFactRef r) {
        var inv = invoices.selectOne(scoped(new PrintScope(r.tenantNo(), r.entityNo(), r.storeNo()))
                .eq("invoice_no", r.factNo()));
        if (inv == null || !ref(inv).equals(r)) throw new PrintFailure("print.factNotFound");
        String block = "N".equals(inv.getPrintMark()) ? "invoice.proofNotPrintable"
                : !inv.getProofSnapshot().contains("\"qrLeft\"") ? "invoice.qrKeyMissing" : null;
        return new PrintFact(r, null, null, "INVOICE_ISSUED", inv.getInvoiceNo(),
                inv.getIssuedAt().toInstant(ZoneOffset.UTC), 1,
                new FrozenJson(inv.getProofSnapshot(), inv.getProofSnapshotHash()),
                block == null ? Printability.READY : Printability.BLOCKED, block,
                block == null ? List.of() : List.of(block));
    }

    @Override
    public PrintFactPage scan(PrintFactCursor c, int limit) {
        if (!TYPE.equals(c.sourceKind()) || limit < 1 || limit > 500 || c.from() == null || c.to() == null
                || c.from().isAfter(c.to())) throw new PrintFailure("print.invalidScan");
        var q = scoped(c.scope()).eq("print_mark", "Y")
                .ge("created_at", utc(c.from())).le("created_at", utc(c.to()));
        if (c.watermarkAt() != null)
            q.and(w -> w.gt("created_at", utc(c.watermarkAt()))
                    .or(x -> x.eq("created_at", utc(c.watermarkAt())).gt("invoice_no", c.watermarkNo())));
        var found = invoices.selectList(q.orderByAsc("created_at", "invoice_no").last("LIMIT " + limit));
        var last = found.isEmpty() ? null : found.getLast();
        var next = last == null ? c : new PrintFactCursor(c.scope(), c.sourceKind(), c.from(), c.to(),
                last.getCreatedAt().toInstant(ZoneOffset.UTC), last.getInvoiceNo());
        return new PrintFactPage(found.stream().map(InvoicePrintSourceFactsPortImpl::ref).toList(), next,
                found.size() == limit);
    }

    @Override
    public PrintExecutionGuard executionGuard(PrintFactRef r) {
        var fact = snapshot(r);
        var inv = invoices.selectOne(scoped(new PrintScope(r.tenantNo(), r.entityNo(), r.storeNo()))
                .eq("invoice_no", r.factNo()));
        if ("VOIDED".equals(inv.getStatus()))
            return new PrintExecutionGuard(false, GuardDecision.CANCEL_UNSUBMITTED_EXECUTION, r.bizRevision(), true,
                    "INVOICE_VOIDED");
        boolean ready = fact.printability() == Printability.READY;
        return new PrintExecutionGuard(ready, ready ? GuardDecision.ALLOW_HISTORICAL_RECEIPT : GuardDecision.HOLD_UNKNOWN,
                r.bizRevision(), false, "IMMUTABLE_INVOICE_PROOF");
    }

    private static QueryWrapper<IvcInvoice> scoped(PrintScope s) {
        return new QueryWrapper<IvcInvoice>().eq("tenant_no", s.tenantNo()).eq("entity_no", s.entityNo())
                .eq("store_no", s.storeNo());
    }

    /** 证明联打印事实的身份；开立后发 {@code BusinessPrintFactReady} 也用它。 */
    public static PrintFactRef ref(IvcInvoice inv) {
        return new PrintFactRef(inv.getTenantNo(), inv.getEntityNo(), inv.getStoreNo(), TYPE, inv.getInvoiceNo(),
                "1", SCENE, inv.getProofSnapshotHash());
    }

    private static LocalDateTime utc(Instant at) {
        return LocalDateTime.ofInstant(at, ZoneOffset.UTC);
    }
}
