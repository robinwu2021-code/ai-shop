package ai.neargo.shop.media;

import ai.neargo.shop.common.BizException;
import ai.neargo.shop.common.ErrorCode;
import ai.neargo.shop.spi.platform.AuditLogPort;
import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

/**
 * 回收任务：提交与执行。<b>这是整套机制里唯一真的删文件的地方。</b>
 *
 * <p>提交与执行分开成两步（{@link #submit} 建批次 → {@link #runQueued} 由任务捡起来跑），
 * 而不是在请求里同步删完：
 * <ul>
 *   <li>删几千个文件要几秒到几十秒，同步做就是让运营对着转圈的页面等</li>
 *   <li><b>更要紧的是重跑</b>：同步做的话，中途失败就只剩一个 HTTP 500，
 *       已经删掉的和没删的混在一起，没人说得清该从哪继续</li>
 * </ul>
 */
@Component
public class MediaPurgeService {

    private static final Logger log = LoggerFactory.getLogger(MediaPurgeService.class);

    private static final DateTimeFormatter BATCH_TS = DateTimeFormatter.ofPattern("yyyyMMddHHmmss");

    private final SysMediaAssetMapper assetMapper;
    private final SysMediaPurgeBatchMapper batchMapper;
    private final MediaStore mediaStore;
    private final AuditLogPort auditLogPort;

    public MediaPurgeService(SysMediaAssetMapper assetMapper, SysMediaPurgeBatchMapper batchMapper,
                             MediaStore mediaStore, AuditLogPort auditLogPort) {
        this.assetMapper = assetMapper;
        this.batchMapper = batchMapper;
        this.mediaStore = mediaStore;
        this.auditLogPort = auditLogPort;
    }

    /**
     * 提交一批回收。两种入参二选一：
     * <ul>
     *   <li>{@code assetKeys} —— 当前页勾选的那些</li>
     *   <li>{@code filter + expectedCount} —— 跨页「选中筛选结果全部」</li>
     * </ul>
     *
     * <p><b>跨页全选必须带 {@code expectedCount}，对不上就整批拒绝。</b>
     * 从运营看到清单到点下确认，中间可能刚好跑过一次扫描把几张救回去了；
     * 不比对的话删掉的就是运营没看过的那几张 —— 而这正是「人工确认」要防的事。
     */
    public String submit(List<String> assetKeys, Filter filter, Integer expectedCount,
                         String operator, String operatorName) {

        List<SysMediaAsset> targets = resolve(assetKeys, filter, expectedCount);
        if (targets.isEmpty()) {
            throw BizException.of(ErrorCode.BAD_REQUEST);
        }

        LocalDateTime now = LocalDateTime.now();
        String batchNo = "MP" + now.format(BATCH_TS) + Long.toString(System.nanoTime() % 100000);

        SysMediaPurgeBatch batch = new SysMediaPurgeBatch();
        batch.setBatchNo(batchNo);
        batch.setOperator(operator);
        batch.setOperatorName(operatorName);
        batch.setStatus(SysMediaPurgeBatch.QUEUED);
        batch.setTotalCount(targets.size());
        batch.setTotalBytes(targets.stream().mapToLong(a -> a.getBytes() == null ? 0 : a.getBytes()).sum());
        batch.setPurgedCount(0);
        batch.setFailedCount(0);
        batch.setCreatedAt(now);
        batch.setUpdatedAt(now);
        batchMapper.insert(batch);

        /*
         * 先把资产挂上批次号再排队执行。挂号这一步就是「认领」——
         * 挂过号的行不会被下一个批次再挑走，同一张图不会同时属于两批。
         */
        for (SysMediaAsset a : targets) {
            SysMediaAsset upd = new SysMediaAsset();
            upd.setId(a.getId());
            upd.setPurgeBatchNo(batchNo);
            upd.setUpdatedAt(now);
            assetMapper.updateById(upd);
        }

        auditLogPort.record("MEDIA_PURGE_SUBMIT", batchNo,
                "提交图片回收：" + targets.size() + " 张 / " + batch.getTotalBytes() + " 字节", true);
        log.info("图片回收批次已提交 {}：{} 张，{} 字节，发起人 {}",
                batchNo, targets.size(), batch.getTotalBytes(), operator);
        return batchNo;
    }

    /** 把 {@code QUEUED} 与上一轮没跑完的 {@code RUNNING} 都捡起来跑。 */
    public void runQueued() {
        List<SysMediaPurgeBatch> pending = batchMapper.selectList(
                Wrappers.<SysMediaPurgeBatch>lambdaQuery()
                        .in(SysMediaPurgeBatch::getStatus,
                                SysMediaPurgeBatch.QUEUED, SysMediaPurgeBatch.RUNNING)
                        .orderByAsc(SysMediaPurgeBatch::getId));
        for (SysMediaPurgeBatch batch : pending) {
            run(batch);
        }
    }

