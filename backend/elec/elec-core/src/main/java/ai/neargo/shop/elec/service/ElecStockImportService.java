package ai.neargo.shop.elec.service;

import ai.neargo.shop.elec.dto.SupplierDtos.BatchPreview;
import ai.neargo.shop.elec.dto.SupplierDtos.PreviewRow;

import java.util.List;
import java.util.Map;

/**
 * 上传库存：先预览、确认后才上架。<b>预览一行库存都不动</b>；待确认的数据只在内存里，从上传起最多一小时。
 */
public interface ElecStockImportService {

    /**
     * 原件先以原名落进未入库区，再解析、认列、与现有库存比对。认不出料号或数量是哪一列时回
     * {@code NEED_MAPPING}（不报错），供应商在页面上选。同一家之前待确认的那张作废。
     *
     * @param fileName    端上选的原文件名（小程序 uploadFile 传上来的是临时路径名，要端上单独带）
     * @param mode        MERGE（只改表里有的行）/ REPLACE（表里没有的下架）
     * @param taxIncluded 价格含不含税；null = 看表头（写了「未税」就按未税），都没写按含税
     */
    BatchPreview upload(String userNo, String fileName, byte[] bytes, String mode, Boolean taxIncluded);

    /** 换列映射后重算预览，不用再传一次文件。也是 NEED_MAPPING 的出口 */
    BatchPreview remap(String userNo, String batchNo, Map<String, Integer> columns);

    /**
     * 预览里的行：解析之后的值。
     *
     * @param view INSERT / UPDATE / UNCHANGED / DELIST / PROBLEM（有错误或警告的行）
     */
    List<PreviewRow> rows(String userNo, String batchNo, String view, int page, int size);

    /**
     * 确认上架。按<b>此刻</b>的库存重算一遍再写，不信预览时的数。
     *
     * @param expectDelist 此刻将下架的行数；过了下架护栏的线时必须带，且要与重算的一致
     */
    BatchPreview apply(String userNo, String batchNo, Integer expectDelist);

    /** 放弃：清掉内存里的数据。原件留在未入库区，等每周清理 */
    BatchPreview cancel(String userNo, String batchNo);

    /** 一次上传的详情：待确认的从内存（或原件重建）读，其余从库读 */
    BatchPreview detail(String userNo, String batchNo);

    /** 问题行导出：原表头 + 原行号 + 问题，出错的格标红，全部是文本格 */
    ProblemsFile problems(String userNo, String batchNo);

    /** @param name 下载时建议的文件名 */
    record ProblemsFile(String name, byte[] bytes) {
    }
}
