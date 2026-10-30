package ai.neargo.shop.commerce.service.impl;

import ai.neargo.shop.commerce.dto.InboundContracts;
import ai.neargo.shop.commerce.entity.CimAccount;
import ai.neargo.shop.commerce.entity.CimAuthorizationRevision;
import ai.neargo.shop.commerce.entity.CimConnectionRevision;
import ai.neargo.shop.commerce.entity.CimInboundEvent;
import ai.neargo.shop.commerce.entity.CimInboundReceipt;
import ai.neargo.shop.commerce.entity.CimProductProfile;
import ai.neargo.shop.commerce.entity.CimProvider;
import ai.neargo.shop.commerce.entity.CimStoreConnection;
import ai.neargo.shop.commerce.mapper.CommerceMappers.InboundEventMapper;
import ai.neargo.shop.commerce.mapper.CommerceMappers.InboundReceiptMapper;
import ai.neargo.shop.commerce.mapper.CommerceMappers.ProviderMapper;
import ai.neargo.shop.commerce.policy.ChannelIdentity;
import ai.neargo.shop.commerce.policy.CommerceValidation;
import ai.neargo.shop.commerce.service.ChannelInboundService;
import ai.neargo.shop.common.BizException;
import ai.neargo.shop.common.BizKey;
import ai.neargo.shop.common.ErrorCode;
import ai.neargo.shop.common.PageData;
import ai.neargo.shop.spi.commerce.ChannelInboundGateway;
import ai.neargo.shop.spi.commerce.ChannelInboundHandler;
import ai.neargo.shop.spi.commerce.ChannelRecoveryPort;

import com.baomidou.mybatisplus.core.toolkit.Wrappers;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.util.Base64;
import java.util.Collection;
import java.util.HashMap;
import java.util.HexFormat;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.function.Supplier;

/**
 * Trusted endpoint resolution precedes verification; a connection lock serializes first arrival,
 * duplicate and conflict.
 */
public final class ChannelInboundServiceImpl implements ChannelInboundService, ChannelRecoveryPort {
    private static final Logger LOG = LoggerFactory.getLogger(ChannelInboundServiceImpl.class);
    private final int retentionDays;
    private Supplier<List<ChannelInboundHandler>> handlers = List::of;

    /** Resolve handlers lazily to avoid order/receipt service construction cycles. */
    public void handlers(Supplier<List<ChannelInboundHandler>> value) {
        this.handlers = value;
    }

    private final CatalogPersistence db;
    private final InboundEventMapper events;
    private final InboundReceiptMapper receipts;
    private final ProviderMapper providers;
    private final Map<String, ChannelInboundGateway> gateways;

    public ChannelInboundServiceImpl(
            CatalogPersistence db,
            InboundEventMapper events,
            InboundReceiptMapper receipts,
            ProviderMapper providers,
            Collection<ChannelInboundGateway> gateways) {
        this(db, events, receipts, providers, gateways, 30);
    }

    public ChannelInboundServiceImpl(
            CatalogPersistence db,
            InboundEventMapper events,
            InboundReceiptMapper receipts,
            ProviderMapper providers,
            Collection<ChannelInboundGateway> gateways,
            int retentionDays) {
        if (retentionDays < 1 || retentionDays > 3650)
            throw new IllegalArgumentException("Callback retention days");
        this.retentionDays = retentionDays;
        this.db = db;
        this.events = events;
        this.receipts = receipts;
        this.providers = providers;
        var index = new HashMap<String, ChannelInboundGateway>();
        for (var gateway : gateways)
            if (index.putIfAbsent(gateway.profileNo(), gateway) != null)
                throw new IllegalStateException("Duplicate inbound profile");
        this.gateways = Map.copyOf(index);
    }

