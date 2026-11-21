package ai.neargo.shop.invoice.service.impl;

import ai.neargo.secret.api.SecretNamespace;
import ai.neargo.secret.api.SecretRef;
import ai.neargo.secret.api.SecretVault;
import ai.neargo.shop.common.BizException;
import ai.neargo.shop.common.BizKey;
import ai.neargo.shop.common.ErrorCode;
import ai.neargo.shop.common.PageData;
import ai.neargo.shop.event.OutboxEventBus;
import ai.neargo.shop.invoice.dto.InvoiceDtos.AllowanceVO;
import ai.neargo.shop.invoice.dto.InvoiceDtos.InvoiceLineVO;
import ai.neargo.shop.invoice.dto.InvoiceDtos.InvoiceQuery;
import ai.neargo.shop.invoice.dto.InvoiceDtos.InvoiceVO;
import ai.neargo.shop.invoice.entity.IvcAllowance;
import ai.neargo.shop.invoice.entity.IvcInvoice;
import ai.neargo.shop.invoice.entity.IvcInvoiceLine;
import ai.neargo.shop.invoice.entity.IvcRegimeProfile;
import ai.neargo.shop.invoice.entity.IvcTransmission;
import ai.neargo.shop.invoice.mapper.InvoiceMappers.AllowanceMapper;
import ai.neargo.shop.invoice.mapper.InvoiceMappers.InvoiceLineMapper;
import ai.neargo.shop.invoice.mapper.InvoiceMappers.InvoiceMapper;
import ai.neargo.shop.invoice.mapper.InvoiceMappers.NumberRangeMapper;
import ai.neargo.shop.invoice.mapper.InvoiceMappers.RegimeProfileMapper;
import ai.neargo.shop.invoice.mapper.InvoiceMappers.TransmissionMapper;
import ai.neargo.shop.invoice.port.InvoicePrintSourceFactsPortImpl;
import ai.neargo.shop.invoice.regime.InvoiceRegime;
import ai.neargo.shop.invoice.regime.InvoiceRegimes;
import ai.neargo.shop.invoice.service.InvoiceService;
import ai.neargo.shop.print.api.BusinessPrintFactReady;
import ai.neargo.shop.print.api.PrintHashes;
import ai.neargo.shop.spi.invoice.InvoicePort.Buyer;
import ai.neargo.shop.spi.invoice.InvoicePort.InvoiceReceipt;
import ai.neargo.shop.spi.invoice.InvoicePort.Scope;
import ai.neargo.shop.spi.invoice.InvoiceSourcePort;
import com.baomidou.mybatisplus.core.conditions.query.QueryWrapper;
import com.baomidou.mybatisplus.core.conditions.update.UpdateWrapper;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import java.time.Instant;
import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.Currency;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.TreeMap;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import tools.jackson.databind.ObjectMapper;

/**
 * 开立主流程（TDD-发票模块 §2.5）。与税制无关：编号、金额口径、证明联、报文全部问 {@link InvoiceRegime}。
 *
 * <p><b>不重号、不跳号的依据只有一条</b>：号段行 {@code FOR UPDATE} 与发票、明细、上传任务在同一事务里提交。
 * 任何一步失败整体回滚，号段的 {@code next_no} 一起退回。所以本类在取号之后不捕获异常。
 */
@Service
public class InvoiceServiceImpl implements InvoiceService {

    /** QR 加密密钥在 secret 模块里的 owner 类型（命名空间 PLATFORM，与发票同进程）。 */
    public static final String QR_KEY_OWNER = "INVOICE_QR_KEY";

    private final RegimeProfileMapper profiles;
    private final NumberRangeMapper ranges;
    private final InvoiceMapper invoices;
    private final InvoiceLineMapper lines;
    private final AllowanceMapper allowances;
    private final TransmissionMapper transmissions;
    private final InvoiceRegimes regimes;
    private final Map<String, InvoiceSourcePort> sources = new HashMap<>();
    private final ObjectProvider<SecretVault> vault;
    private final OutboxEventBus events;
    private final ObjectMapper json;

