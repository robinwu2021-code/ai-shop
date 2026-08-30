package ai.neargo.shop.media;

import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import java.time.LocalDateTime;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * 标记扫描：算出「谁还被引用着、谁没有了」，<b>只改状态，一个文件都不删</b>。
 *
 * <p>本期明确不做自动删除（TDD §L1）。删除一律要运营在页面上看过、勾选、强确认。
 * 把两件事分开的理由很简单：<b>只读的可以自动，破坏性的必须人工。</b>
 * 所以这个扫描可以每晚跑，运营早上进来就有一份新鲜清单，而不是点一下等几十秒。
 *
 * <p>一趟做三件事：
 * <ol>
 *   <li>仍被引用的 → 刷新 {@code last_referenced_at} 与 {@code last_ref_desc}
 *       （运营端那列「可回收理由」就是它们的自然结果，不是事后推断）</li>
 *   <li>已在清单里但<b>又被引用</b>的 → <b>救回 ACTIVE</b>，清空 {@code marked_at}。
 *       没有这一步，回收站就是单向的，误判不可逆</li>
 *   <li>无人引用且已过宽限期的 → 进清单</li>
 * </ol>
 */
@Component
public class MediaScanner {

    private static final Logger log = LoggerFactory.getLogger(MediaScanner.class);

    private final List<MediaRefSource> sources;
    private final MediaRefMapper refMapper;
    private final SysMediaAssetMapper assetMapper;
    private final int graceHours;
    private final double abnormalRatio;

    public MediaScanner(List<MediaRefSource> sources, MediaRefMapper refMapper,
                        SysMediaAssetMapper assetMapper,
                        @Value("${shop.media.scan.grace-hours:72}") int graceHours,
                        @Value("${shop.media.scan.abnormal-ratio-alert:0.5}") double abnormalRatio) {
        this.sources = sources;
        this.refMapper = refMapper;
        this.assetMapper = assetMapper;
        this.graceHours = graceHours;
        this.abnormalRatio = abnormalRatio;
    }

