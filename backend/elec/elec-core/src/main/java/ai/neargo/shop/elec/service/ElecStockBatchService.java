package ai.neargo.shop.elec.service;

import ai.neargo.shop.elec.dto.SupplierDtos.BatchSummary;

import java.nio.file.Path;
import java.util.List;

/** 上传记录：供应商看自己的、运营看某一家的；运营能下载原件 */
public interface ElecStockBatchService {

    List<BatchSummary> mine(String userNo, int page, int size);

    List<BatchSummary> ofSupplier(String supplierNo, int page, int size);

    /** 运营下载原件。原件已被清理 → ELEC_UPLOAD_FILE_PURGED */
    OriginalFile original(String supplierNo, String batchNo);

    /**
     * @param name 原文件名（给 Content-Disposition 用）；没记下时用批次号
     */
    record OriginalFile(String name, String contentType, Path path) {
    }
}
