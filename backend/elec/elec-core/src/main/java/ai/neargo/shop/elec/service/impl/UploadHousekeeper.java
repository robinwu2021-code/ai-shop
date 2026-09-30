package ai.neargo.shop.elec.service.impl;

import ai.neargo.shop.elec.config.ConditionalOnElec;
import ai.neargo.shop.elec.config.ElecProperties;
import ai.neargo.shop.elec.entity.ElcStockBatch;
import ai.neargo.shop.elec.mapper.ElecMappers.StockBatchMapper;
import ai.neargo.shop.elec.support.UploadFileStore;
import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

/**
 * 上传原件的定期清理（每周一次，elec-svc 按时叫它）。三步，顺序不能反：
 *
 * <ol>
 *   <li><b>先补移</b>：已上架、但当时移区失败还留在未入库区的原件，先移到已入库区。
 *       不先做这一步的话，下一步会把一个已上架的原件当成未入库删掉。补移失败的那天的目录这次不删</li>
 *   <li>删未入库区里早于保留天数的日期目录，并给对应批次记上「原件已清理」</li>
 *   <li>已入库区：保留天数为 0（当前取值）时不动</li>
 * </ol>
 */
@Slf4j
@ConditionalOnElec
@Component
public class UploadHousekeeper {

    private final StockBatchMapper batchMapper;
    private final UploadFileStore files;
    private final ElecProperties props;

    public UploadHousekeeper(StockBatchMapper batchMapper, UploadFileStore files, ElecProperties props) {
        this.batchMapper = batchMapper;
        this.files = files;
        this.props = props;
    }

    /** @param rehomed 补移成功几个；rehomeFailed 失败几个 */
    public record Report(int rehomed, int rehomeFailed, List<UploadFileStore.Purged> purged, List<String> unknown,
                         long failedBytes, long appliedBytes) {
    }

    public Report run(LocalDate today) {
        LocalDateTime now = LocalDateTime.now();
        // ① 补移
        int rehomed = 0;
        Set<String> skipDays = new HashSet<>();
        for (ElcStockBatch b : batchMapper.selectList(Wrappers.<ElcStockBatch>lambdaQuery()
                .eq(ElcStockBatch::getFileArea, ElcStockBatch.AREA_FAILED)
                .eq(ElcStockBatch::getStatus, ElcStockBatch.STATUS_APPLIED)
                .isNull(ElcStockBatch::getFilePurgedAt))) {
            if (b.getFilePath() == null) {
                continue;
            }
            if (files.moveToApplied(b.getFilePath())) {
                batchMapper.setFileArea(b.getBatchNo(), ElcStockBatch.AREA_APPLIED);
                rehomed++;
            } else {
                skipDays.add(b.getFilePath().substring(0, b.getFilePath().indexOf('/')));
                log.error("补移失败：已上架批次 {} 的原件 {} 还在未入库区，这天的目录这次不删", b.getBatchNo(),
                        b.getFilePath());
            }
        }
        // ② 未入库区
        List<String> unknown = new ArrayList<>();
        List<UploadFileStore.Purged> purged = new ArrayList<>(purge(UploadFileStore.FAILED, ElcStockBatch.AREA_FAILED,
                today.minusDays(props.getUpload().getFailedRetentionDays()), skipDays, unknown, now));
        // ③ 已入库区：0 = 不删
        int appliedDays = props.getUpload().getAppliedRetentionDays();
        if (appliedDays > 0) {
            purged.addAll(purge(UploadFileStore.APPLIED, ElcStockBatch.AREA_APPLIED, today.minusDays(appliedDays),
                    Set.of(), unknown, now));
        }
        Report r = new Report(rehomed, skipDays.size(), purged, unknown, files.usage(UploadFileStore.FAILED),
                files.usage(UploadFileStore.APPLIED));
        for (String u : unknown) {
            log.warn("上传目录里有不认识的目录，没动它：{}", u);
        }
        log.info("上传原件清理：补移 {} 个、删了 {} 个日期目录共 {} 字节；现占用 未入库 {} 字节、已入库 {} 字节",
                rehomed, purged.size(), purged.stream().mapToLong(UploadFileStore.Purged::bytes).sum(),
                r.failedBytes(), r.appliedBytes());
        return r;
    }

    private List<UploadFileStore.Purged> purge(String dir, String area, LocalDate before, Set<String> skip,
                                               List<String> unknown, LocalDateTime now) {
        List<UploadFileStore.Purged> done = files.purge(dir, before, skip, unknown);
        for (UploadFileStore.Purged p : done) {
            batchMapper.markPurged(area, p.day() + "/%", now);
        }
        return done;
    }
}
