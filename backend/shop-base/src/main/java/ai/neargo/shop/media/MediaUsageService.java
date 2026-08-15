package ai.neargo.shop.media;

import ai.neargo.shop.common.PageData;
import com.baomidou.mybatisplus.core.metadata.IPage;
import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 运营端「存储空间治理」那三个页签要的所有读。
 *
 * <p><b>它存在的直接原因是一条守卫</b>：{@code ArchitectureTest.controllersMustNotTouchMappers}。
 * 初稿把这些查询写在 Controller 里直连 Mapper，当场被拦下 —— 而那条规则要防的正是
 * 「Controller 直连数据」这类最难拆的债（TDD-backend 把它记为 powerbank 的头号教训）。
 *
 * <p>与 {@link MediaPurgeService} 分开：这里只读，那里会删文件。
 * 两种风险等级的代码放一个类里，日后加方法的人很难一眼看出自己在哪一边。
 */
@Component
public class MediaUsageService {

    private final SysMediaAssetMapper assetMapper;
    private final SysMediaPurgeBatchMapper batchMapper;
    private final MediaPurgeService purgeService;

    /**
     * 可回收占比超过它，页面置顶红条并<b>禁用批量回收</b> ——
     * 多半是有图片列没登记进 {@link MediaRefSource}。
     *
     * <p><b>与 {@link MediaScanner} 读同一个配置项</b>。初版这里写死了 0.5，
     * 而扫描器那边读配置 —— 调了配置之后，日志里的告警阈值变了、
     * 而真正拦住删除按钮的这一侧还停在 0.5。两份判据里偏偏是没跟着变的那个更要紧。
     */
    private final double abnormalRatio;

    public MediaUsageService(SysMediaAssetMapper assetMapper, SysMediaPurgeBatchMapper batchMapper,
                             MediaPurgeService purgeService,
                             @Value("${shop.media.scan.abnormal-ratio-alert:0.5}") double abnormalRatio) {
        this.assetMapper = assetMapper;
        this.batchMapper = batchMapper;
        this.purgeService = purgeService;
        this.abnormalRatio = abnormalRatio;
    }

    /** 顶部四张卡。{@code abnormal} 为真时前端置顶红条并禁用批量回收。 */
    public OverviewVO overview() {
        List<SysMediaAsset> all = live();
        long activeBytes = 0;
        long reclaimableBytes = 0;
        int activeCount = 0;
        int reclaimableCount = 0;
        for (SysMediaAsset a : all) {
            long b = bytesOf(a);
            if (SysMediaAsset.RECLAIMABLE.equals(a.getStatus())) {
                reclaimableBytes += b;
                reclaimableCount++;
            } else {
                activeBytes += b;
                activeCount++;
            }
        }
        boolean abnormal = !all.isEmpty() && (double) reclaimableCount / all.size() > abnormalRatio;
        return new OverviewVO(activeBytes + reclaimableBytes, all.size(),
                activeBytes, activeCount, reclaimableBytes, reclaimableCount, abnormal);
    }

    /**
     * 门店占用，<b>默认按待回收倒序</b>。
     *
     * <p>这一页的目的就是找出最该清的店，不该让人自己去排 ——
     * 默认序是这个页面唯一的产品决策。
     */
    public List<StoreUsageVO> stores() {
        Map<String, long[]> agg = new LinkedHashMap<>();
        Map<String, String> entityOf = new LinkedHashMap<>();
        for (SysMediaAsset a : live()) {
            long[] v = agg.computeIfAbsent(a.getStoreNo(), k -> new long[3]);
            v[2]++;
            if (SysMediaAsset.RECLAIMABLE.equals(a.getStatus())) {
                v[1] += bytesOf(a);
            } else {
                v[0] += bytesOf(a);
            }
            entityOf.putIfAbsent(a.getStoreNo(), a.getEntityNo());
        }
        return agg.entrySet().stream()
                .map(e -> new StoreUsageVO(e.getKey(), entityOf.get(e.getKey()),
                        e.getValue()[2], e.getValue()[0], e.getValue()[1]))
                .sorted(Comparator.comparingLong(StoreUsageVO::reclaimableBytes).reversed())
                .toList();
    }

    public PageData<ReclaimableVO> reclaimable(MediaPurgeService.Filter filter, long page, long size) {
        IPage<SysMediaAsset> result = assetMapper.selectPage(new Page<>(page, size),
                purgeService.reclaimableQuery(filter).orderByDesc(SysMediaAsset::getMarkedAt));
        return PageData.of(result.getRecords().stream().map(ReclaimableVO::of).toList(),
                result.getTotal(), page, size);
    }

    public List<SysMediaPurgeBatch> batches() {
        return batchMapper.selectList(Wrappers.<SysMediaPurgeBatch>lambdaQuery()
                .orderByDesc(SysMediaPurgeBatch::getId).last("limit 100"));
    }

    public BatchDetailVO batch(String batchNo) {
        SysMediaPurgeBatch batch = batchMapper.selectOne(Wrappers.<SysMediaPurgeBatch>lambdaQuery()
                .eq(SysMediaPurgeBatch::getBatchNo, batchNo));
        List<ReclaimableVO> items = assetMapper.selectList(Wrappers.<SysMediaAsset>lambdaQuery()
                        .eq(SysMediaAsset::getPurgeBatchNo, batchNo))
                .stream().map(ReclaimableVO::of).toList();
        return new BatchDetailVO(batch, items);
    }

    private List<SysMediaAsset> live() {
        return assetMapper.selectList(Wrappers.<SysMediaAsset>lambdaQuery()
                .in(SysMediaAsset::getStatus, SysMediaAsset.ACTIVE, SysMediaAsset.RECLAIMABLE));
    }

    private static long bytesOf(SysMediaAsset a) {
        return a.getBytes() == null ? 0 : a.getBytes();
    }

    public record OverviewVO(long totalBytes, int totalCount,
                             long activeBytes, int activeCount,
                             long reclaimableBytes, int reclaimableCount,
                             boolean abnormal) {
    }

    public record StoreUsageVO(String storeNo, String entityNo, long count,
                               long activeBytes, long reclaimableBytes) {
    }

    /**
     * 待回收的一行。<b>{@code reason} 是这一列的全部意义</b> ——
     * 运营要靠它判断「这张能不能删」，而它来自扫描时落下的真实数据，不是事后推断。
     */
    public record ReclaimableVO(String assetKey, String entityNo, String storeNo, String bizType,
                                long bytes, Integer width, Integer height,
                                String uploadedBy, String createdAt, String markedAt,
                                String reason, String status) {

        static ReclaimableVO of(SysMediaAsset a) {
            String reason = a.getLastReferencedAt() == null
                    ? "从未被引用"
                    : "曾被「" + a.getLastRefDesc() + "」引用，" + a.getLastReferencedAt() + " 后失去引用";
            return new ReclaimableVO(a.getAssetKey(), a.getEntityNo(), a.getStoreNo(), a.getBizType(),
                    bytesOf(a), a.getWidth(), a.getHeight(), a.getUploadedBy(),
                    a.getCreatedAt() == null ? null : a.getCreatedAt().toString(),
                    a.getMarkedAt() == null ? null : a.getMarkedAt().toString(),
                    reason, a.getStatus());
        }
    }

    public record BatchDetailVO(SysMediaPurgeBatch batch, List<ReclaimableVO> items) {
    }
}