    @Override
    public ChannelInboundGateway.Ack receive(
            String providerCode, String endpointNo, ChannelInboundGateway.RawMessage message) {
        CommerceValidation.text(providerCode, 32);
        CommerceValidation.text(endpointNo, 32);
        if (message == null
                || message.bytes().length == 0
                || message.bytes().length > 1024 * 1024
                || message.headers().size() > 32
                || message.receivedAt() == null) throw BizException.of(ErrorCode.BAD_REQUEST);
        for (var header : message.headers().entrySet()) {
            CommerceValidation.text(header.getKey(), 128);
            CommerceValidation.text(header.getValue(), 2048);
        }
        var before = db.commands.query(() -> endpoint(providerCode, endpointNo, false));
        var gateway = gateways.get(before.profileNo());
        if (gateway == null) throw BizException.of(ErrorCode.NOT_FOUND);
        var verified = gateway.verify(before, message);
        if (verified == null
                || !before.externalStoreRef().equals(verified.externalStoreRef())
                || verified.authorizationNo() == null
                || before.credentials().stream()
                        .noneMatch(c -> c.authorizationNo().equals(verified.authorizationNo())))
            throw BizException.of(ErrorCode.FORBIDDEN);
        CommerceValidation.text(verified.dedupeIdentity(), 512);
        CommerceValidation.text(verified.eventType(), 64);
        CommerceValidation.text(verified.verificationEvidenceRef(), 256);
        if (verified.providerEventRef() != null)
            CommerceValidation.text(verified.providerEventRef(), 128);
        if (verified.externalOrderRef() != null)
            CommerceValidation.text(verified.externalOrderRef(), 128);
        if (verified.externalRevision() != null)
            CommerceValidation.text(verified.externalRevision(), 128);
        var ack = gateway.acknowledge(verified);
        if (ack == null
                || ack.status() < 200
                || ack.status() >= 300
                || ack.body() == null
                || ack.body().length() > 4096)
            throw new IllegalStateException("Invalid protocol ACK");
        String payloadHash = sha(message.bytes()),
                dedupe = ChannelIdentity.hash(verified.dedupeIdentity());
        db.commands.atomic(
                () -> {
                    var current = endpoint(providerCode, endpointNo, true);
                    if (!current.entityNo().equals(before.entityNo())
                            || !current.storeNo().equals(before.storeNo())
                            || !current.connectionRevisionNo().equals(before.connectionRevisionNo())
                            || current.credentials().stream()
                                    .noneMatch(
                                            c ->
                                                    c.authorizationNo()
                                                                    .equals(
                                                                            verified
                                                                                    .authorizationNo())
                                                            && before.credentials().contains(c)))
                        throw BizException.of(ErrorCode.CONFLICT);
                    var existing =
                            events.selectOne(
                                    Wrappers.<CimInboundEvent>query()
                                            .eq("tenant_no", "MAIN")
                                            .eq("connection_no", endpointNo)
                                            .eq("dedupe_key", dedupe)
                                            .last("FOR UPDATE"));
                    String receiptNo = BizKey.next("CIRC");
                    String classification =
                            existing == null
                                    ? "ORIGINAL"
                                    : payloadHash.equals(existing.getPayloadHash())
                                            ? "DUPLICATE"
                                            : "CONFLICT";
                    if (existing == null) {
                        existing = CatalogPersistence.audit(new CimInboundEvent(), "SYSTEM");
                        existing.setEventNo(BizKey.next("CIEV"));
                        existing.setEntityNo(current.entityNo());
                        existing.setStoreNo(current.storeNo());
                        existing.setConnectionNo(endpointNo);
                        existing.setConnectionRevisionNo(current.connectionRevisionNo());
                        existing.setDedupeKey(dedupe);
                        existing.setProviderEventRef(verified.providerEventRef());
                        existing.setEventType(verified.eventType());
                        existing.setPayloadHash(payloadHash);
                        existing.setOriginalReceiptNo(receiptNo);
                        existing.setOccurredAt(
                                verified.occurredAt() == null
                                        ? null
                                        : LocalDateTime.ofInstant(
                                                verified.occurredAt(), ZoneOffset.UTC));
                        existing.setStatus("NEW");
                        existing.setAttemptNo(0L);
                        existing.setFence(0L);
                        existing.setNextAttemptAt(CatalogPersistence.now());
                        existing.setExternalOrderRef(verified.externalOrderRef());
                        existing.setExternalRevision(verified.externalRevision());
                        existing.setAuthorizationNo(verified.authorizationNo());
                        existing.setVerificationEvidenceRef(verified.verificationEvidenceRef());
                        events.insert(existing);
                    } else if ("CONFLICT".equals(classification)) {
                        events.update(
                                Wrappers.<CimInboundEvent>update()
                                        .eq("tenant_no", "MAIN")
                                        .eq("id", existing.getId())
                                        .set("status", "CONFLICT")
                                        .set("fence", existing.getFence() + 1)
                                        .set("lease_until", null)
                                        .set("version", existing.getVersion() + 1));
                    }
                    var receipt = CatalogPersistence.audit(new CimInboundReceipt(), "SYSTEM");
                    receipt.setReceiptNo(receiptNo);
                    receipt.setEntityNo(current.entityNo());
                    receipt.setStoreNo(current.storeNo());
                    receipt.setConnectionNo(endpointNo);
                    receipt.setEventNo(existing.getEventNo());
                    receipt.setReceivedAt(
                            LocalDateTime.ofInstant(message.receivedAt(), ZoneOffset.UTC));
                    receipt.setPayloadRef("DB:" + receiptNo);
                    receipt.setPayloadHash(payloadHash);
                    receipt.setSignatureResult("VERIFIED");
                    receipt.setClassification(classification);
                    receipt.setRetentionUntil(receipt.getReceivedAt().plusDays(retentionDays));
                    receipt.setPayloadBase64(Base64.getEncoder().encodeToString(message.bytes()));
                    receipt.setAuthorizationNo(verified.authorizationNo());
                    receipt.setVerificationEvidenceRef(verified.verificationEvidenceRef());
                    receipts.insert(receipt);
                    return null;
                });
        return ack;
    }