    public InvoiceServiceImpl(RegimeProfileMapper profiles, NumberRangeMapper ranges, InvoiceMapper invoices,
            InvoiceLineMapper lines, AllowanceMapper allowances, TransmissionMapper transmissions,
            InvoiceRegimes regimes, List<InvoiceSourcePort> sourcePorts, ObjectProvider<SecretVault> vault,
            OutboxEventBus events, ObjectMapper json) {
        this.profiles = profiles;
        this.ranges = ranges;
        this.invoices = invoices;
        this.lines = lines;
        this.allowances = allowances;
        this.transmissions = transmissions;
        this.regimes = regimes;
        for (var port : sourcePorts)
            for (var type : port.sourceTypes())
                if (sources.put(type, port) != null)
                    throw new IllegalStateException("Duplicate invoice source type: " + type);
        this.vault = vault;
        this.events = events;
        this.json = json;
    }

    @Override
    @Transactional
    public InvoiceReceipt issue(Scope scope, String sourceType, String sourceNo, Buyer buyer) {
        var existing = active(scope, sourceType, sourceNo);
        if (existing != null) return receipt(existing);
        var profile = activeProfile(scope);
        var regime = regimes.require(profile.getRegimeCode());
        regime.validateBuyer(buyer, profile);
        var port = sources.get(sourceType);
        if (port == null) throw BizException.of(ErrorCode.INVOICE_SOURCE_NOT_READY);
        var source = port.load(scope, sourceType, sourceNo);
        long sum = 0;
        for (var l : source.lines()) sum = Math.addExact(sum, l.amountMinor());
        if (source.lines().isEmpty() || source.totalMinor() <= 0 || sum != source.totalMinor())
            throw BizException.of(ErrorCode.INVOICE_SOURCE_NOT_READY);
        var amounts = regime.amounts(source.totalMinor(), buyer);

        Instant now = Instant.now();
        var range = ranges.lockUsable(scope.tenantNo(), scope.entityNo(), regime.code(), regime.periodOf(now),
                scope.storeNo());
        if (range == null) throw BizException.of(ErrorCode.INVOICE_NUMBER_EXHAUSTED);
        // 同来源的并发请求都排在同一号段锁后面；拿到锁后用锁定读再看一次，后到的直接回原发票，不取号
        existing = invoices.lockActive(scope.tenantNo(), scope.entityNo(), sourceKey(sourceType, sourceNo));
        if (existing != null) return receipt(existing);
        int number = range.getNextNo();
        ranges.advance(range.getId());

        var inv = new IvcInvoice();
        inv.setInvoiceNo(BizKey.next(BizKey.INVOICE));
        inv.setTenantNo(scope.tenantNo());
        inv.setEntityNo(scope.entityNo());
        inv.setStoreNo(scope.storeNo());
        inv.setRegimeCode(regime.code());
        inv.setIndustryCode(source.industryCode());
        inv.setSourceType(sourceType);
        inv.setSourceNo(sourceNo);
        inv.setActiveSourceKey(sourceKey(sourceType, sourceNo));
        inv.setFiscalNumber(regime.fiscalNumber(range.getTrack(), number));
        inv.setRangeNo(range.getRangeNo());
        inv.setPeriodCode(range.getPeriodCode());
        inv.setRandomCode(regime.randomCode());
        inv.setSellerTaxId(profile.getSellerTaxId());
        inv.setSellerName(profile.getSellerName());
        inv.setIssuedAt(LocalDateTime.ofInstant(now, ZoneOffset.UTC));
        inv.setBuyerType(regime.buyerType(buyer));
        inv.setBuyerTaxId(blankToNull(buyer.taxId()));
        inv.setBuyerName(blankToNull(buyer.name()));
        inv.setCarrierType(blankToNull(buyer.carrierType()));
        inv.setCarrierId(blankToNull(buyer.carrierId()));
        inv.setDonateCode(blankToNull(buyer.donateCode()));
        inv.setPrintMark(regime.printMark(buyer) ? "Y" : "N");
        inv.setCurrency(source.currency());
        inv.setSalesMinor(amounts.salesMinor());
        inv.setTaxMinor(amounts.taxMinor());
        inv.setTotalMinor(amounts.totalMinor());
        inv.setAllowedMinor(0L);
        inv.setTaxType(amounts.taxType());
        inv.setTaxRateBp(amounts.taxRateBp());
        inv.setStatus("ISSUED");
        inv.setCreatedBy(scope.actorNo());
        inv.setCreatedAt(inv.getIssuedAt());
        inv.setUpdatedAt(inv.getIssuedAt());

        var rows = new ArrayList<IvcInvoiceLine>();
        int lineNo = 1;
        for (var l : source.lines()) {
            var row = new IvcInvoiceLine();
            row.setTenantNo(scope.tenantNo());
            row.setInvoiceNo(inv.getInvoiceNo());
            row.setLineNo(lineNo++);
            row.setSourceLineRef(l.sourceLineRef());
            row.setName(l.name());
            row.setQuantity(l.quantity());
            row.setUnit(l.unit());
            row.setUnitPriceMinor(l.unitPriceMinor());
            row.setAmountMinor(l.amountMinor());
            row.setTaxType(amounts.taxType());
            rows.add(row);
        }
        var proof = regime.proof(inv, rows, qrKey(profile));
        inv.setProofSnapshot(printPayload(inv, rows, proof));
        inv.setProofSnapshotHash(PrintHashes.sha256(inv.getProofSnapshot()));
        invoices.insert(inv);
        rows.forEach(lines::insert);
        enqueue(inv, profile, "ISSUE", inv.getInvoiceNo(), regime.message("ISSUE", inv, rows, null));
        if ("Y".equals(inv.getPrintMark()))
            events.publish(new BusinessPrintFactReady(inv.getInvoiceNo(), InvoicePrintSourceFactsPortImpl.ref(inv)));
        return receipt(inv);
    }

