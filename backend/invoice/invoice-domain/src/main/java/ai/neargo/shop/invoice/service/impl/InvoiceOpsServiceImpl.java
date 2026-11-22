package ai.neargo.shop.invoice.service.impl;

import ai.neargo.shop.common.PageData;
import ai.neargo.shop.invoice.dto.InvoiceDtos.OpsSellerQuery;
import ai.neargo.shop.invoice.dto.InvoiceDtos.OpsSellerVO;
import ai.neargo.shop.invoice.dto.InvoiceDtos.OpsTransmissionQuery;
import ai.neargo.shop.invoice.dto.InvoiceDtos.OpsTransmissionVO;
import ai.neargo.shop.invoice.entity.IvcAllowance;
import ai.neargo.shop.invoice.entity.IvcInvoice;
import ai.neargo.shop.invoice.entity.IvcRegimeProfile;
import ai.neargo.shop.invoice.entity.IvcTransmission;
import ai.neargo.shop.invoice.mapper.InvoiceMappers.AllowanceMapper;
import ai.neargo.shop.invoice.mapper.InvoiceMappers.InvoiceMapper;
import ai.neargo.shop.invoice.mapper.InvoiceMappers.NumberRangeMapper;
import ai.neargo.shop.invoice.mapper.InvoiceMappers.RegimeProfileMapper;
import ai.neargo.shop.invoice.mapper.InvoiceMappers.TransmissionMapper;
import ai.neargo.shop.invoice.regime.InvoiceRegimes;
import ai.neargo.shop.invoice.service.InvoiceOpsService;
import com.baomidou.mybatisplus.core.conditions.query.QueryWrapper;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import java.time.Instant;
import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.function.Function;
import java.util.stream.Collectors;
import org.springframework.stereotype.Service;

/**
 * 运营端跨主体视图。营业人一览按「主体配置 + 两条汇总 SQL」在内存拼装、筛选、分页 ——
 * 主体数是平台开通发票的商家数（百级），不是发票数；量起来之后改为落一张汇总表。
 */
@Service
public class InvoiceOpsServiceImpl implements InvoiceOpsService {

    private final RegimeProfileMapper profiles;
    private final NumberRangeMapper ranges;
    private final TransmissionMapper transmissions;
    private final InvoiceMapper invoices;
    private final AllowanceMapper allowances;
    private final InvoiceRegimes regimes;

    public InvoiceOpsServiceImpl(RegimeProfileMapper profiles, NumberRangeMapper ranges,
            TransmissionMapper transmissions, InvoiceMapper invoices, AllowanceMapper allowances,
            InvoiceRegimes regimes) {
        this.profiles = profiles;
        this.ranges = ranges;
        this.transmissions = transmissions;
        this.invoices = invoices;
        this.allowances = allowances;
        this.regimes = regimes;
    }

    @Override
    public PageData<OpsSellerVO> sellers(String tenantNo, OpsSellerQuery q) {
        var now = LocalDateTime.now(ZoneOffset.UTC);
        var backlog = byEntity(transmissions.backlogByEntity(tenantNo, now));
        var remainingByPeriod = new HashMap<String, Map<String, Map<String, Object>>>();
        String keyword = q.keyword() == null || q.keyword().isBlank() ? null : q.keyword().trim();
        var rows = new ArrayList<OpsSellerVO>();
        for (var p : profiles.selectList(new QueryWrapper<IvcRegimeProfile>().eq("tenant_no", tenantNo)
                .eq(q.gatewayCode() != null, "gateway_code", q.gatewayCode())
                .and(keyword != null, w -> w.like("seller_name", keyword).or().like("seller_tax_id", keyword)
                        .or().eq("entity_no", keyword))
                .orderByAsc("entity_no"))) {
            String period = regimes.require(p.getRegimeCode()).periodOf(Instant.now());
            var remaining = period == null ? Map.<String, Map<String, Object>>of()
                    : remainingByPeriod.computeIfAbsent(period, k -> byEntity(ranges.remainingByEntity(tenantNo, k)));
            var b = backlog.getOrDefault(p.getEntityNo(), Map.of());
            int low = p.getRangeLowWater() == null ? InvoiceSetupServiceImpl.DEFAULT_LOW_WATER : p.getRangeLowWater();
            var row = new OpsSellerVO(p.getEntityNo(), p.getRegimeCode(), p.getSellerTaxId(), p.getSellerName(),
                    p.getGatewayCode(), p.getStatus(), p.getProviderAccountCode(), p.getProviderSecretRef() != null,
                    p.getProviderHoldReason(), p.getProviderHeldAt(), period,
                    num(remaining.getOrDefault(p.getEntityNo(), Map.of()).get("remaining")), low,
                    Boolean.TRUE.equals(p.getAutoFetchRanges()), num(b.get("pending")), num(b.get("held")),
                    num(b.get("needsReview")), num(b.get("dueSoon")), num(b.get("overdue")));
            if (matches(row, q.problem())) rows.add(row);
        }
        int page = q.page() == null ? 1 : q.page(), size = q.size() == null ? 20 : q.size();
        int from = Math.min((page - 1) * size, rows.size()), to = Math.min(from + size, rows.size());
        return PageData.of(List.copyOf(rows.subList(from, to)), rows.size(), page, size);
    }

