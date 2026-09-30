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
     * 上传预览。<b>预览时一行库存都没动</b>，确认之后才上架；待确认的数据只在服务器内存里，从上传起最多
     * {@code deadline} 为止。
     *
     * @param headers       表头那一行（原样），端上用它画「这几列分别是什么」
     * @param headerRow     表头在第几行（从 0 起）；一列都没认出时为 -1
     * @param columns       字段 → 列序号（从 0 起）
     * @param columnSource  字段 → REMEMBERED 记住的 / ALIAS 别名表 / AI 大模型 / MANUAL 手工。AI 的端上要提示核对
     * @param rowWarn       有警告的行数（照常上架）
     * @param toDelist      全量替换时将下架的行数；增量上传恒为 0
     * @param issueCounts   问题码 → 处数
     * @param issues        前 100 处问题（定位到格）；全部走 rows?view=PROBLEM
     * @param problems      <b>过渡字段</b>：老版本小程序读它（行号 + 原因 + 料号，只有错误级）。新端上读 issues
     * @param delistSample  将下架的料号，最多 20 个 —— 让他一眼看出「这不对，表只传了半截」
     * @param delistConfirm true = 确认时必须带上此刻的下架数（过了下架护栏的线）
     * @param status        NEED_MAPPING 待指定列 / PARSED 待确认 / APPLIED / CANCELLED / SUPERSEDED / FAILED / EXPIRED
     * @param deadline      待确认的截止时刻；过了要重传
     */
    public record BatchPreview(String batchNo, String fileName, String mode, boolean taxIncluded,
                               List<String> headers, int headerRow, Map<String, Integer> columns,
                               Map<String, String> columnSource,
                               int rowTotal, int rowValid, int rowInvalid, int rowWarn,
                               int toInsert, int toUpdate, int toDelist, int unchanged,
                               Map<String, Integer> issueCounts, List<Issue> issues, List<RowProblem> problems,
                               List<String> delistSample, boolean delistConfirm, String status,
                               LocalDateTime deadline, LocalDateTime createdAt, LocalDateTime appliedAt) {
    }

    /**
     * 一处问题，定位到格。
     *
     * @param row    表里的行号（与 Excel 左边的行号一致）
     * @param col    列序号（从 0 起，端上显示成字母）；-1 = 整行的问题（如与前面的行重复）
     * @param header 那一列的表头原文
     * @param value  那一格的原值（截 64 字符）；DUPLICATE 时是「与第 N 行重复」的 N
     * @param code   MPN_MISSING / MPN_INVALID / QTY_INVALID / DUPLICATE（ERROR）·
     *               MFR_MISSING / MFR_UNKNOWN / QTY_ZERO / DC_UNPARSED（WARN）
     * @param level  ERROR 这一行不上架 / WARN 照常上架
     */
    public record Issue(int row, int col, String header, String value, String code, String level) {
    }

    /**
     * <b>过渡</b>：老版本小程序的问题行。新端上读 {@link Issue}。
     *
     * @param row    表里的行号（与 Excel 左边的行号一致）
     * @param reason MPN_MISSING 没有料号 / MPN_INVALID 不像料号 / QTY_INVALID 数量认不出 / DUPLICATE 与前面的行重复
     */
    public record RowProblem(int row, String reason, String mpn) {
    }

    /**
     * 预览里的一行：<b>解析之后</b>平台读到的值，让他核对「平台是不是这么理解我的表」。
     *
     * @param kind   INSERT 新增 / UPDATE 更新 / UNCHANGED 未变 / DELIST 将下架 / PROBLEM 有错误（不上架）
     * @param before 更新的行：变了的那几个字段的旧值；其余情况为空
     * @param issues 这一行的问题（警告也在这里）
     */
    public record PreviewRow(int row, String kind, String mpn, String mfr, Long qty, String dateCode,
                             String packageName, Integer moq, Integer spq, List<PriceTier> tiers, String currency,
                             String packing, String cond, Integer leadDays, String region,
                             Map<String, Object> before, List<Issue> issues) {
    }

    /** 上传记录的一行 */
    public record BatchSummary(String batchNo, String fileName, String mode, String status, String failCode,
                               int rowTotal, int rowValid, int rowInvalid, int rowWarn,
                               int toInsert, int toUpdate, int toDelist, int unchanged, boolean aiUsed,
                               boolean fileAvailable, LocalDateTime createdAt, LocalDateTime appliedAt) {
    }

    /** @param expectDelist 此刻将下架的行数；过了下架护栏的线时必须带，且要与后端重算的一致 */
    public record ApplyReq(Integer expectDelist) {
    }

    /** 换列映射：字段 → 列序号。必须含 MPN 与 QTY */
    public record RemapReq(Map<String, Integer> columns) {
    }

    /** @param renewed 续期了多少行 */
    public record RenewResult(int renewed, LocalDate validUntil) {
    }
}
