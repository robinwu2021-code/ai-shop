package ai.neargo.shop.invoice.service.impl;

import ai.neargo.shop.common.BizException;
import ai.neargo.shop.common.ErrorCode;
import ai.neargo.shop.invoice.dto.InvoiceDtos.ProviderCheckVO;
import ai.neargo.shop.invoice.dto.InvoiceDtos.UploadBacklogVO;
import ai.neargo.shop.invoice.entity.IvcInvoice;
import ai.neargo.shop.invoice.gateway.ProviderException;
import ai.neargo.shop.invoice.gateway.QueryCapability;
import ai.neargo.shop.invoice.gateway.QueryCapability.ProviderInvoiceStatus.State;
import ai.neargo.shop.invoice.mapper.InvoiceMappers.InvoiceMapper;
import ai.neargo.shop.invoice.regime.InvoiceRegimes;
import ai.neargo.shop.invoice.entity.IvcAllowance;
import ai.neargo.shop.invoice.entity.IvcRegimeProfile;
import ai.neargo.shop.invoice.entity.IvcTransmission;
import ai.neargo.shop.invoice.gateway.InvoiceGateway;
import ai.neargo.shop.invoice.gateway.InvoiceGateway.GatewayReceipt;
import ai.neargo.shop.invoice.gateway.InvoiceGateway.GatewayRequest;
import ai.neargo.shop.invoice.gateway.InvoiceGateways;
import ai.neargo.shop.invoice.mapper.InvoiceMappers.AllowanceMapper;
import ai.neargo.shop.invoice.mapper.InvoiceMappers.RegimeProfileMapper;
import ai.neargo.shop.invoice.mapper.InvoiceMappers.TransmissionMapper;
import ai.neargo.shop.invoice.service.InvoiceTransmissionService;
import ai.neargo.shop.spi.invoice.InvoicePort.Scope;
import com.baomidou.mybatisplus.core.conditions.query.QueryWrapper;
import com.baomidou.mybatisplus.core.conditions.update.UpdateWrapper;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.stream.Collectors;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

/**
 * 上传任务的唯一执行者（重试只在这里，不在通道、不在开立流程）。
 *
 * <p>先用条件更新把任务「认领」成 SENDING 再发 —— 多实例同时跑也只有一个实例发同一条。
 *
 * <p><b>失败分级止损</b>（TDD-发票模块 §12）—— 通道回执告诉我们失败属于哪一种，处置各不相同：
 * <ul>
 *   <li>可重试（对方忙、网络不通、通道抛异常）：FAILED，按 1、2、4 … 分钟（封顶 60）退避，下一轮原样补发；</li>
 *   <li>账号问题（如鲸跃 {@code invalid}）：本任务转 HELD，并把主体配置标为「暂停上传」——
 *       之后该主体的任务在发送前就转 HELD，<b>一次都不再打到服务商</b>；商家核验账号通过后由
 *       {@link #releaseHeld} 一并放回 PENDING；</li>
 *   <li>报文被拒（格式、业务规则、不认识的码）：NEEDS_REVIEW，不自动重发，处理后由 {@link #retry} 手动重传。</li>
 * </ul>
 * 后两种各打一条固定关键字的 ERROR 日志（{@code INVOICE_PROVIDER_HOLD} / {@code INVOICE_TRANSMISSION_NEEDS_REVIEW}），供日志告警订阅。
 *
 * <p>加值中心通道要商家账号：发送前按任务的主体 + 税制现取配置、现解密码，不把密码放进任务或报文。
 */
@Service
public class InvoiceTransmissionServiceImpl implements InvoiceTransmissionService {

    private static final Logger log = LoggerFactory.getLogger(InvoiceTransmissionServiceImpl.class);
    static final String HELD = "HELD";
    static final String NEEDS_REVIEW = "NEEDS_REVIEW";

    private final TransmissionMapper transmissions;
    private final RegimeProfileMapper profiles;
    private final AllowanceMapper allowances;
    private final InvoiceProviderAccounts accounts;
    private final InvoiceGateways gateways;
    private final InvoiceMapper invoices;
    private final InvoiceRegimes regimes;

