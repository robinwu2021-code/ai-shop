package ai.neargo.shop.commerce.service.impl;

import ai.neargo.common.data.scope.DataScopeContext;
import ai.neargo.shop.commerce.dto.ChannelCatalogAuthorityContracts.Account;
import ai.neargo.shop.commerce.dto.ChannelCatalogAuthorityContracts.Connection;
import ai.neargo.shop.commerce.dto.ChannelCatalogAuthorityContracts.Facts;
import ai.neargo.shop.commerce.dto.ChannelCatalogAuthorityContracts.Grant;
import ai.neargo.shop.commerce.dto.ChannelCatalogAuthorityContracts.IdentityEvidence;
import ai.neargo.shop.commerce.dto.ChannelCatalogAuthorityContracts.Profile;
import ai.neargo.shop.commerce.dto.ChannelCatalogAuthorityContracts.Provider;
import ai.neargo.shop.commerce.dto.ChannelCatalogAuthorityContracts.Query;
import ai.neargo.shop.commerce.entity.CimProductProfile;
import ai.neargo.shop.commerce.entity.CimProvider;
import ai.neargo.shop.commerce.mapper.CommerceMappers.ProviderMapper;
import ai.neargo.shop.commerce.policy.ChannelCatalogAuthorityPolicy;
import ai.neargo.shop.commerce.service.ChannelCatalogAuthorityService;
import ai.neargo.shop.common.BizException;
import ai.neargo.shop.common.ErrorCode;
import ai.neargo.shop.common.pricing.PricingSnapshotHash;
import ai.neargo.shop.spi.commerce.ChannelConnectionIdentityPort;

import com.baomidou.mybatisplus.core.toolkit.Wrappers;

import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.support.TransactionSynchronizationManager;
import org.springframework.transaction.support.TransactionTemplate;

import java.time.Clock;
import java.util.Objects;
import java.util.function.Supplier;

/**
 * Two coherent reads bracket fresh remote identity verification without holding a database
 * transaction.
 */
public final class ChannelCatalogAuthorityServiceImpl implements ChannelCatalogAuthorityService {
    private final CatalogPersistence data;
    private final ProviderMapper providers;
    private final Supplier<ChannelConnectionIdentityPort> identity;
    private final Clock clock;
    private final TransactionTemplate read;

    public ChannelCatalogAuthorityServiceImpl(
            CatalogPersistence data,
            ProviderMapper providers,
            Supplier<ChannelConnectionIdentityPort> identity,
            PlatformTransactionManager manager,
            Clock clock) {
        this.data = data;
        this.providers = providers;
        this.identity = identity;
        this.clock = clock;
        read = new TransactionTemplate(manager);
        read.setReadOnly(true);
        read.setIsolationLevel(TransactionDefinition.ISOLATION_REPEATABLE_READ);
    }

    @Override
    public Facts capture(Query query) {
        ChannelCatalogAuthorityPolicy.validateQuery(query);
        require(!TransactionSynchronizationManager.isActualTransactionActive());
        var startedAt = clock.instant();
        var before = read(query);
        var verifier = identity.get();
        require(verifier != null);
        var expected = ChannelCatalogAuthorityPolicy.identity(before.facts());
        var proof = verifier.verify(expected);
        var after = read(query);
        require(before.equals(after));
        var now = clock.instant();
        require(
                proof != null
                        && proof.status() == ChannelConnectionIdentityPort.Status.VERIFIED
                        && expected.equals(proof.identity())
                        && proof.receiptRef() != null
                        && !proof.receiptRef().isBlank()
                        && proof.receiptRef().length() <= 128
                        && proof.verifiedAt() != null
                        && !proof.verifiedAt().isAfter(now)
                        && proof.validUntil() != null
                        && proof.validUntil().isAfter(now));
        var until = startedAt.plusSeconds(30);
        if (before.facts().grant().expiresAt().isBefore(until))
            until = before.facts().grant().expiresAt();
        if (proof.validUntil().isBefore(until)) until = proof.validUntil();
        var f = before.facts();
        var result =
                ChannelCatalogAuthorityPolicy.seal(
                        new Facts(
                                1,
                                "CATALOG_AUTHORITY",
                                query,
                                f.provider(),
                                f.profile(),
                                f.account(),
                                f.connection(),
                                f.grant(),
                                new IdentityEvidence(
                                        "VERIFIED",
                                        PricingSnapshotHash.of(expected),
                                        proof.receiptRef(),
                                        proof.verifiedAt(),
                                        proof.validUntil()),
                                now,
                                until,
                                null));
        try {
            ChannelCatalogAuthorityPolicy.validate(query, result, clock.instant());
        } catch (IllegalStateException invalid) {
            throw BizException.of(ErrorCode.CONFLICT);
        }
        return result;
    }