    private static boolean matches(OpsSellerVO r, String problem) {
        if (problem == null) return true;
        return switch (problem) {
            case "HELD" -> r.providerHoldReason() != null;
            case "OVERDUE" -> r.overdue() > 0;
            case "REVIEW" -> r.needsReview() > 0;
            case "BACKLOG" -> r.pending() + r.held() + r.needsReview() > 0;
            case "LOW_RANGE" -> "ACTIVE".equals(r.status()) && r.currentRemaining() < r.rangeLowWater();
            default -> true;
        };
    }

    @Override
    public PageData<OpsTransmissionVO> transmissions(String tenantNo, OpsTransmissionQuery q) {
        var now = LocalDateTime.now(ZoneOffset.UTC);
        int page = q.page() == null ? 1 : q.page(), size = q.size() == null ? 20 : q.size();
        boolean overdueOnly = Boolean.TRUE.equals(q.overdue());
        var result = transmissions.selectPage(new Page<>(page, size), new QueryWrapper<IvcTransmission>()
                .eq("tenant_no", tenantNo).eq(q.entityNo() != null, "entity_no", q.entityNo())
                .eq(q.status() != null, "status", q.status())
                .ne(overdueOnly, "status", "SENT").le(overdueOnly, "report_deadline_at", now)
                .orderByAsc("report_deadline_at", "id"));
        var tasks = result.getRecords();
        // 折让类任务的业务号是折让单号：换成它的原发票，界面才能点进发票
        var allowanceRefs = tasks.stream().filter(t -> t.getMessageType().startsWith("ALLOWANCE"))
                .map(IvcTransmission::getRefNo).toList();
        Map<String, String> allowanceToInvoice = allowanceRefs.isEmpty() ? Map.of()
                : allowances.selectList(new QueryWrapper<IvcAllowance>().eq("tenant_no", tenantNo)
                        .in("allowance_no", allowanceRefs)).stream()
                        .collect(Collectors.toMap(IvcAllowance::getAllowanceNo, IvcAllowance::getInvoiceNo, (a, b) -> a));
        var invoiceNos = tasks.stream().map(t -> allowanceToInvoice.getOrDefault(t.getRefNo(), t.getRefNo()))
                .distinct().toList();
        Map<String, IvcInvoice> byNo = invoiceNos.isEmpty() ? Map.of()
                : invoices.selectList(new QueryWrapper<IvcInvoice>().eq("tenant_no", tenantNo)
                        .in("invoice_no", invoiceNos)).stream()
                        .collect(Collectors.toMap(IvcInvoice::getInvoiceNo, Function.identity()));
        var rows = tasks.stream().map(t -> {
            String invoiceNo = allowanceToInvoice.getOrDefault(t.getRefNo(), t.getRefNo());
            var inv = byNo.get(invoiceNo);
            boolean late = !"SENT".equals(t.getStatus()) && t.getReportDeadlineAt() != null
                    && !t.getReportDeadlineAt().isAfter(now);
            return new OpsTransmissionVO(t.getTransmissionNo(), t.getEntityNo(),
                    t.getStoreNo() != null ? t.getStoreNo() : inv == null ? null : inv.getStoreNo(),
                    t.getMessageType(), t.getRefNo(), invoiceNo, inv == null ? null : inv.getFiscalNumber(),
                    t.getGatewayCode(), t.getStatus(), t.getAttempts(), t.getLastError(), t.getNextAttemptAt(),
                    t.getReportDeadlineAt(), late, t.getCreatedAt());
        }).toList();
        return PageData.of(rows, result.getTotal(), page, size);
    }

    /** 汇总 SQL 的列名在不同驱动下大小写不一，统一按小写取。 */
    private static Map<String, Map<String, Object>> byEntity(List<Map<String, Object>> rows) {
        var out = new HashMap<String, Map<String, Object>>();
        for (var r : rows) {
            var lower = new HashMap<String, Object>();
            r.forEach((k, v) -> lower.put(k.toLowerCase(Locale.ROOT), v));
            var normalized = new HashMap<String, Object>();
            normalized.put("pending", lower.get("pending"));
            normalized.put("held", lower.get("held"));
            normalized.put("needsReview", lower.get("needsreview"));
            normalized.put("dueSoon", lower.get("duesoon"));
            normalized.put("overdue", lower.get("overdue"));
            normalized.put("remaining", lower.get("remaining"));
            out.put(String.valueOf(lower.get("entityno")), normalized);
        }
        return out;
    }

    private static long num(Object v) {
        return v instanceof Number n ? n.longValue() : 0;
    }
}
