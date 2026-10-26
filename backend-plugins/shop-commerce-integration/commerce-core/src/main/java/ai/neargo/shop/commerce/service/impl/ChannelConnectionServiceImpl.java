package ai.neargo.shop.commerce.service.impl;

import ai.neargo.shop.commerce.dto.ChannelConnectionVO;
import ai.neargo.shop.commerce.dto.CreateChannelConnectionReq;
import ai.neargo.shop.commerce.entity.CimAccount;
import ai.neargo.shop.commerce.entity.CimConnectionRevision;
import ai.neargo.shop.commerce.entity.CimProductProfile;
import ai.neargo.shop.commerce.entity.CimStoreConnection;
import ai.neargo.shop.commerce.mapper.CommerceMappers.AccountMapper;
import ai.neargo.shop.commerce.mapper.CommerceMappers.ConnectionRevisionMapper;
import ai.neargo.shop.commerce.mapper.CommerceMappers.ProductProfileMapper;
import ai.neargo.shop.commerce.mapper.CommerceMappers.StoreConnectionMapper;
import ai.neargo.shop.commerce.policy.ChannelIdentity;
import ai.neargo.shop.commerce.policy.CommerceValidation;
import ai.neargo.shop.commerce.service.ChannelConnectionService;
import ai.neargo.shop.common.BizException;
import ai.neargo.shop.common.BizKey;
import ai.neargo.shop.common.ErrorCode;
import ai.neargo.shop.common.PageData;
import ai.neargo.shop.spi.commerce.ChannelConnectionIdentityPort;
import ai.neargo.shop.spi.commerce.ChannelConnectionIdentityPort.Identity;
import ai.neargo.shop.spi.commerce.ChannelConnectionIdentityPort.Proof;
import ai.neargo.shop.spi.commerce.ChannelConnectionIdentityPort.Status;
import ai.neargo.shop.spi.commerce.ChannelSourceValidationPort.Owner;

import com.baomidou.mybatisplus.core.toolkit.Wrappers;

import org.springframework.transaction.support.TransactionSynchronizationManager;

import java.time.Instant;
import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.util.Set;
import java.util.function.Supplier;

/** 稳定远端身份永久唯一；首批只保存草稿，不产生平台接单或履约指令。 */
public class ChannelConnectionServiceImpl implements ChannelConnectionService {
    private final StoreConnectionMapper connections;
    private final ConnectionRevisionMapper revisions;
    private final AccountMapper accounts;
    private final ProductProfileMapper profiles;
    private final CommerceCommands commands;
    private final Supplier<ChannelConnectionIdentityPort> authority;

    public ChannelConnectionServiceImpl(
            StoreConnectionMapper connections,
            ConnectionRevisionMapper revisions,
            AccountMapper accounts,
            ProductProfileMapper profiles,
            CommerceCommands commands,
            Supplier<ChannelConnectionIdentityPort> authority) {
        this.connections = connections;
        this.revisions = revisions;
        this.accounts = accounts;
        this.profiles = profiles;
        this.commands = commands;
        this.authority = authority;
    }

