package ai.neargo.shop.invoice.service.impl;

import ai.neargo.shop.common.config.ModuleDataSources;
import ai.neargo.shop.common.BizException;
import ai.neargo.shop.common.BizKey;
import ai.neargo.shop.common.ErrorCode;
import ai.neargo.shop.common.PageData;
import ai.neargo.shop.invoice.dto.InvoiceApplicationDtos.ApplicationView;
import ai.neargo.shop.invoice.dto.InvoiceApplicationDtos.BuyerView;
import ai.neargo.shop.invoice.dto.InvoiceApplicationDtos.ChangeApplicationStateReq;
import ai.neargo.shop.invoice.dto.InvoiceApplicationDtos.EventView;
import ai.neargo.shop.invoice.dto.InvoiceApplicationDtos.IssueApplicationReq;
import ai.neargo.shop.invoice.dto.InvoiceApplicationDtos.ReviseApplicationReq;
import ai.neargo.shop.invoice.dto.InvoiceApplicationDtos.RevisionView;
import ai.neargo.shop.invoice.dto.InvoiceApplicationDtos.SubmitApplicationReq;
import ai.neargo.shop.invoice.dto.InvoiceDtos.BuyerReq;
import ai.neargo.shop.invoice.entity.IvcInvoiceApplication;
import ai.neargo.shop.invoice.entity.IvcInvoiceApplicationEvent;
import ai.neargo.shop.invoice.entity.IvcInvoiceApplicationRevision;
import ai.neargo.shop.invoice.mapper.InvoiceApplicationMappers.ApplicationMapper;
import ai.neargo.shop.invoice.legacy.InvoiceApplicationEventMapper;
import ai.neargo.shop.invoice.legacy.InvoiceHistory;
import ai.neargo.shop.invoice.mapper.InvoiceApplicationMappers.RevisionMapper;
import ai.neargo.shop.invoice.service.InvoiceApplicationService;
import ai.neargo.shop.invoice.service.InvoiceService;
import ai.neargo.shop.print.api.PrintHashes;
import ai.neargo.shop.spi.invoice.InvoicePort.Buyer;
import ai.neargo.shop.spi.invoice.InvoicePort.InvoiceReceipt;
import ai.neargo.shop.spi.invoice.InvoicePort.Scope;
import ai.neargo.shop.spi.invoice.InvoiceSourcePort;
import ai.neargo.shop.spi.invoice.InvoiceSourcePort.InvoiceSource;
import com.baomidou.mybatisplus.core.conditions.query.QueryWrapper;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.time.temporal.ChronoUnit;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.stream.Collectors;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import tools.jackson.databind.ObjectMapper;

@Service
public class InvoiceApplicationServiceImpl implements InvoiceApplicationService {

    private static final String SUBMITTED = "SUBMITTED";
    private static final String REJECTED = "REJECTED";
    private static final String CANCELLED = "CANCELLED";
    private static final String ISSUED = "ISSUED";

    private final ApplicationMapper applications;
    private final RevisionMapper revisions;
    private final InvoiceApplicationEventMapper events;
    private final InvoiceHistory history;
    private final InvoiceService invoices;
    private final Map<String, InvoiceSourcePort> sources;
    private final ObjectMapper json;

    public InvoiceApplicationServiceImpl(ApplicationMapper applications, RevisionMapper revisions,
            InvoiceApplicationEventMapper events, InvoiceHistory history,
            InvoiceService invoices, List<InvoiceSourcePort> sources, ObjectMapper json) {
        this.applications = applications;
        this.revisions = revisions;
        this.events = events;
        this.history = history;
        this.invoices = invoices;
        this.sources = sources.stream().flatMap(source -> source.sourceTypes().stream()
                .map(type -> Map.entry(type, source))).collect(Collectors.toUnmodifiableMap(Map.Entry::getKey, Map.Entry::getValue));
        this.json = json;
    }

