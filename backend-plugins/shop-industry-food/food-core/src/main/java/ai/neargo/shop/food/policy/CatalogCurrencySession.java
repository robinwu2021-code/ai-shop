package ai.neargo.shop.food.policy;

import ai.neargo.shop.common.BizException;
import ai.neargo.shop.common.ErrorCode;
import ai.neargo.shop.food.dto.PublicationMarketContext;
import ai.neargo.shop.spi.product.CatalogMarketContracts.GoodsQuery;
import ai.neargo.shop.spi.product.CatalogMarketContracts.SkuQuery;
import ai.neargo.shop.spi.product.CatalogMarketFacts;
import ai.neargo.shop.spi.product.CatalogPriceSourceFacts;
import ai.neargo.shop.spi.product.CatalogPriceSourcePort;
import ai.neargo.shop.spi.product.CatalogPriceSourcePort.MarketFacts;
import ai.neargo.shop.spi.product.CatalogPriceSourcePort.MarketScope;
import ai.neargo.shop.spi.product.GoodsCatalogQueryPort;
import ai.neargo.shop.spi.product.GoodsCatalogQueryPort.GoodsFact;
import ai.neargo.shop.spi.product.GoodsCatalogQueryPort.SkuIdentityFact;
import java.time.Clock;
import java.time.Instant;
import org.springframework.transaction.support.TransactionSynchronizationManager;
import org.springframework.transaction.support.TransactionSynchronization;

/** One operation owns this short-lived session; no thread-local state, global cache, FX or sale admission. */
public final class CatalogCurrencySession {
    private final GoodsCatalogQueryPort goods;
    private final CatalogPriceSourcePort owner;
    private final MarketScope scope;
    private final MarketFacts proof;
    private final Clock clock;
    private Instant until;

    private CatalogCurrencySession(GoodsCatalogQueryPort goods, CatalogPriceSourcePort owner, MarketScope scope,
            MarketFacts proof, Clock clock) {
        this.goods = goods;
        this.owner = owner;
        this.scope = scope;
        this.proof = proof;
        this.clock = clock;
        until = proof == null ? null : proof.validUntil();
    }

    /** Existing constructors and the disabled feature use the original contracts without market queries. */
    public static CatalogCurrencySession legacy(GoodsCatalogQueryPort goods, String entity, String store) {
        return new CatalogCurrencySession(goods, null, new MarketScope(entity, store), null, Clock.systemUTC());
    }

    /** Original context capture is deliberately outside all local read/write transactions. */
    public static CatalogCurrencySession capture(GoodsCatalogQueryPort goods, CatalogPriceSourcePort owner,
            String entity, String store, Clock clock) {
        outside();
        var scope = new MarketScope(entity, store);
        CatalogPriceSourceFacts.validateMarketScope(scope);
        var proof = owner.market(scope);
        validate(scope, proof, clock.instant());
        return new CatalogCurrencySession(goods, owner, scope, proof, clock);
    }

    /** Managed sessions carry a bounded original-owner deadline even when CN keeps legacy metadata. */
    public boolean managed() { return proof != null; }
    /** Only actual foreign markets opt into the new identity and frozen-document contracts. */
    public boolean foreignMarket() { return proof != null && !"CN".equals(proof.market().market()); }
    /** Price validation uses server evidence rather than the currency supplied by a client. */
    public String currency() { return proof == null ? "CNY" : proof.market().currency(); }
    /** Downstream proofs must not outlive any original evidence consumed by this operation. */
    public Instant validUntil() { requireFresh(); return until; }

    /** Legacy/CN metadata behavior is preserved; foreign metadata must carry the actual original market proof. */
    public GoodsFact goods(String number) {
        if (!foreignMarket()) return goods.getGoods(scope.entityNo(), scope.storeNo(), number);
        outside();
        requireFresh();
        var query = new GoodsQuery(scope.entityNo(), scope.storeNo(), number, proof.market().market());
        var facts = goods.getCatalogGoods(query);
        try { CatalogMarketFacts.validate(query, facts, clock.instant()); }
        catch (IllegalArgumentException | IllegalStateException invalid) { throw conflict(); }
        same(facts.marketFacts());
        until = minimum(until, facts.validUntil());
        requireFresh();
        return facts.goods();
    }

    /** Original ownership/version facts are retained; unavailable foreign identities never fall back to CN. */
    public SkuIdentityFact sku(String number) {
        if (!foreignMarket()) return goods.getSku(scope.entityNo(), scope.storeNo(), number);
        outside();
        requireFresh();
        var query = new SkuQuery(scope.entityNo(), scope.storeNo(), number, proof.market().market());
        var facts = goods.getCatalogSku(query);
        try { CatalogMarketFacts.validate(query, facts, clock.instant()); }
        catch (IllegalArgumentException | IllegalStateException invalid) { throw conflict(); }
        same(facts.marketFacts());
        until = minimum(until, facts.validUntil());
        requireFresh();
        return facts.sku();
    }

    /** Recheck current market before entering the command transaction; no remote read occurs under its locks. */
    public void refresh() {
        if (proof == null) return;
        outside();
        var current = owner.market(scope);
        validate(scope, current, clock.instant());
        same(current);
        until = minimum(until, current.validUntil());
        requireFresh();
    }

    /** This local deadline check is safe inside the original transaction immediately before mutation. */
    public void requireFresh() {
        if (until != null && !until.isAfter(clock.instant())) throw conflict();
    }

    /** Lock waits and idempotency/outbox SQL must not let expired evidence commit after the initial check. */
    public void guardTransaction() {
        requireFresh();
        if (proof == null) return;
        if (!TransactionSynchronizationManager.isActualTransactionActive()
                || !TransactionSynchronizationManager.isSynchronizationActive()) {
            throw new IllegalStateException("Currency write guard requires the owning transaction");
        }
        TransactionSynchronizationManager.registerSynchronization(new TransactionSynchronization() {
            @Override public void beforeCommit(boolean readOnly) { requireFresh(); }
        });
    }

    /** Only new non-CN documents freeze context; CN publishers retain original bytes and schemas. */
    public PublicationMarketContext frozen() {
        requireFresh();
        return foreignMarket() ? PublicationMarketPolicy.capture(scope, proof, clock.instant()) : null;
    }

    /** Re-publication/current capture cannot reinterpret old bytes under a different current market. */
    public void requireFrozen(PublicationMarketContext context) {
        if (context == null) {
            if (foreignMarket()) throw conflict();
        } else {
            if (!foreignMarket()) throw conflict();
            try { PublicationMarketPolicy.requireSame(frozen(), context); }
            catch (IllegalArgumentException | IllegalStateException invalid) { throw conflict(); }
        }
    }

    private void same(MarketFacts current) {
        if (!scope.equals(current.scope()) || !proof.market().equals(current.market())) throw conflict();
    }

    private static void validate(MarketScope scope, MarketFacts facts, Instant now) {
        try { CatalogPriceSourceFacts.validateMarketFacts(scope, facts, now); }
        catch (IllegalArgumentException | IllegalStateException invalid) { throw conflict(); }
    }

    private static Instant minimum(Instant a, Instant b) { return a.isBefore(b) ? a : b; }
    private static void outside() {
        if (TransactionSynchronizationManager.isActualTransactionActive()) {
            throw new IllegalStateException("Original catalog context cannot be read inside a transaction");
        }
    }
    private static BizException conflict() { return BizException.of(ErrorCode.CONFLICT); }
}