    @Override
    public Optional<InvoiceReceipt> activeOf(Scope scope, String sourceType, String sourceNo) {
        return Optional.ofNullable(active(scope, sourceType, sourceNo)).map(this::receipt);
    }

    @Override
    public InvoiceVO get(Scope scope, String invoiceNo) {
        return view(require(scope, invoiceNo), true);
    }

    @Override
    public PageData<InvoiceVO> list(Scope scope, InvoiceQuery query) {
        int page = query.page() == null ? 1 : query.page(), size = query.size() == null ? 20 : query.size();
        var q = new QueryWrapper<IvcInvoice>().eq("tenant_no", scope.tenantNo()).eq("entity_no", scope.entityNo())
                .eq("store_no", scope.storeNo())
                .eq(query.periodCode() != null, "period_code", query.periodCode())
                .eq(query.status() != null, "status", query.status())
                .orderByDesc("issued_at", "id");
        var result = invoices.selectPage(new Page<>(page, size), q);
        return PageData.of(result.getRecords().stream().map(i -> view(i, false)).toList(),
                result.getTotal(), page, size);
    }

    @Override
    @Transactional
    public InvoiceVO voidInvoice(Scope scope, String invoiceNo, String reason) {
        var inv = require(scope, invoiceNo);
        if (!"ISSUED".equals(inv.getStatus()) || inv.getAllowedMinor() > 0)
            throw BizException.of(ErrorCode.INVOICE_STATE_INVALID);
        var regime = regimes.require(inv.getRegimeCode());
        if (!regime.voidable(inv, Instant.now())) throw BizException.of(ErrorCode.INVOICE_VOID_PERIOD_CLOSED);
        var now = LocalDateTime.now(ZoneOffset.UTC);
        int updated = invoices.update(null, new UpdateWrapper<IvcInvoice>().eq("id", inv.getId())
                .eq("status", "ISSUED").eq("allowed_minor", 0)
                .set("status", "VOIDED").set("active_source_key", null).set("void_reason", reason)
                .set("voided_at", now).set("voided_by", scope.actorNo()).set("updated_at", now));
        if (updated != 1) throw BizException.of(ErrorCode.INVOICE_STATE_INVALID);
        inv.setStatus("VOIDED");
        inv.setActiveSourceKey(null);
        inv.setVoidReason(reason);
        inv.setVoidedAt(now);
        enqueue(inv, activeProfile(scope), "VOID", inv.getInvoiceNo(),
                regime.message("VOID", inv, List.of(), null));
        return view(inv, true);
    }