    @Override
    @Transactional(transactionManager = ModuleDataSources.INVOICE_TX_MANAGER)
    public ApplicationView submit(Scope scope, SubmitApplicationReq request, String key) {
        var command = command("SUBMIT", key, List.of(request.sourceType(), request.sourceNo(), buyer(request.buyer())));
        var now = now();
        var reserved = new IvcInvoiceApplication();
        reserved.setTenantNo(scope.tenantNo());
        reserved.setEntityNo(scope.entityNo());
        reserved.setStoreNo(scope.storeNo());
        reserved.setApplicationNo(BizKey.next("IVA"));
        reserved.setSourceType(request.sourceType());
        reserved.setSourceNo(request.sourceNo());
        reserved.setCurrentRevision(1);
        reserved.setStatus(SUBMITTED);
        reserved.setCreatedBy(scope.actorNo());
        reserved.setCreatedAt(now);
        applications.reserve(reserved);
        var app = require(scoped(scope).eq("source_type", request.sourceType()).eq("source_no", request.sourceNo())
                .last("FOR UPDATE"));
        var prior = replay(scope, app, command);
        if (prior != null) return view(app, prior);
        if (!reserved.getApplicationNo().equals(app.getApplicationNo())) {
            // 事件在主库、提交后才记，可能缺失：同一来源、同一买方的重复提交按业务状态回原申请
            if (SUBMITTED.equals(app.getStatus()) && sameBuyer(app, buyer(request.buyer()))) return view(app);
            throw BizException.of(ErrorCode.CONFLICT);
        }
        appendRevision(scope, app, buyer(request.buyer()), now);
        appendEvent(scope, app, command, null, null, now);
        return view(app);
    }

    @Override
    public ApplicationView get(Scope scope, String applicationNo) {
        return view(require(scoped(scope).eq("application_no", applicationNo)));
    }

    @Override
    public PageData<ApplicationView> list(Scope scope, String sourceType, String sourceNo, String status,
            int page, int size) {
        var rows = applications.selectPage(new Page<>(page, size), scoped(scope)
                .eq(sourceType != null, "source_type", sourceType)
                .eq(sourceNo != null, "source_no", sourceNo)
                .eq(status != null, "status", status).orderByDesc("id"));
        return new PageData<>(rows.getRecords().stream().map(InvoiceApplicationServiceImpl::view).toList(),
                rows.getTotal(), page, size);
    }

    @Override
    public List<RevisionView> revisions(Scope scope, String applicationNo) {
        var app = require(scoped(scope).eq("application_no", applicationNo));
        return revisions.selectList(revisionScope(app).orderByAsc("revision_no")).stream()
                .map(InvoiceApplicationServiceImpl::revisionView).toList();
    }

    @Override
    public List<EventView> events(Scope scope, String applicationNo) {
        var app = require(scoped(scope).eq("application_no", applicationNo));
        return events.selectList(eventScope(scope).eq("application_no", app.getApplicationNo()).orderByAsc("id"))
                .stream().map(e -> new EventView(e.getRevisionNo(), e.getAction(), e.getFromStatus(), e.getToStatus(),
                        e.getActorNo(), e.getReason(), e.getRelatedInvoiceNo(), e.getOccurredAt())).toList();
    }

    @Override
    @Transactional(transactionManager = ModuleDataSources.INVOICE_TX_MANAGER)
    public ApplicationView revise(Scope scope, String applicationNo, ReviseApplicationReq request, String key) {
        var app = lock(scope, applicationNo);
        var command = command("REVISE", key, List.of(applicationNo, request.expectedRevision(), buyer(request.buyer()),
                request.changeReason()));
        var prior = replay(scope, app, command);
        if (prior != null) return view(app, prior);
        requireRevision(app, request.expectedRevision());
        if (ISSUED.equals(app.getStatus())) throw BizException.of(ErrorCode.INVOICE_STATE_INVALID);
        var from = app.getStatus();
        var now = now();
        app.setCurrentRevision(Math.incrementExact(app.getCurrentRevision()));
        app.setStatus(SUBMITTED);
        app.setRejectReason(null);
        app.setUpdatedBy(scope.actorNo());
        app.setUpdatedAt(now);
        appendRevision(scope, app, buyer(request.buyer()), now);
        applications.updateById(app);
        appendEvent(scope, app, command, from, request.changeReason(), now);
        return view(app);
    }

    @Override
    @Transactional(transactionManager = ModuleDataSources.INVOICE_TX_MANAGER)
    public ApplicationView reject(Scope scope, String applicationNo, ChangeApplicationStateReq request, String key) {
        return changeState(scope, applicationNo, request, key, "REJECT", REJECTED);
    }

    @Override
    @Transactional(transactionManager = ModuleDataSources.INVOICE_TX_MANAGER)
    public ApplicationView cancel(Scope scope, String applicationNo, ChangeApplicationStateReq request, String key) {
        return changeState(scope, applicationNo, request, key, "CANCEL", CANCELLED);
    }

    private ApplicationView changeState(Scope scope, String applicationNo, ChangeApplicationStateReq request,
            String key, String action, String target) {
        var app = lock(scope, applicationNo);
        var command = command(action, key, List.of(applicationNo, request));
        var prior = replay(scope, app, command);
        if (prior != null) return view(app, prior);
        requireRevision(app, request.expectedRevision());
        if (target.equals(app.getStatus())) return view(app);
        requireSubmitted(app);
        var now = now();
        app.setStatus(target);
        app.setRejectReason(REJECTED.equals(target) ? request.reason() : null);
        app.setOperatorNo(scope.actorNo());
        app.setUpdatedBy(scope.actorNo());
        app.setUpdatedAt(now);
        applications.updateById(app);
        appendEvent(scope, app, command, SUBMITTED, request.reason(), now);
        return view(app);
    }

