package ai.neargo.shop.product.dto;

import java.time.LocalDateTime;

/**
 * 商品的一个提交版本（TDD-商品编辑页-录入落点与发布历史 AC11）。
 *
 * <p>**故意没有差异字段。** AC12 要的两份差异（对比前一版 / 对比此刻线上）要比较
 * 两份 payload，而现有的差异计算比的是「线上实体 vs SaveCommand」，还依赖
 * {@code MerchantGoodsServiceImpl} 里的私有渲染器。把它们搬出来是另一次重构，
 * 不该和建表捆在同一个提交里。留一组恒空的字段比没有字段更糟 ——
 * 端上会照着它排版，然后永远显示空白。
 *
 * @param revisionNo    版本号
 * @param status        DRAFT 未发布 / ONLINE 线上在售 / SUPERSEDED 已被替换 / REJECTED 已驳回
 * @param entrySource   录入方式：MANUAL / QUICK_TEXT / ZIP / IMAGE
 * @param changeSummary 「改了哪几项」的摘要，给人看，不是结构化 diff
 * @param savedBy       保存人
 * @param savedAt       保存时间
 * @param publishedBy   发布人。与保存人常常不是同一个
 * @param publishedAt   发布时间
 * @param rejectReason  驳回原因
 */
public record GoodsRevisionVO(
        int revisionNo,
        String status,
        String entrySource,
        String changeSummary,
        String savedBy,
        LocalDateTime savedAt,
        String publishedBy,
        LocalDateTime publishedAt,
        String rejectReason) {
}