    @Override
    @Transactional
    public InvoiceVO allowance(Scope scope, String invoiceNo, long amountMinor, String reason) {
        var inv = require(scope, invoiceNo);
        if (!"ISSUED".equals(inv.getStatus())) throw BizException.of(ErrorCode.INVOICE_STATE_INVALID);
        var regime = regimes.require(inv.getRegimeCode());
        long tax = regime.allowanceTax(inv, amountMinor);
        if (invoices.addAllowance(inv.getId(), amountMinor) != 1)
            throw BizException.of(ErrorCode.INVOICE_ALLOWANCE_EXCEEDED);
        var a = new IvcAllowance();
        a.setAllowanceNo(BizKey.next(BizKey.INVOICE_ALLOWANCE));
        a.setTenantNo(inv.getTenantNo());
        a.setEntityNo(inv.getEntityNo());
        a.setStoreNo(inv.getStoreNo());
        a.setInvoiceNo(inv.getInvoiceNo());
        a.setAmountMinor(amountMinor);
        a.setTaxMinor(tax);
        a.setReason(reason);
        a.setCreatedBy(scope.actorNo());
        a.setCreatedAt(LocalDateTime.now(ZoneOffset.UTC));
        allowances.insert(a);
        enqueue(inv, activeProfile(scope), "ALLOWANCE", a.getAllowanceNo(),
                regime.message("ALLOWANCE", inv, List.of(), a));
        return view(require(scope, invoiceNo), true);
    }

    private void enqueue(IvcInvoice inv, IvcRegimeProfile profile, String type, String refNo,
            Map<String, Object> message) {
        var t = new IvcTransmission();
        t.setTransmissionNo(BizKey.next(BizKey.INVOICE_TRANSMISSION));
        t.setTenantNo(inv.getTenantNo());
        t.setEntityNo(inv.getEntityNo());
        t.setRegimeCode(inv.getRegimeCode());
        t.setGatewayCode(profile.getGatewayCode());
        t.setMessageType(type);
        t.setRefNo(refNo);
        t.setPayload(json.writeValueAsString(message));
        t.setStatus("PENDING");
        t.setAttempts(0);
        var now = LocalDateTime.now(ZoneOffset.UTC);
        t.setNextAttemptAt(now);
        t.setCreatedAt(now);
        t.setUpdatedAt(now);
        transmissions.insert(t);
    }

    /**
     * 证明联的打印事实快照（打印中心公共字段 + 税制给出的 fields）。按键排序序列化，哈希才稳定。
     * 门店名取开立时冻结的卖方名称：证明联上印的是营业人，不是门店展示名。
     */
    private String printPayload(IvcInvoice inv, List<IvcInvoiceLine> rows, Map<String, Object> proof) {
        var lineItems = new ArrayList<Map<String, Object>>();
        for (var r : rows)
            lineItems.add(Map.of("lineRef", String.valueOf(r.getLineNo()), "title", r.getName(),
                    "quantity", r.getQuantity().stripTrailingZeros().toPlainString(),
                    "lineAmount", money(r.getAmountMinor(), inv.getCurrency())));
        var payload = new TreeMap<String, Object>();
        payload.put("scene", InvoicePrintSourceFactsPortImpl.SCENE);
        payload.put("payloadSchemaVersion", 1);
        payload.put("store", Map.of("name", inv.getSellerName(), "timezone", proof.getOrDefault("timezone", "UTC")));
        payload.put("source", Map.of("type", InvoicePrintSourceFactsPortImpl.TYPE, "no", inv.getInvoiceNo(), "factNo", inv.getInvoiceNo(),
                "revision", "1", "occurredAt", inv.getIssuedAt().toInstant(ZoneOffset.UTC).toString()));
        payload.put("currency", inv.getCurrency());
        var fields = new TreeMap<String, Object>();
        proof.forEach((k, v) -> {
            if (v != null) fields.put(k, v);
        });
        fields.put("totalAmount", money(inv.getTotalMinor(), inv.getCurrency()));
        payload.put("fields", fields);
        payload.put("lines", lineItems);
        return json.writeValueAsString(sorted(payload));
    }

    @SuppressWarnings("unchecked")
    private Object sorted(Object value) {
        if (value instanceof Map<?, ?> m) {
            var out = new TreeMap<String, Object>();
            m.forEach((k, v) -> out.put((String) k, sorted(v)));
            return out;
        }
        if (value instanceof List<?> l) return l.stream().map(this::sorted).toList();
        return value;
    }

    private static Map<String, Object> money(long minor, String currency) {
        return Map.of("minor", Long.toString(minor), "currency", currency, "scale",
                Currency.getInstance(currency).getDefaultFractionDigits());
    }