    @Override
    public ChannelConnectionVO createConnection(
            String entity, String store, String actor, CreateChannelConnectionReq req, String key) {
        CommerceValidation.text(store, 32);
        if (req == null
                || req.sourceOwner() == null
                || !Set.of("INSTANT", "PARCEL", "REDEMPTION")
                        .contains(
                                req.fulfillmentProfile() == null ? "" : req.fulfillmentProfile())) {
            throw BizException.of(ErrorCode.BAD_REQUEST);
        }
        CommerceValidation.text(req.accountNo(), 32);
        CommerceValidation.text(req.externalStoreRef(), 128);
        if (TransactionSynchronizationManager.isActualTransactionActive())
            throw BizException.of(ErrorCode.CONFLICT);
        var replay =
                commands.replay(
                        entity,
                        store,
                        actor,
                        "CREATE_CONNECTION",
                        key,
                        req,
                        ChannelConnectionVO.class);
        if (replay.isPresent()) return replay.get();
        Identity identity = commands.query(() -> identity(entity, store, req, false));
        var verifier = authority.get();
        if (verifier == null) throw BizException.of(ErrorCode.CONFLICT);
        Proof proof = verifier.verify(identity);
        requireProof(identity, proof);

        return commands.execute(
                entity,
                store,
                actor,
                "CREATE_CONNECTION",
                key,
                req,
                ChannelConnectionVO.class,
                () -> {
                    Identity current = identity(entity, store, req, true);
                    if (!identity.equals(current)) throw BizException.of(ErrorCode.CONFLICT);
                    requireProof(current, proof);
                    var row = new CimStoreConnection();
                    row.setTenantNo("MAIN");
                    row.setEntityNo(entity);
                    row.setStoreNo(store);
                    row.setConnectionNo(BizKey.next("CICN"));
                    row.setAccountNo(identity.accountNo());
                    row.setProfileNo(identity.profileNo());
                    row.setExternalStoreRef(req.externalStoreRef());
                    row.setChannelStoreKey(
                            ChannelIdentity.storeKey(
                                    identity.identityNamespace(),
                                    identity.accountScoped(),
                                    identity.externalAccountRef(),
                                    req.externalStoreRef()));
                    row.setIdentityReceiptRef(proof.receiptRef());
                    row.setIdentityVerifiedAt(
                            LocalDateTime.ofInstant(proof.verifiedAt(), ZoneOffset.UTC));
                    row.setIdentityValidUntil(
                            LocalDateTime.ofInstant(proof.validUntil(), ZoneOffset.UTC));
                    row.setStatus("DRAFT");
                    row.setVersion(0L);
                    row.setDeleted(0);
                    row.setCreatedBy(actor);
                    row.setUpdatedBy(actor);
                    var revision = new CimConnectionRevision();
                    revision.setTenantNo("MAIN");
                    revision.setEntityNo(entity);
                    revision.setConnectionNo(row.getConnectionNo());
                    revision.setRevisionNo(BizKey.next("CICR"));
                    revision.setRevision(1L);
                    revision.setSourceOwner(req.sourceOwner().name());
                    revision.setAcceptMode("MANUAL");
                    revision.setFulfillmentProfile(req.fulfillmentProfile());
                    revision.setCatalogAuthority("LOCAL");
                    revision.setConfigJson("{}");
                    revision.setConfigHash(
                            ChannelIdentity.hash(
                                    req.sourceOwner().name()
                                            + ":MANUAL:"
                                            + req.fulfillmentProfile()
                                            + ":LOCAL:{}"));
                    revision.setCreatedBy(actor);
                    revision.setUpdatedBy(actor);
                    revision.setVersion(0L);
                    revision.setDeleted(0);
                    row.setActiveRevisionNo(revision.getRevisionNo());
                    row.setCreatedAt(LocalDateTime.now(ZoneOffset.UTC));
                    row.setUpdatedAt(row.getCreatedAt());
                    revision.setCreatedAt(row.getCreatedAt());
                    revision.setUpdatedAt(row.getCreatedAt());
                    connections.insert(row);
                    revisions.insert(revision);
                    return view(row, revision);
                },
                ChannelConnectionVO::connectionNo);
    }

