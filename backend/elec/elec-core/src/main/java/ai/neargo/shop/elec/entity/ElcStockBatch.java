package ai.neargo.shop.elec.entity;

import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Getter;
import lombok.Setter;

@Getter
@Setter
@TableName("elc_stock_batch")
public class ElcStockBatch extends ElcMutableEntity {

    /** 认不出料号或数量是哪一列，等供应商手工指定（remap 之后转 PARSED） */
    public static final String STATUS_NEED_MAPPING = "NEED_MAPPING";
    public static final String STATUS_PARSED = "PARSED";
    public static final String STATUS_APPLIED = "APPLIED";
    /** 供应商点了「放弃」 */
    public static final String STATUS_CANCELLED = "CANCELLED";
    /** 同一家又传了一张，这张作废（一家只留一张待确认） */
    public static final String STATUS_SUPERSEDED = "SUPERSEDED";
    /** 文件读不了 / 找不到表头 / 超行数。原件照样落盘，原因在 fail_code */
    public static final String STATUS_FAILED = "FAILED";
    /** 读时算出来的：待确认但已过了有效期。<b>库里不存这个值</b> */
    public static final String STATUS_EXPIRED = "EXPIRED";

    /** 原件所在的区：未入库（上传先落这里）/ 已入库（确认上架后移过去） */
    public static final String AREA_FAILED = "FAILED";
    public static final String AREA_APPLIED = "APPLIED";

    private String batchNo;

    private String supplierNo;

    private String fileName;

    private String mode;

    private Boolean taxIncluded;

    private String currency;

    private String headers;

    private String columnMap;

    /** 阶梯价列 JSON：[[列序号, 从多少起], …] */
    private String tierCols;

    private Integer rowTotal;

    private Integer rowValid;

    private Integer rowInvalid;

    private Integer toInsert;

    private Integer toUpdate;

    private Integer toDelist;

    private Integer unchanged;

    private String status;

    private java.time.LocalDateTime appliedAt;

    private Integer rowWarn;

    /** 表头在第几行（从 0 起）；从原件重建时按它解析 */
    private Integer headerRow;

    /** 字段 → REMEMBERED / ALIAS / AI / MANUAL，JSON */
    private String columnSource;

    private Boolean aiUsed;

    /** 原件相对路径（不含区）：yyyy-MM-dd/供应商号/原名_批次号.xlsx */
    private String filePath;

    private String fileArea;

    private Integer fileSize;

    private String fileSha256;

    /** 非空 = 原件已被清理任务删掉 */
    private java.time.LocalDateTime filePurgedAt;

    /** 解析失败时的错误码键 */
    private String failCode;

    /** 待确认的批次是否还在有效期内。上传、确认、翻页、导出都用这一个式子 */
    public boolean pendingAlive(java.time.LocalDateTime now, int ttlMinutes) {
        return (STATUS_PARSED.equals(status) || STATUS_NEED_MAPPING.equals(status))
                && now.isBefore(deadline(ttlMinutes));
    }

    public java.time.LocalDateTime deadline(int ttlMinutes) {
        return getCreatedAt().plusMinutes(ttlMinutes);
    }

    /** 给人看的状态：过了有效期的待确认批次显示成 EXPIRED */
    public String displayStatus(java.time.LocalDateTime now, int ttlMinutes) {
        boolean pending = STATUS_PARSED.equals(status) || STATUS_NEED_MAPPING.equals(status);
        return pending && !pendingAlive(now, ttlMinutes) ? STATUS_EXPIRED : status;
    }
}
