package ai.neargo.shop.invoice.service.impl;

import ai.neargo.shop.common.BizException;
import ai.neargo.shop.common.ErrorCode;
import ai.neargo.shop.invoice.dto.InvoiceDtos.ProviderCheckVO;
import ai.neargo.shop.invoice.dto.InvoiceDtos.UploadBacklogVO;
import ai.neargo.shop.invoice.entity.IvcInvoice;
import ai.neargo.shop.invoice.gateway.ProviderException;
import ai.neargo.shop.invoice.gateway.QueryCapability;
import ai.neargo.shop.invoice.gateway.RevokeStatusCapability;
import ai.neargo.shop.invoice.gateway.QueryCapability.ProviderInvoiceStatus.State;
import ai.neargo.shop.invoice.mapper.InvoiceMappers.InvoiceMapper;
import ai.neargo.shop.invoice.regime.InvoiceRegimes;
import ai.neargo.shop.invoice.entity.IvcAllowance;
import ai.neargo.shop.invoice.entity.IvcRegimeProfile;
import ai.neargo.shop.invoice.entity.IvcTransmission;
import ai.neargo.shop.invoice.entity.IvcTransmissionAttempt;
import ai.neargo.shop.invoice.gateway.InvoiceGateway;
import ai.neargo.shop.invoice.gateway.InvoiceGateway.GatewayReceipt;
import ai.neargo.shop.invoice.gateway.InvoiceGateway.GatewayRequest;
import ai.neargo.shop.invoice.gateway.InvoiceGateways;
import ai.neargo.shop.invoice.mapper.InvoiceMappers.AllowanceMapper;
import ai.neargo.shop.invoice.mapper.InvoiceMappers.RegimeProfileMapper;
import ai.neargo.shop.invoice.mapper.InvoiceMappers.TransmissionMapper;
import ai.neargo.shop.invoice.service.InvoiceChannelService;
import ai.neargo.shop.invoice.service.InvoiceTransmissionService;
import org.springframework.beans.factory.annotation.Autowired;
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
import java.time.Duration;
import java.time.Instant;

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
    /** 状态列是 10 位，库里与接口上的取值都是 REVIEW（原 NEEDS_REVIEW）。 */
    static final String NEEDS_REVIEW = "REVIEW";

    private final TransmissionMapper transmissions;
    private final RegimeProfileMapper profiles;
    private final AllowanceMapper allowances;
    private final InvoiceProviderAccounts accounts;
    private final InvoiceGateways gateways;
    private final InvoiceMapper invoices;
    private final InvoiceRegimes regimes;
    private final InvoiceTransmissionJournal journal;

    private record AccountContext(IvcRegimeProfile profile, Long revision) {}

    public InvoiceTransmissionServiceImpl(TransmissionMapper transmissions, RegimeProfileMapper profiles,
            AllowanceMapper allowances, InvoiceProviderAccounts accounts, InvoiceGateways gateways,
            InvoiceMapper invoices, InvoiceRegimes regimes, InvoiceTransmissionJournal journal) {
        this.transmissions = transmissions;
        this.profiles = profiles;
        this.allowances = allowances;
        this.invoices = invoices;
        this.regimes = regimes;
        this.journal = journal;
        this.accounts = accounts;
        this.gateways = gateways;
    }

    @Override
    public int transmitDue(int limit) {
        var now = LocalDateTime.now(ZoneOffset.UTC);
        // SENDING 超过 10 分钟没回音 = 上一个执行者在发送途中退出；报文幂等由通道按发票号识别，重发安全
        var due = transmissions.selectList(new QueryWrapper<IvcTransmission>()
                .and(w -> w.in("status", "PENDING", "FAILED").le("next_attempt_at", now)
                        .or(x -> x.eq("status", "SENDING").le("updated_at", now.minusMinutes(InvoiceTransmissionJournal.STALE_MINUTES))))
                .orderByAsc("next_attempt_at", "id").last("LIMIT " + limit));
        int sent = 0;
        // 同一轮里同一主体的配置只查一次；本轮刚被暂停的主体，后面的任务直接转 HELD
        var profileCache = new HashMap<String, AccountContext>();
        for (var t : due) {
            if (transmitOne(t, profileCache, null)) sent++;
        }
        return sent;
    }

    /**
     * 同步上传：开立 / 作废 / 折让提交之后，在同一个请求里把这张票（含其折让）待传的任务立刻送出。
     * 与 {@link #transmitDue} 共用认领与记账：先条件更新成 SENDING 再发，定时任务与本方法同时碰到同一条也只发一次；
     * 失败照常记成可重试，留给定时任务或人工重传。按任务 id 顺序发，开立（C0401）总在作废（C0501）之前。
     */
    @Override
    public int transmitNow(Scope scope, String invoiceNo) {
        var refs = new ArrayList<String>();
        refs.add(invoiceNo);
        allowances.selectList(new QueryWrapper<IvcAllowance>().eq("tenant_no", scope.tenantNo())
                        .eq("entity_no", scope.entityNo()).eq("invoice_no", invoiceNo))
                .forEach(a -> refs.add(a.getAllowanceNo()));
        var due = transmissions.selectList(new QueryWrapper<IvcTransmission>().eq("tenant_no", scope.tenantNo())
                .eq("entity_no", scope.entityNo()).eq("store_no", scope.storeNo())
                .in("ref_no", refs).in("status", "PENDING", "FAILED")
                .orderByAsc("id"));
        int sent = 0;
        var profileCache = new HashMap<String, AccountContext>();
        for (var t : due) {
            if (transmitOne(t, profileCache, scope.actorNo())) sent++;
        }
        return sent;
    }

    /** The committed claim fences this network attempt from stale workers and concurrent checks. */
    private boolean transmitOne(IvcTransmission task, HashMap<String, AccountContext> profileCache, String actorNo) {
        var gateway = gateways.find(task.getGatewayCode()).orElse(null);
        var account = gateway != null && gateway.profile().requiresAccount()
                ? profileCache.computeIfAbsent(task.getTenantNo() + "|" + task.getEntityNo() + "|" + task.getRegimeCode()
                        + "|" + task.getChannelAccountNo() + "|" + task.getGatewayCode(),
                        k -> accountContext(task))
                : new AccountContext(null, null);
        var profile = account.profile();
        var now = LocalDateTime.now(ZoneOffset.UTC);
        if (profile != null && profile.getProviderHeldAt() != null) {
            journal.markHeld(task, now);
            return false;
        }
        var claim = journal.claim(task, actorNo, account.revision(), now);
        if (claim == null) return false;

        GatewayReceipt receipt;
        String auditOutcome;
        String safeCode = null;
        try {
            receipt = send(gateway, task, profile);
            auditOutcome = receipt.outcome().name();
        } catch (RuntimeException e) {
            // Keep retry policy and task diagnostics while avoiding provider text in the audit.
            receipt = GatewayReceipt.retryable(String.valueOf(e.getMessage()));
            auditOutcome = "UNKNOWN";
            safeCode = "PROVIDER_EXCEPTION";
        }
        var result = receipt;
        var after = LocalDateTime.now(ZoneOffset.UTC);
        boolean completed = journal.complete(claim, auditOutcome, safeCode, after,
                () -> record(task, claim, result, after));
        if (completed && result.outcome() == GatewayReceipt.Outcome.ACCOUNT_PROBLEM && profile != null) {
            if (task.getChannelAccountNo() == null) hold(profile, result.error());
            profile.setProviderHeldAt(now);
        }
        return completed && result.accepted();
    }

    @Override
    public int releaseHeld(String tenantNo, String entityNo, String regimeCode) {
        var now = LocalDateTime.now(ZoneOffset.UTC);
        profiles.update(null, new UpdateWrapper<IvcRegimeProfile>().eq("tenant_no", tenantNo)
                .eq("entity_no", entityNo).eq("regime_code", regimeCode)
                .set("provider_hold_reason", null).set("provider_held_at", null).set("updated_at", now));
        int released = transmissions.update(null, new UpdateWrapper<IvcTransmission>().eq("tenant_no", tenantNo)
                .eq("entity_no", entityNo).eq("regime_code", regimeCode).isNull("channel_account_no").eq("status", HELD)
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
                .eq("entity_no", scope.entityNo()).eq("store_no", scope.storeNo()).in("ref_no", refs)
                .and(w -> w.eq("status", NEEDS_REVIEW)
                        .or(bound -> bound.isNotNull("channel_account_no").eq("status", HELD)))
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
        // 查这张票开立时冻结的服务商与账户。
        var issued = transmissions.selectOne(new QueryWrapper<IvcTransmission>().eq("tenant_no", inv.getTenantNo())
                .eq("entity_no", inv.getEntityNo()).eq("message_type", "ISSUE").eq("ref_no", invoiceNo).last("LIMIT 1"));
        var context = issued == null ? new AccountContext(null, null) : accountContext(issued);
        var provider = issued == null ? null
                : gateways.capability(issued.getGatewayCode(), QueryCapability.class).orElse(null);
        if (provider == null) throw BizException.of(ErrorCode.INVOICE_STATE_INVALID);
        var account = accounts.of(context.profile()).orElseThrow(
                () -> BizException.of(ErrorCode.INVOICE_PROVIDER_REJECTED, "account_not_configured"));
        var day = LocalDate.ofInstant(inv.getIssuedAt().toInstant(ZoneOffset.UTC),
                regimes.require(inv.getRegimeCode()).zone());
        var query = journal.startQuery(issued, "PROVIDER_CHECK", invoiceNo, scope.actorNo(), context.revision(),
                LocalDateTime.now(ZoneOffset.UTC));
        QueryCapability.ProviderInvoiceStatus status;
        try {
            status = provider.queryStatus(account, inv.getFiscalNumber(), day);
        } catch (ProviderException e) {
            finishFailedQuery(query, e.kind());
            throw BizException.of(ErrorCode.INVOICE_PROVIDER_REJECTED, e.code());
        } catch (RuntimeException e) {
            journal.finishQuery(query, "UNKNOWN", "PROVIDER_EXCEPTION", LocalDateTime.now(ZoneOffset.UTC));
            throw e;
        }
        var now = LocalDateTime.now(ZoneOffset.UTC);
        journal.finishQuery(query, "CHECKED", status.state().name(), now);
        var tasks = transmissions.selectList(new QueryWrapper<IvcTransmission>().eq("tenant_no", inv.getTenantNo())
                .eq("entity_no", inv.getEntityNo()).in("message_type", "ISSUE", "VOID").eq("ref_no", invoiceNo));
        // 补记：服务商已有这张票 → 开立已送达；服务商已作废 → 作废也已送达（鲸跃收到的就是我们发的那份报文）
        var reconciled = new ArrayList<String>();
        for (var t : tasks) {
            boolean delivered = "ISSUE".equals(t.getMessageType()) ? status.state() != State.NOT_FOUND
                    : status.state() == State.VOIDED;
            if (delivered && !"SENT".equals(t.getStatus())
                    && journal.reconcile(t, t.getGatewayCode() + ":QUERY:" + status.rawCode(), now)) {
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

    private boolean record(IvcTransmission t, IvcTransmissionAttempt claim, GatewayReceipt receipt, LocalDateTime after) {
        int attempts = t.getAttempts() + 1;
        var update = new UpdateWrapper<IvcTransmission>().eq("id", t.getId()).eq("status", "SENDING")
                .eq("attempt_sequence", claim.getAttemptIndex()).eq("attempts", t.getAttempts())
                .set("attempts", attempts).set("updated_at", after);
        String error = receipt.error() == null ? "rejected" : receipt.error();
        String clipped = error.length() > 512 ? error.substring(0, 512) : error;
        switch (receipt.outcome()) {
            case ACCEPTED -> update.set("status", "SENT").set("gateway_ref", receipt.gatewayRef()).set("last_error", null)
                    .set("deadline_level", 2);   // 已送达：退出期限巡检
            case RETRYABLE -> update.set("status", "FAILED").set("last_error", clipped)
                    .set("next_attempt_at", after.plusMinutes(Math.min(60, 1L << Math.min(attempts - 1, 6))));
            case ACCOUNT_PROBLEM -> update.set("status", HELD).set("last_error", clipped);
            case FINAL -> update.set("status", NEEDS_REVIEW).set("last_error", clipped);
        }
        if (transmissions.update(null, update) != 1) return false;
        if (receipt.outcome() == GatewayReceipt.Outcome.FINAL)
            log.error("INVOICE_TRANSMISSION_NEEDS_REVIEW tenant={} entity={} type={} ref={} error={}",
                    t.getTenantNo(), t.getEntityNo(), t.getMessageType(), t.getRefNo(), clipped);
        // 注销被服务商拒收（终态）：发票回到已开立，可改正后再次注销（TDD-发票-注销与同号重开 §3.2）
        if ("REVOKE".equals(t.getMessageType()) && receipt.outcome() == GatewayReceipt.Outcome.FINAL)
            invoices.update(null, new UpdateWrapper<IvcInvoice>().eq("tenant_no", t.getTenantNo())
                    .eq("invoice_no", t.getRefNo()).eq("status", "REVOKING").set("status", "ISSUED")
                    .set("revoke_error", clipped.length() > 255 ? clipped.substring(0, 255) : clipped)
                    .set("updated_at", after));
        return true;
    }

    @Override
    public int[] checkRevocations(Instant now, Duration interval, Duration pendingAlert,
            Duration reissueAlertBefore) {
        var at = LocalDateTime.ofInstant(now, ZoneOffset.UTC);
        int done = 0, failed = 0, alerts = 0;
        // ① 注销中、注销报文已送达、距上次检查超过 interval 的，向服务商查进度
        for (var inv : invoices.selectList(new QueryWrapper<IvcInvoice>().eq("status", "REVOKING")
                .le("updated_at", at.minus(interval)).orderByAsc("revoke_requested_at").last("LIMIT 200"))) {
            var t = transmissions.selectOne(new QueryWrapper<IvcTransmission>().eq("tenant_no", inv.getTenantNo())
                    .eq("message_type", "REVOKE").eq("ref_no", inv.getInvoiceNo()));
            if (t == null || !"SENT".equals(t.getStatus())) continue;
            var capability = gateways.capability(t.getGatewayCode(), RevokeStatusCapability.class).orElse(null);
            var context = accountContext(t);
            var account = accounts.of(context.profile()).orElse(null);
            if (capability == null || account == null) continue;
            var query = journal.startQuery(t, "REVOCATION_CHECK", inv.getInvoiceNo(), null, context.revision(),
                    LocalDateTime.now(ZoneOffset.UTC));
            RevokeStatusCapability.RevokeState state;
            try {
                state = capability.revokeStatus(account, inv.getFiscalNumber(),
                        LocalDate.ofInstant(inv.getIssuedAt().toInstant(ZoneOffset.UTC), regimes.require(inv.getRegimeCode()).zone()));
            } catch (ProviderException e) {
                finishFailedQuery(query, e.kind());
                log.warn("INVOICE_REVOKE_STATUS_FAILED tenant={} invoice={} code={}", inv.getTenantNo(),
                        inv.getInvoiceNo(), e.code());
                continue;
            } catch (RuntimeException e) {
                journal.finishQuery(query, "UNKNOWN", "PROVIDER_EXCEPTION", LocalDateTime.now(ZoneOffset.UTC));
                throw e;
            }
            journal.finishQuery(query, "CHECKED", state.name(), LocalDateTime.now(ZoneOffset.UTC));
            var base = new UpdateWrapper<IvcInvoice>().eq("id", inv.getId()).eq("status", "REVOKING");
            switch (state) {
                case DONE -> {
                    // 号码与来源单一起释放：可同号重开，也可按来源重新开立
                    if (invoices.update(null, base.set("status", "REVOKED").set("active_source_key", null)
                            .set("active_fiscal_key", null).set("revoked_at", at).set("updated_at", at)) == 1) {
                        done++;
                        log.info("INVOICE_REVOKED tenant={} invoice={} fiscal={}", inv.getTenantNo(),
                                inv.getInvoiceNo(), inv.getFiscalNumber());
                    }
                }
                case FAILED -> {
                    if (invoices.update(null, base.set("status", "ISSUED")
                            .set("revoke_error", "provider revoke failed").set("updated_at", at)) == 1) {
                        failed++;
                        log.error("INVOICE_REVOKE_FAILED tenant={} invoice={} fiscal={}", inv.getTenantNo(),
                                inv.getInvoiceNo(), inv.getFiscalNumber());
                    }
                }
                case PENDING -> {
                    boolean overdue = inv.getRevokeRequestedAt() != null
                            && !inv.getRevokeRequestedAt().isAfter(at.minus(pendingAlert))
                            && (inv.getRevokeAlerted() == null || inv.getRevokeAlerted() < 1);
                    invoices.update(null, base.set("updated_at", at).set(overdue, "revoke_alerted", 1));
                    if (overdue) {
                        alerts++;
                        log.error("INVOICE_REVOKE_PENDING_TOO_LONG tenant={} invoice={} fiscal={} since={}",
                                inv.getTenantNo(), inv.getInvoiceNo(), inv.getFiscalNumber(), inv.getRevokeRequestedAt());
                    }
                }
            }
        }
        // ② 已注销、未重开、期末将至：提醒一次（不重开的号码期末作空白号码上报）
        for (var inv : invoices.selectList(new QueryWrapper<IvcInvoice>().eq("status", "REVOKED")
                .isNull("revised_to_invoice_no").lt("revoke_alerted", 2).last("LIMIT 200"))) {
            var end = regimes.require(inv.getRegimeCode()).periodEnd(inv.getPeriodCode());
            if (end == null || now.isBefore(end.minus(reissueAlertBefore)) || !now.isBefore(end)) continue;
            invoices.update(null, new UpdateWrapper<IvcInvoice>().eq("id", inv.getId()).set("revoke_alerted", 2));
            alerts++;
            log.warn("INVOICE_REVOKED_NOT_REISSUED tenant={} invoice={} fiscal={} periodEnd={}", inv.getTenantNo(),
                    inv.getInvoiceNo(), inv.getFiscalNumber(), end);
        }
        return new int[] {done, failed, alerts};
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

    @Autowired(required = false)
    private InvoiceChannelService channels;

    private AccountContext accountContext(IvcTransmission task) {
        if (task.getChannelAccountNo() != null) {
            return channels == null ? new AccountContext(null, null)
                    : channels.historical(task.getTenantNo(), task.getEntityNo(), task.getChannelAccountNo())
                            .filter(account -> task.getGatewayCode().equals(account.channelCode()))
                            .map(account -> new AccountContext(account.profile(), account.accountRevision()))
                            .orElseGet(() -> new AccountContext(null, null));
        }
        return new AccountContext(profiles.selectOne(new QueryWrapper<IvcRegimeProfile>()
                .eq("tenant_no", task.getTenantNo()).eq("entity_no", task.getEntityNo())
                .eq("regime_code", task.getRegimeCode()).last("LIMIT 1")), null);
    }

    private void finishFailedQuery(IvcTransmissionAttempt query, ProviderException.Kind kind) {
        var outcome = kind == ProviderException.Kind.UNAVAILABLE ? "UNKNOWN" : "FAILED";
        journal.finishQuery(query, outcome, kind.name(), LocalDateTime.now(ZoneOffset.UTC));
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