    private State read(Query query) {
        return DataScopeContext.executeWithoutScope(() -> read.execute(tx -> load(query)));
    }

    private State load(Query q) {
        var context = data.connection(q.entityNo(), q.storeNo(), q.connectionNo(), false);
        var c = context.connection();
        var a = context.account();
        var r = context.revision();
        require(
                q.revisionNo().equals(c.getActiveRevisionNo())
                        && q.revisionNo().equals(r.getRevisionNo()));
        var grant = data.authorized(context);
        var p =
                data.profiles.selectOne(
                        Wrappers.<CimProductProfile>query()
                                .eq("tenant_no", "MAIN")
                                .eq("profile_no", c.getProfileNo()));
        require(
                p != null
                        && Objects.equals(a.getProfileNo(), p.getProfileNo())
                        && Objects.equals(p.getIdentitySchemaVersion(), 1)
                        && ("GLOBAL".equals(p.getStoreIdentityScope())
                                || "ACCOUNT".equals(p.getStoreIdentityScope())));
        var provider =
                providers.selectOne(
                        Wrappers.<CimProvider>query()
                                .eq("tenant_no", "MAIN")
                                .eq("provider_no", p.getProviderNo()));
        require(
                provider != null
                        && "ACTIVE".equals(provider.getStatus())
                        && !grant.getCredentialRef().isBlank());
        require(r.getConfigJson() != null && data.json.readTree(r.getConfigJson()).isObject());
        var f =
                new Facts(
                        1,
                        "CATALOG_AUTHORITY",
                        q,
                        new Provider(
                                provider.getProviderNo(),
                                provider.getProviderCode(),
                                provider.getVersion()),
                        new Profile(
                                p.getProfileNo(),
                                p.getProductCode(),
                                p.getMarket(),
                                p.getProtocolCode(),
                                p.getIdentityNamespace(),
                                "ACCOUNT".equals(p.getStoreIdentityScope()),
                                p.getVersion()),
                        new Account(a.getAccountNo(), a.getExternalAccountRef(), a.getVersion()),
                        new Connection(
                                c.getConnectionNo(),
                                r.getRevisionNo(),
                                c.getExternalStoreRef(),
                                c.getChannelStoreKey(),
                                r.getSourceOwner(),
                                r.getCatalogAuthority(),
                                r.getFulfillmentProfile(),
                                r.getConfigHash(),
                                c.getVersion(),
                                r.getVersion(),
                                r.getRevision()),
                        new Grant(
                                grant.getAuthorizationNo(),
                                grant.getVersion(),
                                grant.getRevisionNo(),
                                "CATALOG",
                                grant.getCapabilityHash(),
                                CatalogPersistence.instant(grant.getExpiresAt())),
                        null,
                        null,
                        null,
                        null);
        try {
            ChannelCatalogAuthorityPolicy.validateBase(f);
        } catch (IllegalStateException invalid) {
            throw BizException.of(ErrorCode.CONFLICT);
        }
        // Private comparison material never leaves the service, even if a writer omits a version
        // increment.
        return new State(f, grant.getCredentialRef(), grant.getGrantedScopes(), r.getConfigJson());
    }

    private static void require(boolean ok) {
        if (!ok) throw BizException.of(ErrorCode.CONFLICT);
    }

    private record State(Facts facts, String credential, String scopes, String config) {}
}
