package ai.neargo.shop.elec.service.impl;

import ai.neargo.shop.elec.entity.ElcStock;

import java.time.Instant;
import java.util.List;
import java.util.Map;

/**
 * 一张待确认的表，解析与比对之后的样子。<b>只在内存里</b>（{@link PendingBatchCache}），确认之前库里只有批次元数据。
 *
 * <p>不放原始单元格（只有问题行留着，导出要用）：重建与改映射都从磁盘原件重读，内存省一半。
 *
 * @param deadline 上传起 {@code pendingTtlMinutes} 的绝对时刻。<b>重建出来的条目沿用它</b>，不从重建那刻重算
 * @param parsed   逐行解析结果
 * @param kinds    行号 → INSERT / UPDATE / UNCHANGED（按生成这份预览时的库存；确认时重算）
 * @param delist   全量替换将下架的在售行
 * @param onSale   生成预览时这家在售行数（下架护栏的分母）
 */
record PendingBatch(String batchNo, String supplierNo, Instant deadline, StockSheetParser.Result parsed,
                    Map<Integer, String> kinds, List<ElcStock> delist, int toInsert, int toUpdate, int unchanged,
                    int onSale) {

    static final String INSERT = "INSERT";
    static final String UPDATE = "UPDATE";
    static final String UNCHANGED = "UNCHANGED";
    static final String DELIST = "DELIST";
    static final String PROBLEM = "PROBLEM";

    int valid() {
        return parsed.total() - parsed.invalid();
    }
}
