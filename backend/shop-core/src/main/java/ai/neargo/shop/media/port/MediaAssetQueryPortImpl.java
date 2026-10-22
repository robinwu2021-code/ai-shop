package ai.neargo.shop.media.port;

import ai.neargo.shop.media.SysMediaAsset;
import ai.neargo.shop.media.SysMediaAssetMapper;

import ai.neargo.shop.spi.media.MediaAssetQueryPort;
import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import java.util.List;
import org.springframework.stereotype.Component;

/** Reads the same asset ledger written by MediaUploadService; no filesystem ownership inference. */
@Component
public class MediaAssetQueryPortImpl implements MediaAssetQueryPort {
    private final SysMediaAssetMapper assets;

    public MediaAssetQueryPortImpl(SysMediaAssetMapper assets) { this.assets = assets; }

    @Override
    public List<AssetFact> getAssets(String entity, String store, List<String> keys) {
        MediaAssetQueryPort.validateLookup(entity, store, keys);
        return assets.selectList(Wrappers.<SysMediaAsset>lambdaQuery()
                .select(SysMediaAsset::getAssetKey, SysMediaAsset::getEntityNo, SysMediaAsset::getStoreNo,
                        SysMediaAsset::getBizType, SysMediaAsset::getStatus)
                .eq(SysMediaAsset::getEntityNo, entity).eq(SysMediaAsset::getStoreNo, store)
                .eq(SysMediaAsset::getBizType, SysMediaAsset.GOODS)
                .eq(SysMediaAsset::getStatus, SysMediaAsset.ACTIVE).isNull(SysMediaAsset::getPurgeBatchNo)
                .in(SysMediaAsset::getAssetKey, keys).orderByAsc(SysMediaAsset::getAssetKey))
                .stream().map(a -> new AssetFact(a.getAssetKey(), a.getEntityNo(), a.getStoreNo(),
                        a.getBizType(), a.getStatus())).toList();
    }
}
