package ai.neargo.shop.elec.service;

import ai.neargo.shop.elec.dto.SupplierDtos.BatchPreview;

import java.util.Map;

/**
 * 上传库存：先预览、确认后才上架。<b>预览一行库存都不动</b>。
 */
public interface ElecStockImportService {

    /**
     * @param mode         MERGE（只改表里有的行）/ REPLACE（表里没有的下架）
     * @param taxIncluded  价格含不含税；null = 看表头（写了「未税」就按未税），都没写按含税
     */
    BatchPreview upload(String userNo, String fileName, byte[] bytes, String mode, Boolean taxIncluded);

    /** 换列映射后重算预览，不用再传一次文件 */
    BatchPreview remap(String userNo, String batchNo, Map<String, Integer> columns);

    /** 确认上架。按<b>此刻</b>的库存重算一遍再写，不信预览时的数 */
    BatchPreview apply(String userNo, String batchNo);
}
