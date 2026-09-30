package ai.neargo.shop.elec.dto;

import ai.neargo.shop.elec.dto.RfqDtos.OpsSource;
import ai.neargo.shop.elec.dto.SupplierDtos.StockView;

import java.time.LocalDateTime;
import java.util.List;

/**
 * 运营端 · 供应商与料号库存。<b>内部面</b>：真名、电话、精确数量都在这里 ——
 * 买家那一侧的任何代码都不许引用本类。
 */
public final class OpsDtos {

    private OpsDtos() {
    }

    // ── 供应商 ──────────────────────────────────────────────────────────────

    /**
     * 供应商列表的一行。
     *
     * @param status        ACTIVE / SUSPENDED
     * @param onCount       在售且未到期的库存行数
     * @param expiringCount 其中 7 天内到期的
     * @param lastUploadAt  最近一次确认上架；从没传过为空
     */
    public record OpsSupplierRow(String supplierNo, String companyName, String kind, String city,
                                 String contactName, String contactPhone, String maskCode, String status,
                                 int onCount, int expiringCount, LocalDateTime lastUploadAt,
                                 LocalDateTime createdAt) {
    }

    /**
     * 供应商详情。
     *
     * @param suspendReason    最近一次暂停的理由。恢复后保留 —— 「上次为什么停过」是有用的
     * @param expiredCount     在售但已过期的行数（买家看不到，等他续期或重传）
     * @param registerNotified 入驻通知送到企业微信了没有
     * @param dispatch         近 30 天派单响应情况
     */
    public record OpsSupplierDetail(String supplierNo, String companyName, String kind, String city,
                                    String contactName, String contactPhone, String maskCode, String status,
                                    String suspendReason, LocalDateTime suspendedAt, int onCount,
                                    int expiringCount, int expiredCount, LocalDateTime lastUploadAt,
                                    boolean registerNotified, LocalDateTime createdAt, DispatchStats dispatch) {
    }

    /**
     * 派单响应。<b>分母是「看到的」不是「派出去的」</b>：没看到的不怪他。
     *
     * @param days      统计窗口（天）
     * @param sent      派了几条
     * @param viewed    其中看过的（含后来报价或拒绝的）
     * @param responded 其中回了话的（报价或拒绝）
     * @param quoted    其中报了价的
     * @param accepted  报价被买家选中的
     */
    public record DispatchStats(int days, int sent, int viewed, int responded, int quoted, int accepted) {
    }

    /** @param reason 暂停理由，至少 2 个字。只给平台看：供应商那边只收到「已暂停」 */
    public record SuspendReq(String reason) {
    }

    // ── 料号与库存 ──────────────────────────────────────────────────────────

    /**
     * 运营搜料号的一行。
     *
     * @param mfrNameRaw       厂牌认不出（mfrCode=UNKNOWN）时第一次上传写的原文
     * @param status           ACTIVE / PENDING / MERGED
     * @param match            EXACT / PREFIX / CONTAINS —— 与买家搜索同一套命中方式
     * @param supplierCnt      几家有在售且未到期的库存（<b>精确家数</b>，买家那边只有档位）
     * @param totalQty         合计数量（精确）
     * @param buyerPriceFromE6 买家看到的起价（投影表里的，已换算与加价）；没人报价为空
     */
    public record OpsPartRow(String partNo, String mpn, String mfrCode, String mfrName, String mfrNameRaw,
                             String pkg, String status, String match, int supplierCnt, long totalQty,
                             Long buyerPriceFromE6) {
    }

    /**
     * 料号详情：这是运营报价时最常看的一屏。
     *
     * @param qtyBand    买家看到的数量档
     * @param sourceBand 买家看到的家数档
     * @param sources    谁有货：按数量倒序，最多 50 家
     */
    public record OpsPartDetail(OpsPartRow part, String description, String qtyBand, String sourceBand,
                                List<OpsSource> sources) {
    }

    /** 库存行查询的一行：库存本身 + 它属于哪家、挂在哪个料号上 */
    public record OpsStockRow(String supplierNo, String companyName, String supplierStatus, String partNo,
                              StockView stock) {
    }

    // ── 表头别名 ────────────────────────────────────────────────────────────

    /**
     * 表头别名的一行。
     *
     * @param id            全局别名的 id（改字段、停用用它）；学到的别名按写法聚合，没有 id
     * @param source        SEED / OPS / LEARNED
     * @param supplierCount 学到的别名：几家在用（据此决定要不要提升为全局）；全局的为 0
     */
    public record HeaderAliasRow(Long id, String aliasNorm, String aliasRaw, String field, String source,
                                 String status, int supplierCount, LocalDateTime updatedAt) {
    }

    /** 加一条全局别名，或把学到的提升为全局。同一写法已有全局别名时改成这个字段并启用 */
    public record HeaderAliasReq(String alias, String field) {
    }

    /** @param status ACTIVE / DISABLED */
    public record HeaderAliasUpdate(String field, String status) {
    }
}