    private record Claim(CimInboundEvent row, long fence, ChannelInboundHandler.Event event) {}

    @Override
    public String workflow() {
        return "INBOUND_CALLBACK";
    }

    /**
     * A lease only grants local processing; it never permits re-sending an unknown platform write.
     */
    @Override
    public int recoverDue(int limit) {
        if (limit < 1 || limit > 100) throw BizException.of(ErrorCode.BAD_REQUEST);
        var due =
                db.commands.query(
                        () ->
                                events.selectList(
                                        Wrappers.<CimInboundEvent>query()
                                                .eq("tenant_no", "MAIN")
                                                .in(
                                                        "status",
                                                        "NEW",
                                                        "RETRY",
                                                        "WAITING_OWNER",
                                                        "PROCESSING")
                                                .le("next_attempt_at", CatalogPersistence.now())
                                                .orderByAsc("next_attempt_at", "id")
                                                .last("LIMIT " + limit)));
        int completed = 0;
        for (var candidate : due) {
            Claim claim;
            try {
                claim = db.commands.atomic(() -> claim(candidate));
            } catch (RuntimeException unavailable) {
                // One damaged receipt or missing historical context must not prevent other events
                // from progressing.
                deferClaim(candidate);
                continue;
            }
            if (claim == null) continue;
            ChannelInboundHandler.Result result;
            try {
                var matching =
                        handlers.get().stream()
                                .filter(
                                        h ->
                                                h.acceptsProfile(claim.event().profileNo())
                                                        && h.supports(claim.event().eventType()))
                                .toList();
                if (matching.size() > 1)
                    throw new IllegalStateException("Ambiguous callback handler");
                result =
                        matching.isEmpty()
                                ? new ChannelInboundHandler.Result(
                                        ChannelInboundHandler.Outcome.WAITING_OWNER, null)
                                : matching.getFirst().handle(claim.event());
                if (result == null || result.outcome() == null)
                    throw new IllegalStateException("Missing handler result");
                if (result.resultRef() != null) CommerceValidation.text(result.resultRef(), 128);
                if (result.outcome() == ChannelInboundHandler.Outcome.PROCESSED
                        && result.resultRef() == null)
                    throw new IllegalStateException(
                            "Processed callback has no durable owner reference");
            } catch (RuntimeException unavailable) {
                result =
                        new ChannelInboundHandler.Result(ChannelInboundHandler.Outcome.RETRY, null);
            }
            var finalResult = result;
            try {
                if (db.commands.atomic(() -> finish(claim, finalResult))) completed++;
            } catch (RuntimeException unavailable) {
                // The original lease/fence remains intact; the same event is recovered after that
                // lease expires.
                LOG.warn("Commerce callback finish deferred for event {}", candidate.getEventNo());
            }
        }
        return completed;
    }