    /**
     * 跑一批。<b>幂等</b>：已经 {@code PURGED} 的行会被跳过，
     * 所以整批重跑是安全的 —— 失败重试、进程重启后接着跑，都走这一条路。
     */
    public void run(SysMediaPurgeBatch batch) {
        LocalDateTime now = LocalDateTime.now();
        markRunning(batch, now);

        // 只挑还没删的：重跑时已 PURGED 的那些不会再来一遍
        List<SysMediaAsset> targets = assetMapper.selectList(Wrappers.<SysMediaAsset>lambdaQuery()
                .eq(SysMediaAsset::getPurgeBatchNo, batch.getBatchNo())
                .ne(SysMediaAsset::getStatus, SysMediaAsset.PURGED));

        int purged = 0;
        int failed = 0;
        for (SysMediaAsset a : targets) {
            try {
                mediaStore.delete(List.of(a.getAssetKey()));
                SysMediaAsset upd = new SysMediaAsset();
                upd.setId(a.getId());
                upd.setStatus(SysMediaAsset.PURGED);
                upd.setPurgedAt(LocalDateTime.now());
                upd.setUpdatedAt(LocalDateTime.now());
                assetMapper.updateById(upd);
                purged++;
            } catch (Exception e) {
                // 单张失败不中断整批：它留在批次里，运营可以重跑
                log.warn("回收失败，留在批次 {} 里可重跑：{}", batch.getBatchNo(), a.getAssetKey(), e);
                failed++;
            }
        }

        SysMediaPurgeBatch upd = new SysMediaPurgeBatch();
        upd.setId(batch.getId());
        upd.setPurgedCount((batch.getPurgedCount() == null ? 0 : batch.getPurgedCount()) + purged);
        upd.setFailedCount(failed);
        upd.setStatus(failed > 0 ? SysMediaPurgeBatch.PARTIAL : SysMediaPurgeBatch.DONE);
        upd.setFinishedAt(LocalDateTime.now());
        upd.setUpdatedAt(LocalDateTime.now());
        batchMapper.updateById(upd);

        auditLogPort.record("MEDIA_PURGE_DONE", batch.getBatchNo(),
                "图片回收完成：成功 " + purged + " 张，失败 " + failed + " 张", true);
        log.info("图片回收批次 {} 完成：成功 {}，失败 {}", batch.getBatchNo(), purged, failed);
    }

    private void markRunning(SysMediaPurgeBatch batch, LocalDateTime now) {
        SysMediaPurgeBatch upd = new SysMediaPurgeBatch();
        upd.setId(batch.getId());
        upd.setStatus(SysMediaPurgeBatch.RUNNING);
        if (batch.getStartedAt() == null) {
            upd.setStartedAt(now);
        }
        upd.setUpdatedAt(now);
        batchMapper.updateById(upd);
    }

    /** 把两种入参归一成「要删哪些行」，顺便把两条闸都过一遍。 */
    private List<SysMediaAsset> resolve(List<String> assetKeys, Filter filter, Integer expectedCount) {
        if (assetKeys != null && !assetKeys.isEmpty()) {
            Set<String> unique = new LinkedHashSet<>(assetKeys);
            List<SysMediaAsset> rows = assetMapper.selectList(reclaimableQuery(null)
                    .in(SysMediaAsset::getAssetKey, unique));
            /*
             * 勾选的这批里，只要有一张已经不在待回收状态（被救回了、或已被别的批次领走），
             * 就整批拒绝而不是「删能删的那些」。
             * 部分执行会让运营以为自己删的就是看到的那些 —— 而少的那几张永远不会有人发现。
             */
            if (rows.size() != unique.size()) {
                throw BizException.of(ErrorCode.CONFLICT);
            }
            return rows;
        }

        if (filter == null || expectedCount == null) {
            throw BizException.of(ErrorCode.BAD_REQUEST);
        }
        List<SysMediaAsset> rows = assetMapper.selectList(reclaimableQuery(filter));
        // 跨页全选的那道闸：清单在这中间变过，就别删
        if (rows.size() != expectedCount) {
            throw BizException.of(ErrorCode.CONFLICT);
        }
        return rows;
    }

    /**
     * 待回收清单的公共查询条件。
     *
     * <p><b>证件默认不在里面</b>：它的留存期是法务口径，不该由工程默认决定。
     * 运营要连证件一起清，得显式把 {@code includeQual} 打开 —— 让这个未决状态在
     * 界面上看得见，而不是假装它不存在。
     */
    public LambdaQueryWrapper<SysMediaAsset> reclaimableQuery(Filter filter) {
        LambdaQueryWrapper<SysMediaAsset> q = Wrappers.<SysMediaAsset>lambdaQuery()
                .eq(SysMediaAsset::getStatus, SysMediaAsset.RECLAIMABLE)
                .isNull(SysMediaAsset::getPurgeBatchNo);
        if (filter == null) {
            return q.ne(SysMediaAsset::getBizType, SysMediaAsset.QUAL);
        }
        if (filter.storeNo() != null && !filter.storeNo().isBlank()) {
            q.eq(SysMediaAsset::getStoreNo, filter.storeNo());
        }
        if (filter.entityNo() != null && !filter.entityNo().isBlank()) {
            q.eq(SysMediaAsset::getEntityNo, filter.entityNo());
        }
        if (!filter.includeQual()) {
            q.ne(SysMediaAsset::getBizType, SysMediaAsset.QUAL);
        }
        if (filter.neverUsed() != null) {
            if (filter.neverUsed()) {
                q.isNull(SysMediaAsset::getLastReferencedAt);
            } else {
                q.isNotNull(SysMediaAsset::getLastReferencedAt);
            }
        }
        return q;
    }

    /**
     * @param neverUsed {@code true} = 只看「从未被引用」（临时图），
     *                  {@code false} = 只看「曾被引用、后被替换」，{@code null} = 都要
     */
    public record Filter(String entityNo, String storeNo, boolean includeQual, Boolean neverUsed) {
    }

    /** 给运营端预览用：这一票到底是多少张、多少字节。 */
    public Preview preview(Filter filter) {
        List<SysMediaAsset> rows = assetMapper.selectList(reclaimableQuery(filter));
        long bytes = rows.stream().mapToLong(a -> a.getBytes() == null ? 0 : a.getBytes()).sum();
        List<String> sample = new ArrayList<>();
        for (int i = 0; i < Math.min(20, rows.size()); i++) {
            sample.add(rows.get(i).getAssetKey());
        }
        return new Preview(rows.size(), bytes, sample);
    }

    public record Preview(int count, long bytes, List<String> sample) {
    }
}
