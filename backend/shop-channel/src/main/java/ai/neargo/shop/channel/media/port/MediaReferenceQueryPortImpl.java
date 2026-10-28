package ai.neargo.shop.channel.media.port;

import ai.neargo.common.data.scope.DataScopeContext;
import ai.neargo.shop.common.BizException;
import ai.neargo.shop.common.ErrorCode;
import ai.neargo.shop.common.pricing.PricingSnapshotHash;
import ai.neargo.shop.media.MediaStore;
import ai.neargo.shop.spi.media.MediaAssetQueryPort;
import ai.neargo.shop.spi.media.MediaPublicSourceFacts;
import ai.neargo.shop.spi.media.MediaReferenceFacts;
import ai.neargo.shop.spi.media.MediaReferenceQueryPort;
import java.net.URI;
import java.time.Clock;
import java.util.HashSet;
import java.util.List;
import java.util.function.Supplier;
import java.util.stream.Stream;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;
import org.springframework.transaction.support.TransactionSynchronizationManager;

/** Strict owner-side reversal of managed public exports; deliberately independent of the GC extractor. */
@Component
public final class MediaReferenceQueryPortImpl implements MediaReferenceQueryPort {
    private static final String PROBE = "REFERENCE/PROBE/goods/identity.jpg";
    private final Supplier<MediaStore> stores;
    private final Supplier<MediaAssetQueryPort> assets;
    private final List<String> aliases;
    private final Clock clock;

    /** Storage and ledger are optional so a missing owner fails at the capability boundary. */
    @Autowired
    public MediaReferenceQueryPortImpl(ObjectProvider<MediaStore> stores, ObjectProvider<MediaAssetQueryPort> assets,
            @Value("${shop.cos.domain:}") String canonical,
            @Value("${shop.media.direct-base-url:}") String direct) {
        this(stores::getIfAvailable, assets::getIfAvailable, canonical, direct, Clock.systemUTC());
    }

    /** Explicit clock and original-owner suppliers keep evidence lifetime independently testable. */
    public MediaReferenceQueryPortImpl(Supplier<MediaStore> stores, Supplier<MediaAssetQueryPort> assets,
            String canonical, String direct, Clock clock) {
        this.stores = stores;
        this.assets = assets;
        this.clock = clock;
        aliases = Stream.of(canonical, direct).filter(s -> s != null && !s.isBlank())
                .map(s -> s.replaceAll("/+$", "") + "/").peek(MediaReferenceQueryPortImpl::trustedBase).toList();
    }

    @Override
    public Facts resolve(Query query) {
        MediaReferenceFacts.validateQuery(query);
        require(!TransactionSynchronizationManager.isActualTransactionActive());
        var started = clock.instant();
        var store = stores.get();
        var ledger = assets.get();
        if (store == null || ledger == null) throw BizException.of(ErrorCode.NOT_FOUND);
        var key = resolveKey(query.reference(), store);
        var keys = List.of(key);
        var before = DataScopeContext.executeWithoutScope(
                () -> ledger.getAssets(query.entityNo(), query.storeNo(), keys));
        if (before == null || before.size() != 1
                || !MediaAssetQueryPort.validFact(before.getFirst(), query.entityNo(), query.storeNo(), keys)) {
            throw BizException.of(ErrorCode.NOT_FOUND);
        }
        require(key.equals(resolveKey(query.reference(), store)));
        require(before.equals(DataScopeContext.executeWithoutScope(
                () -> ledger.getAssets(query.entityNo(), query.storeNo(), keys))));
        var facts = MediaReferenceFacts.seal(new Facts(1, "MEDIA_REFERENCE_IDENTITY", query, key,
                PricingSnapshotHash.of(before.getFirst()), clock.instant(), started.plusSeconds(30), null));
        try { MediaReferenceFacts.validate(query, facts, clock.instant()); }
        catch (IllegalStateException invalid) { throw BizException.of(ErrorCode.CONFLICT); }
        return facts;
    }

    private String resolveKey(String reference, MediaStore store) {
        require(!reference.contains("%") && !reference.contains("\\") && !reference.contains("?")
                && !reference.contains("#") && !reference.chars().anyMatch(Character::isWhitespace));
        var candidates = new HashSet<String>();
        if (MediaPublicSourceFacts.publicGoodsKey(reference)) candidates.add(reference);
        var exported = store.publicUrl(PROBE);
        require(exported != null && exported.endsWith(PROBE));
        String originalBase = exported.substring(0, exported.length() - PROBE.length());
        require(!originalBase.isEmpty() && originalBase.endsWith("/"));
        if (originalBase.startsWith("/")) {
            require(!originalBase.startsWith("//")
                    && URI.create(originalBase).normalize().toString().equals(originalBase));
        } else trustedBase(originalBase);
        addCandidate(reference, originalBase, candidates);
        for (String alias : aliases) addCandidate(reference, alias, candidates);
        require(candidates.size() == 1);
        String key = candidates.iterator().next();
        require((originalBase + key).equals(store.publicUrl(key)));
        return key;
    }

    private static void addCandidate(String ref, String base, HashSet<String> candidates) {
        if (ref.startsWith(base)) {
            String key = ref.substring(base.length());
            if (MediaPublicSourceFacts.publicGoodsKey(key)) candidates.add(key);
        }
    }

    private static void trustedBase(String base) {
        try {
            URI uri = URI.create(base);
            require("https".equals(uri.getScheme()) && uri.getHost() != null && uri.getRawUserInfo() == null
                    && uri.getRawQuery() == null && uri.getRawFragment() == null && !base.contains("%")
                    && uri.normalize().equals(uri) && !uri.getRawPath().contains("//")
                    && (uri.getPort() == -1 || uri.getPort() > 0 && uri.getPort() <= 65535));
        } catch (IllegalArgumentException invalid) { throw BizException.of(ErrorCode.CONFLICT); }
    }

    private static void require(boolean valid) {
        if (!valid) throw BizException.of(ErrorCode.CONFLICT);
    }
}