    @Override
    @Transactional(transactionManager = ModuleDataSources.INVOICE_TX_MANAGER)
    public InvoiceReceipt issue(Scope scope, String applicationNo, IssueApplicationReq request, String key) {
        var app = lock(scope, applicationNo);
        var command = command("ISSUE", key, List.of(applicationNo, request));
        var prior = replay(scope, app, command);
        if (prior != null) return receipt(scope, prior.getRelatedInvoiceNo());
        requireRevision(app, request.expectedRevision());
        if (ISSUED.equals(app.getStatus())) return receipt(scope, app.getIssuedInvoiceNo());
        requireSubmitted(app);
        var version = revisions.selectOne(revisionScope(app).eq("revision_no", app.getCurrentRevision()));
        var buyer = new Buyer(version.getBuyerTaxId(), version.getBuyerName(), version.getCarrierType(),
                version.getCarrierId(), version.getDonateCode());
        var result = invoices.issueFromApplication(scope, app.getSourceType(), app.getSourceNo(), buyer,
                applicationNo, version.getRevisionNo(), version.getSourceFingerprint());
        var now = now();
        app.setStatus(ISSUED);
        app.setIssuedInvoiceNo(result.invoiceNo());
        app.setIssuedAt(now);
        app.setOperatorNo(scope.actorNo());
        app.setUpdatedBy(scope.actorNo());
        app.setUpdatedAt(now);
        applications.updateById(app);
        appendEvent(scope, app, command, SUBMITTED, null, now);
        return result;
    }

    private InvoiceReceipt receipt(Scope scope, String invoiceNo) {
        var invoice = invoices.get(scope, invoiceNo);
        return new InvoiceReceipt(invoice.invoiceNo(), invoice.regimeCode(), invoice.fiscalNumber(),
                invoice.periodCode(), invoice.randomCode(), invoice.status(), invoice.printMark(), invoice.currency(),
                invoice.totalMinor());
    }

    private void appendRevision(Scope scope, IvcInvoiceApplication app, Buyer buyer, LocalDateTime now) {
        var port = sources.get(app.getSourceType());
        if (port == null) throw BizException.of(ErrorCode.INVOICE_SOURCE_NOT_READY);
        var source = port.load(scope, app.getSourceType(), app.getSourceNo());
        var revision = new IvcInvoiceApplicationRevision();
        revision.setTenantNo(scope.tenantNo());
        revision.setApplicationNo(app.getApplicationNo());
        revision.setRevisionNo(app.getCurrentRevision());
        revision.setBuyerTaxId(buyer.taxId());
        revision.setBuyerName(buyer.name());
        revision.setCarrierType(buyer.carrierType());
        revision.setCarrierId(buyer.carrierId());
        revision.setDonateCode(buyer.donateCode());
        revision.setCurrency(source.currency());
        revision.setSourceAmountMinor(source.totalMinor());
        revision.setSourceFingerprint(sourceFingerprint(json, source));
        revision.setSourceSnapshot(json.writeValueAsString(source));
        revision.setSubmittedBy(scope.actorNo());
        revision.setSubmittedAt(now);
        revisions.insert(revision);
    }

    static String sourceFingerprint(ObjectMapper json, InvoiceSource source) {
        return PrintHashes.sha256(json.writeValueAsString(source));
    }

    private Command command(String action, String key, Object payload) {
        return new Command(action, PrintHashes.sha256(key), PrintHashes.sha256(json.writeValueAsString(payload)));
    }

    private IvcInvoiceApplicationEvent replay(Scope scope, IvcInvoiceApplication app, Command command) {
        var event = events.selectOne(eventScope(scope).eq("action", command.action())
                .eq("command_key_hash", command.keyHash()));
        if (event != null && (!app.getApplicationNo().equals(event.getApplicationNo())
                || !command.payloadHash().equals(event.getCommandPayloadHash()))) {
            throw BizException.of(ErrorCode.CONFLICT);
        }
        return event;
    }