    private Identity identity(
            String entity, String store, CreateChannelConnectionReq req, boolean lock) {
        var accountQuery =
                Wrappers.<CimAccount>query()
                        .eq("tenant_no", "MAIN")
                        .eq("entity_no", entity)
                        .eq("account_no", req.accountNo());
        if (lock) accountQuery.last("FOR UPDATE");
        var account = accounts.selectOne(accountQuery);
        if (account == null || "ARCHIVED".equals(account.getStatus()))
            throw BizException.of(ErrorCode.NOT_FOUND);
        var profileQuery =
                Wrappers.<CimProductProfile>query()
                        .eq("tenant_no", "MAIN")
                        .eq("profile_no", account.getProfileNo());
        if (lock) profileQuery.last("FOR UPDATE");
        var profile = profiles.selectOne(profileQuery);
        if (profile == null
                || !"VERIFIED".equals(profile.getStatus())
                || profile.getIdentityNamespace() == null
                || profile.getIdentityNamespace().isBlank()
                || profile.getStoreIdentityScope() == null
                || !Set.of("GLOBAL", "ACCOUNT").contains(profile.getStoreIdentityScope())) {
            throw BizException.of(ErrorCode.CONFLICT);
        }
        return new Identity(
                entity,
                store,
                account.getAccountNo(),
                profile.getProfileNo(),
                account.getExternalAccountRef(),
                req.externalStoreRef(),
                profile.getIdentityNamespace(),
                "ACCOUNT".equals(profile.getStoreIdentityScope()),
                account.getVersion(),
                profile.getVersion());
    }

    private static void requireProof(Identity identity, Proof proof) {
        Instant now = Instant.now();
        if (proof == null
                || proof.status() != Status.VERIFIED
                || !identity.equals(proof.identity())
                || proof.receiptRef() == null
                || proof.receiptRef().isBlank()
                || proof.receiptRef().length() > 128
                || proof.verifiedAt() == null
                || proof.validUntil() == null
                || proof.verifiedAt().isAfter(now)
                || !proof.validUntil().isAfter(now)) throw BizException.of(ErrorCode.CONFLICT);
    }

    @Override
    public ChannelConnectionVO getConnection(String entity, String store, String connectionNo) {
        CommerceValidation.text(entity, 32);
        CommerceValidation.text(store, 32);
        CommerceValidation.text(connectionNo, 32);
        return commands.query(
                () -> {
                    var row =
                            connections.selectOne(
                                    Wrappers.<CimStoreConnection>query()
                                            .eq("tenant_no", "MAIN")
                                            .eq("entity_no", entity)
                                            .eq("store_no", store)
                                            .eq("connection_no", connectionNo));
                    if (row == null) throw BizException.of(ErrorCode.NOT_FOUND);
                    return view(row, revision(row));
                });
    }

    @Override
    public PageData<ChannelConnectionVO> listConnections(
            String entity, String store, int page, int size) {
        CommerceValidation.text(entity, 32);
        CommerceValidation.text(store, 32);
        long offset = CommerceValidation.offset(page, size);
        return commands.query(
                () -> {
                    var query =
                            Wrappers.<CimStoreConnection>query()
                                    .eq("tenant_no", "MAIN")
                                    .eq("entity_no", entity)
                                    .eq("store_no", store);
                    long total = connections.selectCount(query);
                    var records =
                            connections
                                    .selectList(
                                            query.orderByDesc("created_at", "id")
                                                    .last("LIMIT " + size + " OFFSET " + offset))
                                    .stream()
                                    .map(row -> view(row, revision(row)))
                                    .toList();
                    return PageData.of(records, total, page, size);
                });
    }

    private CimConnectionRevision revision(CimStoreConnection row) {
        var revision =
                revisions.selectOne(
                        Wrappers.<CimConnectionRevision>query()
                                .eq("tenant_no", "MAIN")
                                .eq("entity_no", row.getEntityNo())
                                .eq("connection_no", row.getConnectionNo())
                                .eq("revision_no", row.getActiveRevisionNo()));
        if (revision == null)
            throw new IllegalStateException("Missing immutable connection revision");
        return revision;
    }

    private static ChannelConnectionVO view(
            CimStoreConnection row, CimConnectionRevision revision) {
        return new ChannelConnectionVO(
                row.getConnectionNo(),
                row.getStoreNo(),
                row.getAccountNo(),
                row.getProfileNo(),
                row.getExternalStoreRef(),
                row.getStatus(),
                Owner.valueOf(revision.getSourceOwner()),
                revision.getFulfillmentProfile(),
                revision.getRevisionNo(),
                row.getVersion());
    }
}
