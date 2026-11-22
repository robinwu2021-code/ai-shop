package ai.neargo.shop.invoice.service.impl;

import ai.neargo.secret.api.SecretNamespace;
import ai.neargo.secret.api.SecretRef;
import ai.neargo.secret.api.SecretVault;
import ai.neargo.shop.common.BizException;
import ai.neargo.shop.common.BizKey;
import ai.neargo.shop.common.ErrorCode;
import ai.neargo.shop.common.PageData;
import ai.neargo.shop.event.OutboxEventBus;
import ai.neargo.shop.invoice.dto.InvoiceDtos;
import ai.neargo.shop.invoice.dto.InvoiceDtos.AllowanceVO;
import ai.neargo.shop.invoice.dto.InvoiceDtos.InvoiceLineVO;
import ai.neargo.shop.invoice.dto.InvoiceDtos.InvoiceQuery;
import ai.neargo.shop.invoice.dto.InvoiceDtos.InvoiceVO;
import ai.neargo.shop.invoice.entity.IvcAllowance;
import ai.neargo.shop.invoice.entity.IvcBuyerPreference;
import ai.neargo.shop.invoice.entity.IvcInvoice;
import ai.neargo.shop.invoice.entity.IvcInvoiceLine;
import ai.neargo.shop.invoice.entity.IvcRegimeProfile;
import ai.neargo.shop.invoice.entity.IvcTransmission;
import ai.neargo.shop.invoice.mapper.InvoiceMappers.AllowanceMapper;
import ai.neargo.shop.invoice.mapper.InvoiceMappers.BuyerPreferenceMapper;
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
import java.math.BigDecimal;
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
import java.util.function.BiFunction;
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
    private final BuyerPreferenceMapper preferences;
    private final InvoiceRegimes regimes;
    private final Map<String, InvoiceSourcePort> sources = new HashMap<>();
    private final ObjectProvider<SecretVault> vault;
    private final OutboxEventBus events;
    private final ObjectMapper json;
    private final InvoiceGatewayPolicy policy;

    public InvoiceServiceImpl(RegimeProfileMapper profiles, NumberRangeMapper ranges, InvoiceMapper invoices,
            InvoiceLineMapper lines, AllowanceMapper allowances, TransmissionMapper transmissions,
            BuyerPreferenceMapper preferences,
            InvoiceRegimes regimes, List<InvoiceSourcePort> sourcePorts, ObjectProvider<SecretVault> vault,
            OutboxEventBus events, ObjectMapper json, InvoiceGatewayPolicy policy) {
        this.policy = policy;
        this.profiles = profiles;
        this.ranges = ranges;
        this.invoices = invoices;
        this.lines = lines;
        this.allowances = allowances;
        this.transmissions = transmissions;
        this.preferences = preferences;
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
        return issue(scope, sourceType, sourceNo, (profile, source) -> buyer);
    }

    /**
     * 买受人在读到来源之后才定：自动开立没预存选择时，要看来源有没有平台买家才能决定是否默认走会员载具。
     * 买受人校验仍在取号之前，不合规不占号。
     */
    private InvoiceReceipt issue(Scope scope, String sourceType, String sourceNo,
            BiFunction<IvcRegimeProfile, InvoiceSourcePort.InvoiceSource, Buyer> buyerOf) {
        var existing = active(scope, sourceType, sourceNo);
        if (existing != null) return receipt(existing);
        var profile = activeProfile(scope);
        var regime = regimes.require(profile.getRegimeCode());
        var port = sources.get(sourceType);
        if (port == null) throw BizException.of(ErrorCode.INVOICE_SOURCE_NOT_READY);
        var source = port.load(scope, sourceType, sourceNo);
        var buyer = buyerOf.apply(profile, source);
        regime.validateBuyer(buyer, profile);
        long sum = 0;
        for (var l : source.lines()) sum = Math.addExact(sum, l.amountMinor());
        if (source.lines().isEmpty() || source.totalMinor() <= 0 || sum != source.totalMinor())
            throw BizException.of(ErrorCode.INVOICE_SOURCE_NOT_READY);
        var amounts = regime.amounts(source.lines(), buyer);

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
        inv.setBuyerUserNo(source.buyerUserNo());
        inv.setBuyerTaxId(blankToNull(buyer.taxId()));
        inv.setBuyerName(blankToNull(buyer.name()));
        inv.setCarrierType(blankToNull(buyer.carrierType()));
        inv.setCarrierId(blankToNull(buyer.carrierId()));
        inv.setDonateCode(blankToNull(buyer.donateCode()));
        inv.setPrintMark(regime.printMark(buyer) ? "Y" : "N");
        inv.setCurrency(source.currency());
        inv.setSalesMinor(amounts.salesMinor());
        inv.setZeroTaxSalesMinor(amounts.zeroTaxSalesMinor());
        inv.setFreeTaxSalesMinor(amounts.freeTaxSalesMinor());
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
            row.setTaxType(l.taxType());
            rows.add(row);
        }
        var occurred = inv.getIssuedAt().toInstant(ZoneOffset.UTC);
        inv.setProofSnapshot(printPayload(InvoicePrintSourceFactsPortImpl.SCENE, InvoicePrintSourceFactsPortImpl.TYPE,
                inv.getInvoiceNo(), occurred, inv, regime.proof(inv, rows, qrKey(profile)), proofLines(inv, rows)));
        inv.setProofSnapshotHash(PrintHashes.sha256(inv.getProofSnapshot()));
        inv.setDetailSnapshot(printPayload(InvoicePrintSourceFactsPortImpl.DETAIL_SCENE,
                InvoicePrintSourceFactsPortImpl.TYPE, inv.getInvoiceNo(), occurred, inv, regime.detail(inv),
                detailLines(inv, rows)));
        inv.setDetailSnapshotHash(PrintHashes.sha256(inv.getDetailSnapshot()));
        invoices.insert(inv);
        rows.forEach(lines::insert);
        enqueue(inv, profile, "ISSUE", inv.getInvoiceNo(), regime.message("ISSUE", inv, rows, null));
        // 打印中心按门店的场景策略决定是否自动出纸：证明联只在应印时唤醒，交易明细每张都唤醒
        if ("Y".equals(inv.getPrintMark()))
            events.publish(new BusinessPrintFactReady(inv.getInvoiceNo(), InvoicePrintSourceFactsPortImpl.ref(inv)));
        events.publish(new BusinessPrintFactReady(inv.getInvoiceNo(), InvoicePrintSourceFactsPortImpl.detailRef(inv)));
        return receipt(inv);
    }

    @Override
    @Transactional
    public void rememberBuyer(Scope scope, String sourceType, String sourceNo, Buyer buyer) {
        var profile = activeProfile(scope);
        regimes.require(profile.getRegimeCode()).validateBuyer(buyer, profile);
        var now = LocalDateTime.now(ZoneOffset.UTC);
        var key = new QueryWrapper<IvcBuyerPreference>().eq("tenant_no", scope.tenantNo())
                .eq("entity_no", scope.entityNo()).eq("source_type", sourceType).eq("source_no", sourceNo);
        var row = preferences.selectOne(key);
        boolean fresh = row == null;
        if (fresh) {
            row = new IvcBuyerPreference();
            row.setTenantNo(scope.tenantNo());
            row.setEntityNo(scope.entityNo());
            row.setSourceType(sourceType);
            row.setSourceNo(sourceNo);
            row.setCreatedAt(now);
        }
        row.setBuyerTaxId(blankToNull(buyer.taxId()));
        row.setBuyerName(blankToNull(buyer.name()));
        row.setCarrierType(blankToNull(buyer.carrierType()));
        row.setCarrierId(blankToNull(buyer.carrierId()));
        row.setDonateCode(blankToNull(buyer.donateCode()));
        row.setCreatedBy(scope.actorNo());
        row.setUpdatedAt(now);
        // 清空某个字段（例如从打统编改成一般消费者）也要落库：用整行 UpdateWrapper，不靠 updateById 跳过 null
        if (fresh) preferences.insert(row);
        else preferences.update(null, new UpdateWrapper<IvcBuyerPreference>().eq("id", row.getId())
                .set("buyer_tax_id", row.getBuyerTaxId()).set("buyer_name", row.getBuyerName())
                .set("carrier_type", row.getCarrierType()).set("carrier_id", row.getCarrierId())
                .set("donate_code", row.getDonateCode()).set("created_by", row.getCreatedBy())
                .set("updated_at", now));
    }

    @Override
    @Transactional
    public Optional<InvoiceReceipt> issueIfAutomatic(Scope scope, String sourceType, String sourceNo) {
        var profile = profiles.selectOne(new QueryWrapper<IvcRegimeProfile>().eq("tenant_no", scope.tenantNo())
                .eq("entity_no", scope.entityNo()).eq("status", "ACTIVE").last("LIMIT 1"));
        if (profile == null || !Boolean.TRUE.equals(profile.getAutoIssue())) return Optional.empty();
        var p = preferences.selectOne(new QueryWrapper<IvcBuyerPreference>().eq("tenant_no", scope.tenantNo())
                .eq("entity_no", scope.entityNo()).eq("source_type", sourceType).eq("source_no", sourceNo));
        if (p != null)
            return Optional.of(issue(scope, sourceType, sourceNo, new Buyer(p.getBuyerTaxId(), p.getBuyerName(),
                    p.getCarrierType(), p.getCarrierId(), p.getDonateCode())));
        // 载具优先：没预存选择、但有平台买家且主体申请了会员载具 → 存会员载具，不出纸
        return Optional.of(issue(scope, sourceType, sourceNo, (settings, source) ->
                source.buyerUserNo() != null && settings.getMemberCarrierType() != null
                        ? memberCarrier(settings, source.buyerUserNo()) : Buyer.consumer()));
    }

    /** 会员载具：类别号取主体申请的那一个，载具号就是平台买家用户号 —— 由服务端填，不让买家自报。 */
    static Buyer memberCarrier(IvcRegimeProfile profile, String buyerUserNo) {
        return new Buyer(null, null, profile.getMemberCarrierType(), buyerUserNo, null);
    }

    @Override
    public PageData<InvoiceVO> listForBuyer(String tenantNo, String buyerUserNo, int page, int size) {
        var result = invoices.selectPage(new Page<>(page, size), new QueryWrapper<IvcInvoice>()
                .eq("tenant_no", tenantNo).eq("buyer_user_no", buyerUserNo).orderByDesc("issued_at", "id"));
        return PageData.of(result.getRecords().stream().map(i -> view(i, false)).toList(), result.getTotal(),
                page, size);
    }

    @Override
    public InvoiceVO getForBuyer(String tenantNo, String buyerUserNo, String invoiceNo) {
        var inv = invoices.selectOne(new QueryWrapper<IvcInvoice>().eq("tenant_no", tenantNo)
                .eq("buyer_user_no", buyerUserNo).eq("invoice_no", invoiceNo));
        if (inv == null) throw BizException.of(ErrorCode.INVOICE_NOT_FOUND);
        return view(inv, true);
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
    public PageData<InvoiceVO> listForOps(String tenantNo, java.util.Set<String> entityNos,
            InvoiceDtos.OpsInvoiceQuery query) {
        int page = query.page() == null ? 1 : query.page(), size = query.size() == null ? 20 : query.size();
        if (entityNos != null && entityNos.isEmpty()) return PageData.of(List.of(), 0, page, size);
        var result = invoices.selectPage(new Page<>(page, size), new QueryWrapper<IvcInvoice>().eq("tenant_no", tenantNo)
                .in(entityNos != null, "entity_no", entityNos)
                .eq(query.entityNo() != null, "entity_no", query.entityNo())
                .eq(query.storeNo() != null, "store_no", query.storeNo())
                .eq(query.fiscalNumber() != null, "fiscal_number", query.fiscalNumber())
                .eq(query.periodCode() != null, "period_code", query.periodCode())
                .eq(query.status() != null, "status", query.status())
                .orderByDesc("issued_at", "id"));
        return PageData.of(result.getRecords().stream().map(i -> view(i, false)).toList(), result.getTotal(), page,
                size);
    }

    @Override
    public Scope scopeOf(String tenantNo, String invoiceNo, String actorNo) {
        var inv = invoices.selectOne(new QueryWrapper<IvcInvoice>().eq("tenant_no", tenantNo).eq("invoice_no", invoiceNo));
        if (inv == null) throw BizException.of(ErrorCode.INVOICE_NOT_FOUND);
        return new Scope(tenantNo, inv.getEntityNo(), inv.getStoreNo(), actorNo);
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
        // 对外折让单号 = 发票号码 + 两位序号（MIG AllowanceNumber 上限 16 位，内部单号放不下）；
        // 上面的条件更新已锁住发票行，同一张发票的折让在此串行，计数不会撞号
        long seq = allowances.selectCount(new QueryWrapper<IvcAllowance>().eq("tenant_no", inv.getTenantNo())
                .eq("invoice_no", inv.getInvoiceNo())) + 1;
        if (seq > 99) throw BizException.of(ErrorCode.INVOICE_ALLOWANCE_EXCEEDED);
        var a = new IvcAllowance();
        a.setAllowanceNo(BizKey.next(BizKey.INVOICE_ALLOWANCE));
        a.setFiscalAllowanceNumber(inv.getFiscalNumber() + "%02d".formatted(seq));
        a.setTenantNo(inv.getTenantNo());
        a.setEntityNo(inv.getEntityNo());
        a.setStoreNo(inv.getStoreNo());
        a.setInvoiceNo(inv.getInvoiceNo());
        a.setAmountMinor(amountMinor);
        a.setTaxMinor(tax);
        a.setReason(reason);
        a.setStatus("ACTIVE");
        a.setCreatedBy(scope.actorNo());
        a.setCreatedAt(LocalDateTime.now(ZoneOffset.UTC));
        a.setPrintSnapshot(printPayload(InvoicePrintSourceFactsPortImpl.ALLOWANCE_SCENE,
                InvoicePrintSourceFactsPortImpl.ALLOWANCE_TYPE, a.getAllowanceNo(),
                a.getCreatedAt().toInstant(ZoneOffset.UTC), inv, regime.allowanceProof(inv, a), List.of()));
        a.setPrintSnapshotHash(PrintHashes.sha256(a.getPrintSnapshot()));
        allowances.insert(a);
        events.publish(new BusinessPrintFactReady(a.getAllowanceNo(), InvoicePrintSourceFactsPortImpl.ref(inv, a)));
        enqueue(inv, activeProfile(scope), "ALLOWANCE", a.getAllowanceNo(),
                regime.message("ALLOWANCE", inv, List.of(), a));
        return view(require(scope, invoiceNo), true);
    }

    /**
     * 折让作废：只能作废本期折让（跨期已申报）；作废后累计折让回退 —— 全部折让都作废了，发票本身又能作废。
     * 折让证明单作废后不再出纸（打印守卫按折让状态拦）。
     */
    @Override
    @Transactional
    public InvoiceVO voidAllowance(Scope scope, String invoiceNo, String allowanceNo, String reason) {
        var inv = require(scope, invoiceNo);
        var a = allowances.selectOne(new QueryWrapper<IvcAllowance>().eq("tenant_no", inv.getTenantNo())
                .eq("entity_no", inv.getEntityNo()).eq("invoice_no", invoiceNo).eq("allowance_no", allowanceNo));
        if (a == null) throw BizException.of(ErrorCode.INVOICE_NOT_FOUND);
        if (!"ISSUED".equals(inv.getStatus()) || "VOIDED".equals(a.getStatus()))
            throw BizException.of(ErrorCode.INVOICE_STATE_INVALID);
        var regime = regimes.require(inv.getRegimeCode());
        if (!regime.allowanceVoidable(a, Instant.now())) throw BizException.of(ErrorCode.INVOICE_VOID_PERIOD_CLOSED);
        var now = LocalDateTime.now(ZoneOffset.UTC);
        if (allowances.update(null, new UpdateWrapper<IvcAllowance>().eq("id", a.getId()).eq("status", "ACTIVE")
                .set("status", "VOIDED").set("void_reason", reason).set("voided_at", now)
                .set("voided_by", scope.actorNo())) != 1)
            throw BizException.of(ErrorCode.INVOICE_STATE_INVALID);
        if (invoices.releaseAllowance(inv.getId(), a.getAmountMinor()) != 1)
            throw BizException.of(ErrorCode.INVOICE_STATE_INVALID);
        a.setStatus("VOIDED");
        a.setVoidReason(reason);
        a.setVoidedAt(now);
        enqueue(inv, activeProfile(scope), "ALLOWANCE_VOID", a.getAllowanceNo(),
                regime.message("ALLOWANCE_VOID", inv, List.of(), a));
        return view(require(scope, invoiceNo), true);
    }

    private void enqueue(IvcInvoice inv, IvcRegimeProfile profile, String type, String refNo,
            Map<String, Object> message) {
        var t = new IvcTransmission();
        t.setTransmissionNo(BizKey.next(BizKey.INVOICE_TRANSMISSION));
        t.setTenantNo(inv.getTenantNo());
        t.setEntityNo(inv.getEntityNo());
        t.setStoreNo(inv.getStoreNo());
        t.setRegimeCode(inv.getRegimeCode());
        t.setGatewayCode(policy.effective(profile).code());   // 开立时冻结：之后切换供应商，这张票仍走原供应商
        t.setMessageType(type);
        t.setRefNo(refNo);
        t.setPayload(json.writeValueAsString(message));
        t.setStatus("PENDING");
        t.setAttempts(0);
        var now = LocalDateTime.now(ZoneOffset.UTC);
        t.setNextAttemptAt(now);
        // 法定期限从事件发生（即入队这一刻）起算；预警在窗口过半时
        var window = regimes.require(inv.getRegimeCode()).reportWindow(type, inv);
        t.setReportDeadlineAt(now.plus(window));
        t.setReportWarnAt(now.plus(window.dividedBy(2)));
        t.setDeadlineLevel(0);
        t.setCreatedAt(now);
        t.setUpdatedAt(now);
        transmissions.insert(t);
    }

    /**
     * 证明联的打印事实快照（打印中心公共字段 + 税制给出的 fields）。按键排序序列化，哈希才稳定。
     * 门店名取开立时冻结的卖方名称：证明联上印的是营业人，不是门店展示名。
     */
    /**
     * 打印事实快照：打印中心公共字段 + 税制给出的 fields + 明细行。按键排序序列化，哈希才稳定。
     * 门店名取开立时冻结的营业人名称：发票上印的是营业人，不是门店展示名。
     */
    private String printPayload(String scene, String sourceType, String factNo, Instant occurredAt, IvcInvoice inv,
            Map<String, Object> regimeFields, List<Map<String, Object>> lineItems) {
        var payload = new TreeMap<String, Object>();
        payload.put("scene", scene);
        payload.put("payloadSchemaVersion", 1);
        payload.put("store", Map.of("name", inv.getSellerName(),
                "timezone", regimeFields.getOrDefault("timezone", "UTC")));
        payload.put("source", Map.of("type", sourceType, "no", factNo, "factNo", factNo, "revision", "1",
                "occurredAt", occurredAt.toString()));
        payload.put("currency", inv.getCurrency());
        var fields = new TreeMap<String, Object>();
        regimeFields.forEach((k, v) -> {
            if (v != null) fields.put(k, v);
        });
        fields.put("totalAmount", money(inv.getTotalMinor(), inv.getCurrency()));
        payload.put("fields", fields);
        payload.put("lines", lineItems);
        return json.writeValueAsString(sorted(payload));
    }

    private static List<Map<String, Object>> proofLines(IvcInvoice inv, List<IvcInvoiceLine> rows) {
        var out = new ArrayList<Map<String, Object>>();
        for (var r : rows)
            out.add(Map.of("lineRef", String.valueOf(r.getLineNo()), "title", r.getName(),
                    "quantity", r.getQuantity().stripTrailingZeros().toPlainString(),
                    "lineAmount", money(r.getAmountMinor(), inv.getCurrency())));
        return out;
    }

    /** 交易明细逐行：品名、数量、单价（文本，已按币种小数位）、金额（金额型，打印中心按币种排版）。 */
    private static List<Map<String, Object>> detailLines(IvcInvoice inv, List<IvcInvoiceLine> rows) {
        int scale = Currency.getInstance(inv.getCurrency()).getDefaultFractionDigits();
        var out = new ArrayList<Map<String, Object>>();
        for (var r : rows)
            out.add(Map.of("title", r.getName(),
                    "quantity", r.getQuantity().stripTrailingZeros().toPlainString(),
                    "unitPrice", BigDecimal.valueOf(r.getUnitPriceMinor(), scale).stripTrailingZeros()
                            .toPlainString(),
                    "lineAmount", money(r.getAmountMinor(), inv.getCurrency())));
        return out;
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

    @Override
    public List<InvoiceDtos.InvoicePrintFactVO> printFacts(Scope scope, String invoiceNo) {
        var inv = require(scope, invoiceNo);
        var out = new ArrayList<InvoiceDtos.InvoicePrintFactVO>();
        boolean issued = "ISSUED".equals(inv.getStatus());
        if (issued && "Y".equals(inv.getPrintMark()) && inv.getProofSnapshotHash() != null)
            out.add(new InvoiceDtos.InvoicePrintFactVO(InvoicePrintSourceFactsPortImpl.SCENE, inv.getInvoiceNo(),
                    InvoicePrintSourceFactsPortImpl.ref(inv)));
        if (issued && inv.getDetailSnapshotHash() != null)
            out.add(new InvoiceDtos.InvoicePrintFactVO(InvoicePrintSourceFactsPortImpl.DETAIL_SCENE, inv.getInvoiceNo(),
                    InvoicePrintSourceFactsPortImpl.detailRef(inv)));
        for (var a : allowances.selectList(new QueryWrapper<IvcAllowance>().eq("tenant_no", inv.getTenantNo())
                .eq("invoice_no", inv.getInvoiceNo()).orderByAsc("id")))
            if (!"VOIDED".equals(a.getStatus()) && a.getPrintSnapshotHash() != null)
                out.add(new InvoiceDtos.InvoicePrintFactVO(InvoicePrintSourceFactsPortImpl.ALLOWANCE_SCENE,
                        a.getAllowanceNo(), InvoicePrintSourceFactsPortImpl.ref(inv, a)));
        return out;
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
                            l.getUnitPriceMinor(), l.getAmountMinor(), l.getTaxType()))
                    .toList();
            allowanceViews = allowances.selectList(new QueryWrapper<IvcAllowance>().eq("tenant_no", i.getTenantNo())
                            .eq("invoice_no", i.getInvoiceNo()).orderByAsc("id")).stream()
                    .map(a -> new AllowanceVO(a.getAllowanceNo(), a.getAmountMinor(), a.getTaxMinor(), a.getReason(),
                            a.getCreatedAt(), a.getFiscalAllowanceNumber(),
                            a.getStatus() == null ? "ACTIVE" : a.getStatus(), a.getVoidReason(), a.getVoidedAt()))
                    .toList();
        }
        return new InvoiceVO(i.getInvoiceNo(), i.getRegimeCode(), i.getIndustryCode(), i.getSellerName(),
                i.getSellerTaxId(), i.getSourceType(),
                i.getSourceNo(), i.getStoreNo(), i.getFiscalNumber(), i.getPeriodCode(), i.getRandomCode(),
                i.getIssuedAt(), i.getBuyerType(), i.getBuyerTaxId(), i.getCarrierType(), i.getCarrierId(),
                i.getDonateCode(), "Y".equals(i.getPrintMark()), i.getCurrency(), i.getSalesMinor(),
                i.getZeroTaxSalesMinor(), i.getFreeTaxSalesMinor(), i.getTaxMinor(),
                i.getTotalMinor(), i.getAllowedMinor(), i.getStatus(), i.getVoidReason(),
                issue == null ? null : issue.getStatus(), issue == null ? null : issue.getReportDeadlineAt(),
                lineViews, allowanceViews);
    }

    static String sourceKey(String sourceType, String sourceNo) {
        return sourceType + ":" + sourceNo;
    }

    private static String blankToNull(String s) {
        return s == null || s.isBlank() ? null : s.trim();
    }
}