    private void appendEvent(Scope scope, IvcInvoiceApplication app, Command command, String from, String reason,
            LocalDateTime now) {
        var event = new IvcInvoiceApplicationEvent();
        event.setTenantNo(scope.tenantNo());
        event.setEntityNo(scope.entityNo());
        event.setStoreNo(scope.storeNo());
        event.setApplicationNo(app.getApplicationNo());
        event.setRevisionNo(app.getCurrentRevision());
        event.setAction(command.action());
        event.setFromStatus(from);
        event.setToStatus(app.getStatus());
        event.setActorNo(scope.actorNo());
        event.setReason(reason);
        event.setCommandKeyHash(command.keyHash());
        event.setCommandPayloadHash(command.payloadHash());
        event.setRelatedInvoiceNo(app.getIssuedInvoiceNo());
        event.setOccurredAt(now);
        // 事件表在主库：申请提交之后再记。同一把键被并发用在另一个来源上时，这里只会少一条事件，
        // 不再能回滚业务；两边的业务写各自靠来源唯一键与版本号保护。
        history.afterCommit("application." + command.action(), () -> events.insert(event));
    }

    private boolean sameBuyer(IvcInvoiceApplication app, Buyer buyer) {
        var version = revisions.selectOne(revisionScope(app).eq("revision_no", app.getCurrentRevision()));
        return version != null && Objects.equals(version.getBuyerTaxId(), buyer.taxId())
                && Objects.equals(version.getBuyerName(), buyer.name())
                && Objects.equals(version.getCarrierType(), buyer.carrierType())
                && Objects.equals(version.getCarrierId(), buyer.carrierId())
                && Objects.equals(version.getDonateCode(), buyer.donateCode());
    }

    private IvcInvoiceApplication lock(Scope scope, String applicationNo) {
        return require(scoped(scope).eq("application_no", applicationNo).last("FOR UPDATE"));
    }

    private IvcInvoiceApplication require(QueryWrapper<IvcInvoiceApplication> query) {
        var app = applications.selectOne(query);
        if (app == null) throw BizException.of(ErrorCode.NOT_FOUND);
        return app;
    }

    private static QueryWrapper<IvcInvoiceApplication> scoped(Scope scope) {
        return new QueryWrapper<IvcInvoiceApplication>().eq("tenant_no", scope.tenantNo())
                .eq("entity_no", scope.entityNo()).eq("store_no", scope.storeNo());
    }

    private static QueryWrapper<IvcInvoiceApplicationRevision> revisionScope(IvcInvoiceApplication app) {
        return new QueryWrapper<IvcInvoiceApplicationRevision>().eq("tenant_no", app.getTenantNo())
                .eq("application_no", app.getApplicationNo());
    }

    private static QueryWrapper<IvcInvoiceApplicationEvent> eventScope(Scope scope) {
        return new QueryWrapper<IvcInvoiceApplicationEvent>().eq("tenant_no", scope.tenantNo())
                .eq("entity_no", scope.entityNo()).eq("store_no", scope.storeNo());
    }

    private static void requireRevision(IvcInvoiceApplication app, int expected) {
        if (app.getCurrentRevision() != expected) throw BizException.of(ErrorCode.CONFLICT);
    }

    private static void requireSubmitted(IvcInvoiceApplication app) {
        if (!SUBMITTED.equals(app.getStatus())) throw BizException.of(ErrorCode.INVOICE_STATE_INVALID);
    }

    private static LocalDateTime now() {
        return LocalDateTime.now(ZoneOffset.UTC).truncatedTo(ChronoUnit.MILLIS);
    }

    private static Buyer buyer(BuyerReq request) {
        return request == null ? Buyer.consumer()
                : new Buyer(request.taxId(), request.name(), request.carrierType(), request.carrierId(), request.donateCode());
    }

    private static ApplicationView view(IvcInvoiceApplication app) {
        return new ApplicationView(app.getApplicationNo(), app.getSourceType(), app.getSourceNo(), app.getCurrentRevision(),
                app.getStatus(), app.getIssuedInvoiceNo(), app.getCreatedAt(), app.getUpdatedAt());
    }

    private static ApplicationView view(IvcInvoiceApplication app, IvcInvoiceApplicationEvent event) {
        return new ApplicationView(app.getApplicationNo(), app.getSourceType(), app.getSourceNo(), event.getRevisionNo(),
                event.getToStatus(), event.getRelatedInvoiceNo(), app.getCreatedAt(), event.getOccurredAt());
    }

    private static RevisionView revisionView(IvcInvoiceApplicationRevision revision) {
        var carrier = revision.getCarrierId();
        var masked = carrier == null ? null : "••••" + carrier.substring(Math.max(0, carrier.length() - 4));
        var buyer = new BuyerView(revision.getBuyerTaxId(), revision.getBuyerName(), revision.getCarrierType(), masked,
                revision.getDonateCode());
        return new RevisionView(revision.getRevisionNo(), buyer, revision.getCurrency(), revision.getSourceAmountMinor(),
                revision.getSourceFingerprint(), revision.getSubmittedBy(), revision.getSubmittedAt());
    }

    private record Command(String action, String keyHash, String payloadHash) {}
}