    public InvoiceTransmissionServiceImpl(TransmissionMapper transmissions, RegimeProfileMapper profiles,
            AllowanceMapper allowances, InvoiceProviderAccounts accounts, InvoiceGateways gateways,
            InvoiceMapper invoices, InvoiceRegimes regimes) {
        this.transmissions = transmissions;
        this.profiles = profiles;
        this.allowances = allowances;
        this.invoices = invoices;
        this.regimes = regimes;
        this.accounts = accounts;
        this.gateways = gateways;
    }

    @Override
    public int transmitDue(int limit) {
        var now = LocalDateTime.now(ZoneOffset.UTC);
        // SENDING 超过 10 分钟没回音 = 上一个执行者在发送途中退出；报文幂等由通道按发票号识别，重发安全
        var due = transmissions.selectList(new QueryWrapper<IvcTransmission>()
                .and(w -> w.in("status", "PENDING", "FAILED").le("next_attempt_at", now)
                        .or(x -> x.eq("status", "SENDING").le("updated_at", now.minusMinutes(10))))
                .orderByAsc("next_attempt_at", "id").last("LIMIT " + limit));
        int sent = 0;
        // 同一轮里同一主体的配置只查一次；本轮刚被暂停的主体，后面的任务直接转 HELD
        var profileCache = new HashMap<String, IvcRegimeProfile>();
        for (var t : due) {
            var gateway = gateways.find(t.getGatewayCode()).orElse(null);
            IvcRegimeProfile profile = gateway != null && gateway.profile().requiresAccount()
                    ? profileCache.computeIfAbsent(t.getTenantNo() + "|" + t.getEntityNo() + "|" + t.getRegimeCode(),
                            k -> profileOf(t))
                    : null;
            if (profile != null && profile.getProviderHeldAt() != null) {
                transmissions.update(null, new UpdateWrapper<IvcTransmission>().eq("id", t.getId())
                        .eq("status", t.getStatus()).set("status", HELD).set("updated_at", now));
                continue;
            }
            if (transmissions.update(null, new UpdateWrapper<IvcTransmission>().eq("id", t.getId())
                    .eq("status", t.getStatus()).eq("attempts", t.getAttempts())
                    .set("status", "SENDING").set("updated_at", now)) != 1) continue;
            GatewayReceipt receipt;
            try {
                receipt = send(gateway, t, profile);
            } catch (RuntimeException e) {
                // 业务动作：通道异常即本次未送达，转为可重试状态；原报文留着下一轮补发
                receipt = GatewayReceipt.retryable(String.valueOf(e.getMessage()));
            }
            if (receipt.accepted()) sent++;
            record(t, receipt);
            if (receipt.outcome() == GatewayReceipt.Outcome.ACCOUNT_PROBLEM && profile != null) {
                hold(profile, receipt.error());
                profile.setProviderHeldAt(now);   // 本轮后续同主体任务据此直接转 HELD
            }
        }
        return sent;
    }

    @Override
    public int releaseHeld(String tenantNo, String entityNo, String regimeCode) {
        var now = LocalDateTime.now(ZoneOffset.UTC);
        profiles.update(null, new UpdateWrapper<IvcRegimeProfile>().eq("tenant_no", tenantNo)
                .eq("entity_no", entityNo).eq("regime_code", regimeCode)
                .set("provider_hold_reason", null).set("provider_held_at", null).set("updated_at", now));
        int released = transmissions.update(null, new UpdateWrapper<IvcTransmission>().eq("tenant_no", tenantNo)
                .eq("entity_no", entityNo).eq("regime_code", regimeCode).eq("status", HELD)
                .set("status", "PENDING").set("next_attempt_at", now).set("updated_at", now));
        if (released > 0) log.info("INVOICE_PROVIDER_RELEASED tenant={} entity={} tasks={}", tenantNo, entityNo, released);
        return released;
    }

