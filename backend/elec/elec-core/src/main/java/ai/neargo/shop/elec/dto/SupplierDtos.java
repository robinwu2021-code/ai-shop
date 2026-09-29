package ai.neargo.shop.elec.dto;

import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Map;

/** 供应商面。这一侧看得到自己的精确库存与批号，看不到任何买家。 */
public final class SupplierDtos {

    private SupplierDtos() {
    }

    /**
     * @param kind AGENT / TRADER / FACTORY / OTHER
     */
    public record RegisterReq(String companyName, String kind, String city, String contactName,
                              String contactPhone) {
    }

    /**
     * @param status        ACTIVE / SUSPENDED
     * @param onCount       在售库存行数（未到期）
     * @param expiringCount 7 天内到期的在售行数
     * @param lastUploadAt  最近一次确认上架的时间；从没传过为 null
     * @param stockTtlDays  库存多少天不更新就不再给买家看 —— 端上用它解释「为什么要定期传」
     */
    public record SupplierView(String supplierNo, String companyName, String kind, String city,
                               String contactName, String contactPhone, String maskCode, String status,
                               int onCount, int expiringCount, LocalDateTime lastUploadAt, int stockTtlDays) {
    }

    /**
     * @param tiers    阶梯价，按数量档升序。**元器件报价天生是阶梯的**；只有一档时就一条
     * @param priceE6  最低档的单价，百万分之一元（= tiers 第一条）；null = 没报价
     * @param currency CNY / USD / HKD
     * @param packing  REEL 整盘 / TRAY / TUBE / CUT_TAPE 剪带 / BULK / BOX
     * @param cond     ORIGINAL 原装原包 / LOOSE 原装散新 / PULLED 拆机 / REFURB 翻新
     * @param leadDays 交期天数，0 = 现货；null = 供应商没说（**不是现货**）
     * @param status   ON / EXPIRED（到期，端上据此提示续期）
     */
    public record StockView(String stockNo, String mpn, String mfr, long qty, String dateCode,
                            String packageName, Integer moq, Integer spq, List<PriceTier> tiers,
                            Long priceE6, String currency, boolean taxIncluded, String packing,
                            String cond, Integer leadDays, String region,
                            LocalDate validUntil, String status) {
    }

    /** @param priceE6 这一档的单价，百万分之一元 */
    public record PriceTier(long minQty, long priceE6) {
    }

    /**
     * 上传预览。<b>预览时一行库存都没动</b>，确认之后才上架。
     *
     * @param headers  表头那一行（原样），端上用它画「这几列分别是什么」
     * @param columns  字段 → 列序号（从 0 起）：MPN / MFR / QTY / DC / PACKAGE / PRICE / MOQ
     * @param toDelist 全量替换时将下架的行数；增量上传恒为 0
     * @param problems 认不了的行（最多列 50 条）
     * @param delistSample 将下架的料号，最多 20 个 —— 让他一眼看出「这不对，表只传了半截」
     * @param status   PARSED 待确认 / APPLIED 已上架
     */
    public record BatchPreview(String batchNo, String fileName, String mode, boolean taxIncluded,
                               List<String> headers, Map<String, Integer> columns,
                               int rowTotal, int rowValid, int rowInvalid,
                               int toInsert, int toUpdate, int toDelist, int unchanged,
                               List<RowProblem> problems, List<String> delistSample, String status) {
    }

    /**
     * @param row    表里的行号（与 Excel 左边的行号一致）
     * @param reason MPN_MISSING 没有料号 / MPN_INVALID 不像料号 / QTY_INVALID 数量认不出 / DUPLICATE 与前面的行重复
     */
    public record RowProblem(int row, String reason, String mpn) {
    }

    /** 换列映射：字段 → 列序号。必须含 MPN 与 QTY */
    public record RemapReq(Map<String, Integer> columns) {
    }

    /** @param renewed 续期了多少行 */
    public record RenewResult(int renewed, LocalDate validUntil) {
    }
}