    private void deferClaim(CimInboundEvent candidate) {
        try {
            db.commands.atomic(
                    () -> {
                        events.update(
                                Wrappers.<CimInboundEvent>update()
                                        .eq("tenant_no", "MAIN")
                                        .eq("entity_no", candidate.getEntityNo())
                                        .eq("event_no", candidate.getEventNo())
                                        .eq("version", candidate.getVersion())
                                        .in("status", "NEW", "RETRY", "WAITING_OWNER", "PROCESSING")
                                        .and(
                                                q ->
                                                        q.isNull("lease_until")
                                                                .or()
                                                                .le(
                                                                        "lease_until",
                                                                        CatalogPersistence.now()))
                                        .set("status", "RETRY")
                                        .set("lease_until", null)
                                        .set("fence", candidate.getFence() + 1)
                                        .set(
                                                "next_attempt_at",
                                                CatalogPersistence.now().plusMinutes(1))
                                        .set("version", candidate.getVersion() + 1));
                        return null;
                    });
        } catch (RuntimeException unavailable) {
            LOG.warn("Commerce callback claim deferred for event {}", candidate.getEventNo());
        }
    }

    private Claim claim(CimInboundEvent candidate) {
        var c =
                db.connection(
                        candidate.getEntityNo(),
                        candidate.getStoreNo(),
                        candidate.getConnectionNo(),
                        true,
                        false);
        var row =
                event(
                        candidate.getEntityNo(),
                        candidate.getStoreNo(),
                        candidate.getEventNo(),
                        true);
        var now = CatalogPersistence.now();
        if (!Set.of("NEW", "RETRY", "WAITING_OWNER", "PROCESSING").contains(row.getStatus())
                || row.getNextAttemptAt() == null
                || row.getNextAttemptAt().isAfter(now)
                || row.getLeaseUntil() != null && row.getLeaseUntil().isAfter(now)) return null;
        var receipt =
                receipts.selectOne(
                        Wrappers.<CimInboundReceipt>query()
                                .eq("tenant_no", "MAIN")
                                .eq("entity_no", row.getEntityNo())
                                .eq("connection_no", row.getConnectionNo())
                                .eq("receipt_no", row.getOriginalReceiptNo()));
        if (receipt == null
                || !"ORIGINAL".equals(receipt.getClassification())
                || !"VERIFIED".equals(receipt.getSignatureResult()))
            throw new IllegalStateException("Missing immutable original callback receipt");
        var bytes = Base64.getDecoder().decode(receipt.getPayloadBase64());
        if (!sha(bytes).equals(row.getPayloadHash())
                || !row.getPayloadHash().equals(receipt.getPayloadHash()))
            throw new IllegalStateException("Original callback bytes changed");
        var revision =
                db.revisions.selectOne(
                        Wrappers.<CimConnectionRevision>query()
                                .eq("tenant_no", "MAIN")
                                .eq("entity_no", row.getEntityNo())
                                .eq("connection_no", row.getConnectionNo())
                                .eq("revision_no", row.getConnectionRevisionNo()));
        var grant =
                db.authorizations.selectOne(
                        Wrappers.<CimAuthorizationRevision>query()
                                .eq("tenant_no", "MAIN")
                                .eq("entity_no", row.getEntityNo())
                                .eq("account_no", c.account().getAccountNo())
                                .eq("authorization_no", row.getAuthorizationNo()));
        if (revision == null || grant == null)
            throw new IllegalStateException("Missing original callback context");
        long fence = Math.addExact(row.getFence(), 1),
                attempt = Math.addExact(row.getAttemptNo(), 1);
        var lease = now.plusMinutes(2);
        if (events.update(
                        Wrappers.<CimInboundEvent>update()
                                .eq("tenant_no", "MAIN")
                                .eq("id", row.getId())
                                .eq("version", row.getVersion())
                                .set("version", row.getVersion() + 1)
                                .set("fence", fence)
                                .set("attempt_no", attempt)
                                .set("status", "PROCESSING")
                                .set("lease_until", lease)
                                .set("next_attempt_at", lease))
                != 1) throw BizException.of(ErrorCode.CONFLICT);
        row.setAttemptNo(attempt);
        return new Claim(
                row,
                fence,
                new ChannelInboundHandler.Event(
                        row.getEventNo(),
                        row.getEntityNo(),
                        row.getStoreNo(),
                        row.getConnectionNo(),
                        row.getConnectionRevisionNo(),
                        c.connection().getProfileNo(),
                        c.account().getExternalAccountRef(),
                        c.connection().getExternalStoreRef(),
                        row.getEventType(),
                        row.getProviderEventRef(),
                        row.getExternalOrderRef(),
                        row.getExternalRevision(),
                        row.getOccurredAt() == null
                                ? null
                                : row.getOccurredAt().toInstant(ZoneOffset.UTC),
                        row.getAuthorizationNo(),
                        grant.getCredentialRef(),
                        row.getVerificationEvidenceRef(),
                        bytes));
    }