    private byte[] qrKey(IvcRegimeProfile profile) {
        if (profile.getQrKeyRef() == null) return null;
        var v = vault.getIfAvailable();
        if (v == null) throw BizException.of(ErrorCode.INVOICE_KEY_UNAVAILABLE);
        return v.open(SecretNamespace.PLATFORM, new SecretRef(profile.getQrKeyRef()));
    }

    private IvcRegimeProfile activeProfile(Scope scope) {
        var p = profiles.selectOne(new QueryWrapper<IvcRegimeProfile>().eq("tenant_no", scope.tenantNo())
                .eq("entity_no", scope.entityNo()).eq("status", "ACTIVE").last("LIMIT 1"));
        if (p == null) throw BizException.of(ErrorCode.INVOICE_REGIME_NOT_ENABLED);
        return p;
    }

    private IvcInvoice active(Scope scope, String sourceType, String sourceNo) {
        return invoices.selectOne(new QueryWrapper<IvcInvoice>().eq("tenant_no", scope.tenantNo())
                .eq("entity_no", scope.entityNo()).eq("active_source_key", sourceKey(sourceType, sourceNo)));
    }

    private IvcInvoice require(Scope scope, String invoiceNo) {
        var inv = invoices.selectOne(new QueryWrapper<IvcInvoice>().eq("tenant_no", scope.tenantNo())
                .eq("entity_no", scope.entityNo()).eq("store_no", scope.storeNo()).eq("invoice_no", invoiceNo));
        if (inv == null) throw BizException.of(ErrorCode.INVOICE_NOT_FOUND);
        return inv;
    }

    private InvoiceReceipt receipt(IvcInvoice i) {
        return new InvoiceReceipt(i.getInvoiceNo(), i.getRegimeCode(), i.getFiscalNumber(), i.getPeriodCode(),
                i.getRandomCode(), i.getStatus(), "Y".equals(i.getPrintMark()), i.getCurrency(), i.getTotalMinor());
    }

    private InvoiceVO view(IvcInvoice i, boolean detail) {
        var issue = transmissions.selectOne(new QueryWrapper<IvcTransmission>().eq("tenant_no", i.getTenantNo())
                .eq("message_type", "ISSUE").eq("ref_no", i.getInvoiceNo()));
        List<InvoiceLineVO> lineViews = List.of();
        List<AllowanceVO> allowanceViews = List.of();
        if (detail) {
            lineViews = lines.selectList(new QueryWrapper<IvcInvoiceLine>().eq("tenant_no", i.getTenantNo())
                            .eq("invoice_no", i.getInvoiceNo()).orderByAsc("line_no")).stream()
                    .map(l -> new InvoiceLineVO(l.getLineNo(), l.getName(), l.getQuantity(), l.getUnit(),
                            l.getUnitPriceMinor(), l.getAmountMinor()))
                    .toList();
            allowanceViews = allowances.selectList(new QueryWrapper<IvcAllowance>().eq("tenant_no", i.getTenantNo())
                            .eq("invoice_no", i.getInvoiceNo()).orderByAsc("id")).stream()
                    .map(a -> new AllowanceVO(a.getAllowanceNo(), a.getAmountMinor(), a.getTaxMinor(), a.getReason(),
                            a.getCreatedAt()))
                    .toList();
        }
        return new InvoiceVO(i.getInvoiceNo(), i.getRegimeCode(), i.getIndustryCode(), i.getSourceType(),
                i.getSourceNo(), i.getStoreNo(), i.getFiscalNumber(), i.getPeriodCode(), i.getRandomCode(),
                i.getIssuedAt(), i.getBuyerType(), i.getBuyerTaxId(), i.getCarrierType(), i.getCarrierId(),
                i.getDonateCode(), "Y".equals(i.getPrintMark()), i.getCurrency(), i.getSalesMinor(), i.getTaxMinor(),
                i.getTotalMinor(), i.getAllowedMinor(), i.getStatus(), i.getVoidReason(),
                issue == null ? null : issue.getStatus(), lineViews, allowanceViews);
    }

    static String sourceKey(String sourceType, String sourceNo) {
        return sourceType + ":" + sourceNo;
    }

    private static String blankToNull(String s) {
        return s == null || s.isBlank() ? null : s.trim();
    }
}