    /** @return 本轮的统计，给日志与运营端看 */
    public Result scan() {
        Map<String, String> referenced = collectReferences();

        // 只看这两种状态：PENDING 归对账任务管，PURGED 已经是终态
        List<SysMediaAsset> assets = assetMapper.selectList(Wrappers.<SysMediaAsset>lambdaQuery()
                .in(SysMediaAsset::getStatus, SysMediaAsset.ACTIVE, SysMediaAsset.RECLAIMABLE));

        LocalDateTime now = LocalDateTime.now();
        LocalDateTime graceLine = now.minusHours(graceHours);
        int rescued = 0;
        int marked = 0;

        for (SysMediaAsset a : assets) {
            String label = referenced.get(a.getAssetKey());
            if (label != null) {
                SysMediaAsset upd = new SysMediaAsset();
                upd.setId(a.getId());
                upd.setLastReferencedAt(now);
                upd.setLastRefDesc(label);
                upd.setUpdatedAt(now);
                if (SysMediaAsset.RECLAIMABLE.equals(a.getStatus())) {
                    // 救回。marked_at 必须真的置空 —— 留着的话下次进清单会用一个
                    // 过期的起算点，「在清单里待了多少天」就是错的
                    upd.setStatus(SysMediaAsset.ACTIVE);
                    assetMapper.clearMarkedAt(a.getId());
                    rescued++;
                }
                assetMapper.updateById(upd);
                continue;
            }

            if (SysMediaAsset.ACTIVE.equals(a.getStatus())) {
                /*
                 * 宽限期：商家传完图、还没点保存商品的那一刻，这张图**确实**没人引用。
                 * 此时把它列进清单，运营看到的就是一张正在被使用的图 —— 而它随时可能被删掉。
                 */
                if (a.getCreatedAt() != null && a.getCreatedAt().isAfter(graceLine)) {
                    continue;
                }
                SysMediaAsset upd = new SysMediaAsset();
                upd.setId(a.getId());
                upd.setStatus(SysMediaAsset.RECLAIMABLE);
                upd.setMarkedAt(now);
                upd.setUpdatedAt(now);
                assetMapper.updateById(upd);
                marked++;
            }
        }

        int total = assets.size();
        long reclaimable = assets.stream().filter(a ->
                SysMediaAsset.RECLAIMABLE.equals(a.getStatus())).count() + marked - rescued;
        boolean abnormal = total > 0 && (double) reclaimable / total > abnormalRatio;
        if (abnormal) {
            /*
             * 不自动做任何事（本来也不会自动删），只是把它喊出来，
             * 并让运营端置顶红条、禁用批量回收：先查为什么，而不是照删。
             *
             * **提示语要先指向解析，再指向声明。** 上一版只写了「检查是不是有图片列
             * 没登记进 MediaRefSource」—— 2026-08-30 真出事那次，登记是齐的、
             * MediaRefCoverageTest 全绿，坏的是 MediaKeys 抠不出 COS 形态的地址。
             * 照着那句提示查会一路查到「声明没问题」，然后很自然地得出
             * 「阈值定太保守了」，去调高它 —— 而那一下是不可逆的。
             * 一条把人引向错误结论的提示，比没有提示更糟。
             */
            log.warn("图片扫描异常：{} / {} 被判为可回收（{}%），超过阈值 {}%。"
                            + "本轮抠出的引用只有 {} 条 —— 若这个数接近 0，"
                            + "先查 MediaKeys 认不认得出当前 provider 的地址形态"
                            + "（2026-08-30 就是这里：切 COS 后一个都抠不出）；"
                            + "引用数正常才去查是不是有图片列没登记进 MediaRefSource。"
                            + "**在查清楚之前不要调高阈值** —— 它是这里唯一的保险",
                    reclaimable, total, Math.round(reclaimable * 100.0 / total),
                    Math.round(abnormalRatio * 100), referenced.size());
        }
        log.info("图片扫描完成：共 {} 张，引用中 {}，本轮新进清单 {}，救回 {}",
                total, referenced.size(), marked, rescued);
        return new Result(total, referenced.size(), marked, rescued, abnormal);
    }

    /**
     * 把所有声明列里当前引用着的 key 收齐。
     *
     * <p>内存：全量 key 进一个 map。1 万商品 × 6 张 ≈ 6 万条、每条约 100 字节 ≈ 6 MB，
     * 当前量级毫无压力。到百万级时改成按 {@code entity_no} 分片跑
     * —— 结构上已经支持，因为这里对每一列是独立扫的。
     */
    private Map<String, String> collectReferences() {
        Map<String, String> referenced = new HashMap<>();
        for (MediaRefSource source : sources) {
            for (MediaRefColumn col : source.columns()) {
                List<Map<String, Object>> rows;
                try {
                    rows = refMapper.scanColumn(col.table(), col.column(), col.keyColumn());
                } catch (Exception e) {
                    /*
                     * **不能吞掉**：一列扫不出来就意味着它引用的图全会被判成孤儿。
                     * 抛出去让整轮扫描失败，比默默少扫一列安全得多 ——
                     * 后者的表现是「某天早上清单里突然多了几百张在用的图」。
                     */
                    throw new IllegalStateException(
                            "扫描 " + col.table() + "." + col.column() + " 失败，本轮中止", e);
                }
                for (Map<String, Object> row : rows) {
                    Object val = row.get("val");
                    if (val == null) {
                        continue;
                    }
                    Object bizKey = row.get("biz_key");
                    String desc = col.label() + (bizKey == null ? "" : "（" + bizKey + "）");
                    for (String key : MediaKeys.extract(val.toString())) {
                        referenced.put(key, desc);
                    }
                }
            }
        }
        return referenced;
    }

    /** @param abnormal 可回收占比是否超过阈值 —— 运营端据此置顶红条并禁用批量回收 */
    public record Result(int total, int referenced, int marked, int rescued, boolean abnormal) {
    }
}