    private boolean finish(Claim claim, ChannelInboundHandler.Result result) {
        var row = claim.row();
        var current = event(row.getEntityNo(), row.getStoreNo(), row.getEventNo(), true);
        if (!"PROCESSING".equals(current.getStatus())
                || current.getFence() != claim.fence()
                || current.getLeaseUntil() == null
                || !current.getLeaseUntil().isAfter(CatalogPersistence.now())) return false;
        boolean done =
                Set.of(
                                ChannelInboundHandler.Outcome.PROCESSED,
                                ChannelInboundHandler.Outcome.IGNORED,
                                ChannelInboundHandler.Outcome.CONFLICT)
                        .contains(result.outcome());
        long delay = Math.min(3600L, 5L << Math.min(10, row.getAttemptNo().intValue()));
        return events.update(
                                Wrappers.<CimInboundEvent>update()
                                        .eq("tenant_no", "MAIN")
                                        .eq("id", current.getId())
                                        .eq("fence", claim.fence())
                                        .eq("status", "PROCESSING")
                                        .set("status", result.outcome().name())
                                        .set("result_ref", result.resultRef())
                                        .set("lease_until", null)
                                        .set(
                                                "next_attempt_at",
                                                done
                                                        ? null
                                                        : CatalogPersistence.now()
                                                                .plusSeconds(delay))
                                        .set("version", current.getVersion() + 1))
                        == 1
                && done;
    }

    private ChannelInboundGateway.Endpoint endpoint(String provider, String no, boolean lock) {
        var q =
                Wrappers.<CimStoreConnection>query()
                        .eq("tenant_no", "MAIN")
                        .eq("connection_no", no);
        var row = db.connections.selectOne(q);
        if (row == null) throw BizException.of(ErrorCode.NOT_FOUND);
        var aq =
                Wrappers.<CimAccount>query()
                        .eq("tenant_no", "MAIN")
                        .eq("entity_no", row.getEntityNo())
                        .eq("account_no", row.getAccountNo());
        if (lock) aq.last("FOR UPDATE");
        var account = db.accounts.selectOne(aq);
        if (account == null) throw BizException.of(ErrorCode.NOT_FOUND);
        if (lock) row = db.connections.selectOne(q.last("FOR UPDATE"));
        var profile =
                db.profiles.selectOne(
                        Wrappers.<CimProductProfile>query()
                                .eq("tenant_no", "MAIN")
                                .eq("profile_no", row.getProfileNo()));
        if (profile == null) throw BizException.of(ErrorCode.NOT_FOUND);
        var platform =
                providers.selectOne(
                        Wrappers.<CimProvider>query()
                                .eq("tenant_no", "MAIN")
                                .eq("provider_no", profile.getProviderNo()));
        if (platform == null || !provider.equals(platform.getProviderCode()))
            throw BizException.of(ErrorCode.NOT_FOUND);
        var credentials =
                db
                        .authorizations
                        .selectList(
                                Wrappers.<CimAuthorizationRevision>query()
                                        .eq("tenant_no", "MAIN")
                                        .eq("entity_no", row.getEntityNo())
                                        .eq("account_no", row.getAccountNo())
                                        .orderByDesc("revision_no")
                                        .last("LIMIT 3"))
                        .stream()
                        .map(
                                a ->
                                        new ChannelInboundGateway.Credential(
                                                a.getAuthorizationNo(), a.getCredentialRef()))
                        .toList();
        if (credentials.isEmpty()) throw BizException.of(ErrorCode.FORBIDDEN);
        return new ChannelInboundGateway.Endpoint(
                row.getEntityNo(),
                row.getStoreNo(),
                row.getConnectionNo(),
                row.getActiveRevisionNo(),
                row.getProfileNo(),
                account.getExternalAccountRef(),
                row.getExternalStoreRef(),
                credentials);
    }

