package ai.neargo.shop.elec.service.impl;

import ai.neargo.shop.common.BizException;
import ai.neargo.shop.common.ErrorCode;
import ai.neargo.shop.elec.config.ConditionalOnElec;
import ai.neargo.shop.elec.config.ElecProperties;
import ai.neargo.shop.elec.dto.SupplierDtos.BatchSummary;
import ai.neargo.shop.elec.entity.ElcStockBatch;
import ai.neargo.shop.elec.mapper.ElecMappers.StockBatchMapper;
import ai.neargo.shop.elec.service.ElecStockBatchService;
import ai.neargo.shop.elec.support.UploadFileStore;
import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import org.springframework.stereotype.Service;

import java.nio.file.Path;
import java.time.LocalDateTime;
import java.util.List;

@ConditionalOnElec
@Service
public class ElecStockBatchServiceImpl implements ElecStockBatchService {

    private static final int MAX_PAGE = 50;

    private final ElecSupplierAccess access;
    private final StockBatchMapper batchMapper;
    private final UploadFileStore files;
    private final ElecProperties props;

    public ElecStockBatchServiceImpl(ElecSupplierAccess access, StockBatchMapper batchMapper, UploadFileStore files,
                                     ElecProperties props) {
        this.access = access;
        this.batchMapper = batchMapper;
        this.files = files;
        this.props = props;
    }

    @Override
    public List<BatchSummary> mine(String userNo, int page, int size) {
        return ofSupplier(access.requireActive(userNo).getSupplierNo(), page, size);
    }

    @Override
    public List<BatchSummary> ofSupplier(String supplierNo, int page, int size) {
        int sz = Math.max(1, Math.min(MAX_PAGE, size));
        int offset = Math.max(0, page - 1) * sz;
        LocalDateTime now = LocalDateTime.now();
        int ttl = props.getUpload().getPendingTtlMinutes();
        return batchMapper.selectList(Wrappers.<ElcStockBatch>lambdaQuery()
                        .eq(ElcStockBatch::getSupplierNo, supplierNo)
                        .orderByDesc(ElcStockBatch::getId)
                        .last("LIMIT " + sz + " OFFSET " + offset)).stream()
                .map(b -> new BatchSummary(b.getBatchNo(), b.getFileName(), b.getMode(), b.displayStatus(now, ttl),
                        b.getFailCode(), n(b.getRowTotal()), n(b.getRowValid()), n(b.getRowInvalid()),
                        n(b.getRowWarn()), n(b.getToInsert()), n(b.getToUpdate()), n(b.getToDelist()),
                        n(b.getUnchanged()), Boolean.TRUE.equals(b.getAiUsed()),
                        b.getFilePath() != null && b.getFilePurgedAt() == null, b.getCreatedAt(), b.getAppliedAt()))
                .toList();
    }

    @Override
    public OriginalFile original(String supplierNo, String batchNo) {
        ElcStockBatch b = batchMapper.selectOne(Wrappers.<ElcStockBatch>lambdaQuery()
                .eq(ElcStockBatch::getBatchNo, batchNo).eq(ElcStockBatch::getSupplierNo, supplierNo));
        if (b == null) {
            throw BizException.of(ErrorCode.NOT_FOUND);
        }
        String area = ElcStockBatch.AREA_APPLIED.equals(b.getFileArea()) ? UploadFileStore.APPLIED
                : UploadFileStore.FAILED;
        Path p = b.getFilePurgedAt() != null ? null : files.locate(b.getFilePath(), area).orElse(null);
        if (p == null) {
            throw BizException.of(ErrorCode.ELEC_UPLOAD_FILE_PURGED);
        }
        String ext = p.getFileName().toString().substring(p.getFileName().toString().lastIndexOf('.') + 1);
        String name = b.getFileName() == null || b.getFileName().isBlank() ? b.getBatchNo() + "." + ext
                : b.getFileName();
        String type = switch (ext) {
            case "xlsx" -> "application/vnd.openxmlformats-officedocument.spreadsheetml.sheet";
            case "xls" -> "application/vnd.ms-excel";
            default -> "text/csv";
        };
        return new OriginalFile(name, type, p);
    }

    private static int n(Integer v) {
        return v == null ? 0 : v;
    }
}