    @Override
    public int retry(Scope scope, String invoiceNo) {
        // 一张发票的全部上传任务：开立、作废，以及它的每张折让
        var refs = new ArrayList<String>();
        refs.add(invoiceNo);
        allowances.selectList(new QueryWrapper<IvcAllowance>().eq("tenant_no", scope.tenantNo())
                        .eq("entity_no", scope.entityNo()).eq("invoice_no", invoiceNo))
                .forEach(a -> refs.add(a.getAllowanceNo()));
        var now = LocalDateTime.now(ZoneOffset.UTC);
        int n = transmissions.update(null, new UpdateWrapper<IvcTransmission>().eq("tenant_no", scope.tenantNo())
                .eq("entity_no", scope.entityNo()).in("ref_no", refs).eq("status", NEEDS_REVIEW)
                .set("status", "PENDING").set("next_attempt_at", now).set("last_error", null).set("updated_at", now));
        if (n == 0) throw BizException.of(ErrorCode.INVOICE_STATE_INVALID);
        return n;
    }

    @Override
    public int[] checkDeadlines(int limit) {
        var now = LocalDateTime.now(ZoneOffset.UTC);
        // 先逾期再预警：一条直接跨过两级的任务只报逾期，不再补一条预警
        int overdue = escalate(now, 2, "report_deadline_at", limit);
        int warned = escalate(now, 1, "report_warn_at", limit);
        return new int[] {warned, overdue};
    }

    /** 把到点且级别不足的未送达任务升到 level，按主体汇总各打一条日志（同一批问题不刷屏）。 */
    private int escalate(LocalDateTime now, int level, String column, int limit) {
        var due = transmissions.selectList(new QueryWrapper<IvcTransmission>().ne("status", "SENT")
                .lt("deadline_level", level).le(column, now).orderByAsc(column).last("LIMIT " + limit));
        var byEntity = new LinkedHashMap<String, List<IvcTransmission>>();
        for (var t : due) {
            // 条件更新：多实例同时巡检，每条每级只有一个实例报
            if (transmissions.update(null, new UpdateWrapper<IvcTransmission>().eq("id", t.getId())
                    .lt("deadline_level", level).set("deadline_level", level)) != 1) continue;
            byEntity.computeIfAbsent(t.getTenantNo() + " " + t.getEntityNo(), k -> new ArrayList<>()).add(t);
        }
        byEntity.forEach((key, tasks) -> {
            String refs = tasks.stream().limit(5).map(t -> t.getMessageType() + ":" + t.getRefNo() + "(" + t.getStatus() + ")")
                    .collect(Collectors.joining(","));
            if (level == 2)
                log.error("INVOICE_REPORT_OVERDUE {} count={} refs={}", key, tasks.size(), refs);
            else
                log.warn("INVOICE_REPORT_DEADLINE_WARN {} count={} refs={}", key, tasks.size(), refs);
        });
        return byEntity.values().stream().mapToInt(List::size).sum();
    }