    @Override
    public PageData<InboundContracts.Event> listEvents(
            String entity, String store, InboundContracts.Query request) {
        if (request == null) throw BizException.of(ErrorCode.BAD_REQUEST);
        CommerceValidation.text(entity, 32);
        CommerceValidation.text(store, 32);
        long offset = CommerceValidation.offset(request.page(), request.size());
        if (request.status() != null) CommerceValidation.text(request.status(), 24);
        if (request.eventType() != null) CommerceValidation.text(request.eventType(), 64);
        return db.commands.query(
                () -> {
                    var q =
                            Wrappers.<CimInboundEvent>query()
                                    .eq("tenant_no", "MAIN")
                                    .eq("entity_no", entity)
                                    .eq("store_no", store)
                                    .eq(request.status() != null, "status", request.status())
                                    .eq(
                                            request.eventType() != null,
                                            "event_type",
                                            request.eventType());
                    long total = events.selectCount(q);
                    return PageData.of(
                            events
                                    .selectList(
                                            q.orderByDesc("created_at", "id")
                                                    .last(
                                                            "LIMIT "
                                                                    + request.size()
                                                                    + " OFFSET "
                                                                    + offset))
                                    .stream()
                                    .map(ChannelInboundServiceImpl::view)
                                    .toList(),
                            total,
                            request.page(),
                            request.size());
                });
    }

    @Override
    public PageData<InboundContracts.Receipt> listReceipts(
            String entity, String store, String eventNo, int page, int size) {
        long offset = CommerceValidation.offset(page, size);
        return db.commands.query(
                () -> {
                    event(entity, store, eventNo, false);
                    var q =
                            Wrappers.<CimInboundReceipt>query()
                                    .eq("tenant_no", "MAIN")
                                    .eq("entity_no", entity)
                                    .eq("store_no", store)
                                    .eq("event_no", eventNo);
                    long total = receipts.selectCount(q);
                    return PageData.of(
                            receipts
                                    .selectList(
                                            q.orderByAsc("received_at", "id")
                                                    .last("LIMIT " + size + " OFFSET " + offset))
                                    .stream()
                                    .map(
                                            r ->
                                                    new InboundContracts.Receipt(
                                                            r.getReceiptNo(),
                                                            r.getEventNo(),
                                                            r.getClassification(),
                                                            r.getSignatureResult(),
                                                            r.getPayloadHash(),
                                                            r.getReceivedAt()
                                                                    .toInstant(ZoneOffset.UTC)))
                                    .toList(),
                            total,
                            page,
                            size);
                });
    }

    @Override
    public InboundContracts.Event recover(
            String entity,
            String store,
            String no,
            String actor,
            InboundContracts.Recovery request,
            String key) {
        if (request == null || request.version() == null || request.version() < 0)
            throw BizException.of(ErrorCode.BAD_REQUEST);
        CommerceValidation.text(request.reason(), 256);
        return db.commands.execute(
                entity,
                store,
                actor,
                "RECOVER_INBOUND:" + no,
                key,
                request,
                InboundContracts.Event.class,
                () -> {
                    var row = event(entity, store, no, true);
                    if (!request.version().equals(row.getVersion())
                            || !Set.of("NEW", "RETRY", "WAITING_OWNER").contains(row.getStatus()))
                        throw BizException.of(ErrorCode.CONFLICT);
                    events.update(
                            Wrappers.<CimInboundEvent>update()
                                    .eq("tenant_no", "MAIN")
                                    .eq("id", row.getId())
                                    .eq("version", request.version())
                                    .set("next_attempt_at", CatalogPersistence.now())
                                    .set("updated_by", actor)
                                    .set("version", request.version() + 1));
                    return view(event(entity, store, no, false));
                },
                InboundContracts.Event::eventNo);
    }

    CimInboundEvent event(String entity, String store, String no, boolean lock) {
        CommerceValidation.text(entity, 32);
        CommerceValidation.text(store, 32);
        CommerceValidation.text(no, 32);
        var q =
                Wrappers.<CimInboundEvent>query()
                        .eq("tenant_no", "MAIN")
                        .eq("entity_no", entity)
                        .eq("store_no", store)
                        .eq("event_no", no);
        if (lock) q.last("FOR UPDATE");
        var row = events.selectOne(q);
        if (row == null) throw BizException.of(ErrorCode.NOT_FOUND);
        return row;
    }

    static InboundContracts.Event view(CimInboundEvent row) {
        return new InboundContracts.Event(
                row.getEventNo(),
                row.getConnectionNo(),
                row.getEventType(),
                row.getStatus(),
                row.getProviderEventRef(),
                row.getOccurredAt() == null ? null : row.getOccurredAt().toInstant(ZoneOffset.UTC),
                row.getAttemptNo(),
                row.getResultRef(),
                row.getVersion());
    }

    private static String sha(byte[] bytes) {
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(bytes));
        } catch (NoSuchAlgorithmException unavailable) {
            throw new IllegalStateException(unavailable);
        }
    }
}
