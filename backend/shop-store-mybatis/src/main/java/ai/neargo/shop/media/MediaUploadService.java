package ai.neargo.shop.media;

import org.springframework.stereotype.Service;

import java.io.IOException;
import java.io.InputStream;
import java.time.LocalDateTime;

/**
 * 「把字节存下来并记上一笔」—— 上传链路上**唯一**写 {@code sys_media_asset} 的地方。
 *
 * <p><b>为什么要有这个类，而不是让各 controller 各写一遍三步：</b>
 * 那三步的顺序（记账 PENDING → 落盘 → 改 ACTIVE）与「刻意不加事务」是一对判据，
 * 抄第二份时最容易丢掉的恰恰是后者 —— 而丢掉它的症状是「磁盘有文件、库里没有」
 * 的孤儿，孤儿查不出来，统计永远少算，回收清单里永远不出现，
 * 只能靠人去 {@code du} 才发现。
 *
 * <p>顺带解决一条架构约束：{@code ArchitectureTest} 不许 {@code portal..} 下的
 * controller 碰 {@code *Mapper}（直连 Mapper 等于把业务写进 web 层）。
 * C 端头像上传住在 {@code portal/mp}，于是它本来就不能自己记账。
 *
 * <p><b>key 的构造不在这里</b>：商品图是门店级、证件是主体级、头像是用户级，
 * 三种归属的分段规则不同，各自在调用方构造。这个类只认「给我一个 key」。
 */
@Service
public class MediaUploadService {

    private final MediaStore mediaStore;
    private final SysMediaAssetMapper assetMapper;

    public MediaUploadService(MediaStore mediaStore, SysMediaAssetMapper assetMapper) {
        this.mediaStore = mediaStore;
        this.assetMapper = assetMapper;
    }

    /**
     * 记账 → 落盘 → 置 ACTIVE。
     *
     * <p><b>这个方法没有 {@code @Transactional}，是故意的。</b>
     * 三步包在一个事务里的话，落盘成功而事务回滚就留下「磁盘有文件、库里没有」的孤儿。
     * 不用事务则两种崩法都只留下可对账的 PENDING 行：有行无文件就删行，
     * 有行有文件就补成 ACTIVE，都由对账任务处理。
     *
     * @param src        每次调用给一条新流 —— 读宽高与落盘各要从头读一遍
     * @param entityNo   归属主体；用户级资产传 {@link SysMediaAsset#USER_SCOPE}
     * @param storeNo    归属门店；主体级传 {@link SysMediaAsset#ENTITY_SCOPE}，用户级传 {@code USER_SCOPE}
     * @param uploadedBy 谁传的
     */
    public void store(String key, ImageProbe.InputStreamSource src, long size, String contentType,
                      String bizType, String entityNo, String storeNo, String uploadedBy)
            throws IOException {
        // ① 先记账。拿不到 id 就不落盘 —— 顺序反了会产生查不出来的孤儿
        SysMediaAsset asset = new SysMediaAsset();
        asset.setAssetKey(key);
        asset.setEntityNo(entityNo);
        asset.setStoreNo(storeNo);
        asset.setBizType(bizType);
        asset.setBytes(size);
        asset.setContentType(contentType);
        asset.setStatus(SysMediaAsset.PENDING);
        asset.setUploadedBy(uploadedBy);
        int[] wh = ImageProbe.dimensionsOf(src);
        asset.setWidth(wh[0] > 0 ? wh[0] : null);
        asset.setHeight(wh[1] > 0 ? wh[1] : null);
        LocalDateTime now = LocalDateTime.now();
        asset.setCreatedAt(now);
        asset.setUpdatedAt(now);
        assetMapper.insert(asset);

        // ② 落字节
        try (InputStream in = src.open()) {
            mediaStore.put(key, in, size, contentType);
        }

        // ③ 这一刻起才算在用
        SysMediaAsset done = new SysMediaAsset();
        done.setId(asset.getId());
        done.setStatus(SysMediaAsset.ACTIVE);
        done.setUpdatedAt(LocalDateTime.now());
        assetMapper.updateById(done);
    }
}