    @Override
    public ProviderCheckVO checkWithProvider(Scope scope, String invoiceNo) {
        var inv = invoices.selectOne(new QueryWrapper<IvcInvoice>().eq("tenant_no", scope.tenantNo())
                .eq("entity_no", scope.entityNo()).eq("store_no", scope.storeNo()).eq("invoice_no", invoiceNo));
        if (inv == null) throw BizException.of(ErrorCode.INVOICE_NOT_FOUND);
        var profile = profiles.selectOne(new QueryWrapper<IvcRegimeProfile>().eq("tenant_no", inv.getTenantNo())
                .eq("entity_no", inv.getEntityNo()).eq("regime_code", inv.getRegimeCode()).last("LIMIT 1"));
        // 查这张票当初送去的那家（开立时冻结），而不是主体现在生效的那家
        var issued = transmissions.selectOne(new QueryWrapper<IvcTransmission>().eq("tenant_no", inv.getTenantNo())
                .eq("entity_no", inv.getEntityNo()).eq("message_type", "ISSUE").eq("ref_no", invoiceNo).last("LIMIT 1"));
        var provider = profile == null || issued == null ? null
                : gateways.capability(issued.getGatewayCode(), QueryCapability.class).orElse(null);
        if (provider == null) throw BizException.of(ErrorCode.INVOICE_STATE_INVALID);
        var account = accounts.of(profile).orElseThrow(
                () -> BizException.of(ErrorCode.INVOICE_PROVIDER_REJECTED, "account_not_configured"));
        var day = LocalDate.ofInstant(inv.getIssuedAt().toInstant(ZoneOffset.UTC),
                regimes.require(inv.getRegimeCode()).zone());
        QueryCapability.ProviderInvoiceStatus status;
        try {
            status = provider.queryStatus(account, inv.getFiscalNumber(), day);
        } catch (ProviderException e) {
            // 业务动作：服务商拒绝或不可达，原文代码交给用户
            throw BizException.of(ErrorCode.INVOICE_PROVIDER_REJECTED, e.code());
        }
        var now = LocalDateTime.now(ZoneOffset.UTC);
        var tasks = transmissions.selectList(new QueryWrapper<IvcTransmission>().eq("tenant_no", inv.getTenantNo())
                .eq("entity_no", inv.getEntityNo()).in("message_type", "ISSUE", "VOID").eq("ref_no", invoiceNo));
        // 补记：服务商已有这张票 → 开立已送达；服务商已作废 → 作废也已送达（鲸跃收到的就是我们发的那份报文）
        var reconciled = new ArrayList<String>();
        for (var t : tasks) {
            boolean delivered = "ISSUE".equals(t.getMessageType()) ? status.state() != State.NOT_FOUND
                    : status.state() == State.VOIDED;
            if (delivered && !"SENT".equals(t.getStatus()) && transmissions.update(null,
                    new UpdateWrapper<IvcTransmission>().eq("id", t.getId()).ne("status", "SENT").set("status", "SENT")
                            .set("gateway_ref", t.getGatewayCode() + ":QUERY:" + status.rawCode())
                            .set("last_error", null).set("deadline_level", 2).set("updated_at", now)) == 1) {
                reconciled.add(t.getMessageType());
                log.info("INVOICE_TRANSMISSION_RECONCILED tenant={} entity={} type={} ref={} provider={}",
                        t.getTenantNo(), t.getEntityNo(), t.getMessageType(), invoiceNo, status.rawCode());
            }
        }
        State expected = "VOIDED".equals(inv.getStatus()) ? State.VOIDED
                : inv.getAllowedMinor() == 0 ? State.ISSUED
                : inv.getAllowedMinor() < inv.getTotalMinor() ? State.PARTIALLY_ALLOWED : State.FULLY_ALLOWED;
        String verdict;
        if (status.state() == expected) verdict = "CONSISTENT";
        else if (hasUnsent(inv)) verdict = "PENDING_UPLOAD";
        else {
            verdict = "MISMATCH";
            log.error("INVOICE_PROVIDER_MISMATCH tenant={} entity={} invoice={} fiscal={} local={} provider={}",
                    inv.getTenantNo(), inv.getEntityNo(), invoiceNo, inv.getFiscalNumber(), expected, status.rawCode());
        }
        return new ProviderCheckVO(status.state().name(), status.rawCode(), expected.name(), verdict, reconciled, now);
    }

    /** 这张票（含其折让）是否还有未送达的上传任务：有的话差异只是还没补传完。 */
    private boolean hasUnsent(IvcInvoice inv) {
        var refs = new ArrayList<String>();
        refs.add(inv.getInvoiceNo());
        allowances.selectList(new QueryWrapper<IvcAllowance>().eq("tenant_no", inv.getTenantNo())
                .eq("invoice_no", inv.getInvoiceNo())).forEach(a -> refs.add(a.getAllowanceNo()));
        return transmissions.selectCount(new QueryWrapper<IvcTransmission>().eq("tenant_no", inv.getTenantNo())
                .eq("entity_no", inv.getEntityNo()).in("ref_no", refs).ne("status", "SENT")) > 0;
    }

