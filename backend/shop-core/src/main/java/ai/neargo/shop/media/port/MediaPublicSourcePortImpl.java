package ai.neargo.shop.media.port;

import ai.neargo.common.data.scope.DataScopeContext;
import ai.neargo.shop.common.BizException;
import ai.neargo.shop.common.ErrorCode;
import ai.neargo.shop.common.pricing.PricingSnapshotHash;
import ai.neargo.shop.media.MediaStore;
import ai.neargo.shop.media.SysMediaAsset;
import ai.neargo.shop.media.SysMediaAssetMapper;
import ai.neargo.shop.spi.media.MediaPublicSourceFacts;
import ai.neargo.shop.spi.media.MediaPublicSourcePort;

import com.baomidou.mybatisplus.core.toolkit.Wrappers;

import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.stereotype.Component;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.support.TransactionSynchronizationManager;
import org.springframework.transaction.support.TransactionTemplate;

import java.time.Clock;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.function.Supplier;

/**
 * The original ledger and storage owner supply public facts; no filesystem ownership inference or
 * private signing.
 */
@Component
public final class MediaPublicSourcePortImpl implements MediaPublicSourcePort {
    private final SysMediaAssetMapper assets;
    private final Supplier<MediaStore> stores;
    private final Clock clock;
    private final TransactionTemplate read;

    @Autowired
    public MediaPublicSourcePortImpl(
            SysMediaAssetMapper assets,
            ObjectProvider<MediaStore> stores,
            @Qualifier("transactionManager") PlatformTransactionManager manager) {
        this(assets, stores::getIfAvailable, manager, Clock.systemUTC());
    }

    public MediaPublicSourcePortImpl(
            SysMediaAssetMapper assets,
            Supplier<MediaStore> stores,
            PlatformTransactionManager manager,
            Clock clock) {
        this.assets = assets;
        this.stores = stores;
        this.clock = clock;
        read = new TransactionTemplate(manager);
        read.setReadOnly(true);
        read.setIsolationLevel(TransactionDefinition.ISOLATION_REPEATABLE_READ);
    }

    @Override
    public Facts capture(Request request) {
        MediaPublicSourceFacts.validateRequest(request);
        require(!TransactionSynchronizationManager.isActualTransactionActive());
        var started = clock.instant();
        var before = load(request);
        var store = stores.get();
        if (store == null) throw BizException.of(ErrorCode.NOT_FOUND);
        var result = new ArrayList<Asset>();
        for (var row : before) {
            var m = row.metadata();
            require(MediaPublicSourceFacts.publicGoodsKey(m.assetKey()));
            var address = store.publicUrl(m.assetKey());
            try {
                MediaPublicSourceFacts.validateUrl(m.assetKey(), address);
            } catch (IllegalStateException invalid) {
                throw BizException.of(ErrorCode.CONFLICT);
            }
            result.add(
                    new Asset(
                            m.assetKey(),
                            address,
                            m.contentType(),
                            m.bytes(),
                            m.width(),
                            m.height(),
                            PricingSnapshotHash.of(m)));
        }
        var after = load(request);
        require(before.equals(after));
        var facts =
                MediaPublicSourceFacts.seal(
                        new Facts(
                                1,
                                "CATALOG_PUBLIC_MEDIA",
                                request,
                                result,
                                clock.instant(),
                                started.plusSeconds(30),
                                null));
        try {
            MediaPublicSourceFacts.validate(request, facts, clock.instant());
        } catch (IllegalStateException invalid) {
            throw BizException.of(ErrorCode.CONFLICT);
        }
        return facts;
    }

    private List<Row> load(Request request) {
        return DataScopeContext.executeWithoutScope(
                () ->
                        read.execute(
                                tx -> {
                                    var rows =
                                            assets.selectList(
                                                    Wrappers.<SysMediaAsset>lambdaQuery()
                                                            .select(
                                                                    SysMediaAsset::getAssetKey,
                                                                    SysMediaAsset::getEntityNo,
                                                                    SysMediaAsset::getStoreNo,
                                                                    SysMediaAsset::getBizType,
                                                                    SysMediaAsset::getStatus,
                                                                    SysMediaAsset::getPurgeBatchNo,
                                                                    SysMediaAsset::getBytes,
                                                                    SysMediaAsset::getWidth,
                                                                    SysMediaAsset::getHeight,
                                                                    SysMediaAsset::getContentType,
                                                                    SysMediaAsset::getCreatedAt,
                                                                    SysMediaAsset::getUpdatedAt)
                                                            .eq(
                                                                    SysMediaAsset::getEntityNo,
                                                                    request.entityNo())
                                                            .eq(
                                                                    SysMediaAsset::getStoreNo,
                                                                    request.storeNo())
                                                            .eq(
                                                                    SysMediaAsset::getBizType,
                                                                    SysMediaAsset.GOODS)
                                                            .eq(
                                                                    SysMediaAsset::getStatus,
                                                                    SysMediaAsset.ACTIVE)
                                                            .isNull(SysMediaAsset::getPurgeBatchNo)
                                                            .in(
                                                                    SysMediaAsset::getAssetKey,
                                                                    request.assetKeys()));
                                    var indexed = new HashMap<String, Row>();
                                    for (var row : rows) {
                                        require(
                                                request.assetKeys().contains(row.getAssetKey())
                                                        && request.entityNo()
                                                                .equals(row.getEntityNo())
                                                        && request.storeNo()
                                                                .equals(row.getStoreNo())
                                                        && SysMediaAsset.GOODS.equals(
                                                                row.getBizType())
                                                        && SysMediaAsset.ACTIVE.equals(
                                                                row.getStatus())
                                                        && row.getPurgeBatchNo() == null);
                                        var metadata =
                                                new Metadata(
                                                        row.getAssetKey(),
                                                        row.getEntityNo(),
                                                        row.getStoreNo(),
                                                        row.getBytes(),
                                                        row.getWidth(),
                                                        row.getHeight(),
                                                        row.getContentType(),
                                                        row.getCreatedAt());
                                        require(
                                                indexed.put(
                                                                row.getAssetKey(),
                                                                new Row(
                                                                        metadata,
                                                                        row.getUpdatedAt()))
                                                        == null);
                                    }
                                    if (indexed.size() != request.assetKeys().size())
                                        throw BizException.of(ErrorCode.NOT_FOUND);
                                    return request.assetKeys().stream().map(indexed::get).toList();
                                }));
    }

    private static void require(boolean ok) {
        if (!ok) throw BizException.of(ErrorCode.CONFLICT);
    }

    // Audit refresh participates in consistency checks but not stable display metadata revisions.
    private record Row(Metadata metadata, LocalDateTime updatedAt) {}

    private record Metadata(
            String assetKey,
            String entity,
            String store,
            Long bytes,
            Integer width,
            Integer height,
            String contentType,
            LocalDateTime createdAt) {}
}