    @Override
    public UploadBacklogVO backlog(Scope scope) {
        var now = LocalDateTime.now(ZoneOffset.UTC);
        var open = transmissions.selectList(new QueryWrapper<IvcTransmission>()
                .select("status", "report_warn_at", "report_deadline_at")
                .eq("tenant_no", scope.tenantNo()).eq("entity_no", scope.entityNo()).eq("store_no", scope.storeNo())
                .ne("status", "SENT"));
        long pending = 0, held = 0, review = 0, dueSoon = 0, overdue = 0;
        LocalDateTime oldest = null;
        for (var t : open) {
            switch (t.getStatus()) {
                case HELD -> held++;
                case NEEDS_REVIEW -> review++;
                default -> pending++;
            }
            var deadline = t.getReportDeadlineAt();
            if (deadline == null) continue;
            if (!deadline.isAfter(now)) overdue++;
            else if (t.getReportWarnAt() != null && !t.getReportWarnAt().isAfter(now)) dueSoon++;
            if (oldest == null || deadline.isBefore(oldest)) oldest = deadline;
        }
        return new UploadBacklogVO(pending, held, review, dueSoon, overdue, oldest);
    }

    private void record(IvcTransmission t, GatewayReceipt receipt) {
        int attempts = t.getAttempts() + 1;
        var after = LocalDateTime.now(ZoneOffset.UTC);
        var update = new UpdateWrapper<IvcTransmission>().eq("id", t.getId()).eq("status", "SENDING")
                .set("attempts", attempts).set("updated_at", after);
        String error = receipt.error() == null ? "rejected" : receipt.error();
        String clipped = error.length() > 512 ? error.substring(0, 512) : error;
        switch (receipt.outcome()) {
            case ACCEPTED -> update.set("status", "SENT").set("gateway_ref", receipt.gatewayRef()).set("last_error", null)
                    .set("deadline_level", 2);   // 已送达：退出期限巡检
            case RETRYABLE -> update.set("status", "FAILED").set("last_error", clipped)
                    .set("next_attempt_at", after.plusMinutes(Math.min(60, 1L << Math.min(attempts - 1, 6))));
            case ACCOUNT_PROBLEM -> update.set("status", HELD).set("last_error", clipped);
            case FINAL -> {
                update.set("status", NEEDS_REVIEW).set("last_error", clipped);
                log.error("INVOICE_TRANSMISSION_NEEDS_REVIEW tenant={} entity={} type={} ref={} error={}",
                        t.getTenantNo(), t.getEntityNo(), t.getMessageType(), t.getRefNo(), clipped);
            }
        }
        transmissions.update(null, update);
    }

    /** 只有第一次把主体标为暂停的那个执行者打告警日志，避免同一问题刷屏。 */
    private void hold(IvcRegimeProfile profile, String reason) {
        var now = LocalDateTime.now(ZoneOffset.UTC);
        String clipped = reason == null ? "account problem" : reason.length() > 256 ? reason.substring(0, 256) : reason;
        if (profiles.update(null, new UpdateWrapper<IvcRegimeProfile>().eq("id", profile.getId())
                .isNull("provider_held_at").set("provider_hold_reason", clipped).set("provider_held_at", now)
                .set("updated_at", now)) == 1)
            log.error("INVOICE_PROVIDER_HOLD tenant={} entity={} gateway={} reason={}", profile.getTenantNo(),
                    profile.getEntityNo(), profile.getGatewayCode(), clipped);
    }

    private IvcRegimeProfile profileOf(IvcTransmission t) {
        return profiles.selectOne(new QueryWrapper<IvcRegimeProfile>().eq("tenant_no", t.getTenantNo())
                .eq("entity_no", t.getEntityNo()).eq("regime_code", t.getRegimeCode()).last("LIMIT 1"));
    }

    private GatewayReceipt send(InvoiceGateway gateway, IvcTransmission t, IvcRegimeProfile profile) {
        if (gateway == null) return GatewayReceipt.retryable("gateway not installed: " + t.getGatewayCode());
        InvoiceGateway.ProviderAccount account = null;
        if (gateway.profile().requiresAccount()) {
            account = accounts.of(profile).orElse(null);
            if (account == null) return GatewayReceipt.accountProblem("provider account not configured");
        }
        return gateway.send(new GatewayRequest(t.getRegimeCode(), t.getMessageType(), t.getRefNo(), t.getPayload(),
                account));
    }
}
