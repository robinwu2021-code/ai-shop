package ai.neargo.shop.product.dto;

import java.time.LocalDateTime;
import java.util.List;

/**
 * 商品的一个提交版本（TDD-商品编辑页-录入落点与发布历史 AC11）。
 *
 * <p>列表与详情同一个形状：**列表里两份差异为 null，详情才算**。为 null 而不是空列表 ——
 * 「这一版没有改动」与「这次没算差异」是两件事，端上据此决定画不画那一段。
 *
 * <p>差异一律用现成的 {@link PublishPreviewVO.DiffRow}：同一种信息两种形状的话，
 * 端上要排两套版、商家要学两遍。算法在 {@link GoodsDiffs#between}。
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
 * @param changesFromPrev 这一版当年改了什么（对比它的基版）。**仅详情**，列表里为 null
 * @param changesVsOnline 它跟此刻线上差多少（决定要不要取回）。**仅详情**，列表里为 null
 * @param canFork         能不能以这一版建草稿。线上在售那一版取回毫无意义，所以它为 false
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
        String rejectReason,
        List<PublishPreviewVO.DiffRow> changesFromPrev,
        List<PublishPreviewVO.DiffRow> changesVsOnline,
        boolean canFork) {

    /** 列表用：两份差异与 canFork 都不算 */
    public GoodsRevisionVO withoutDiffs() {
        return new GoodsRevisionVO(revisionNo, status, entrySource, changeSummary,
                savedBy, savedAt, publishedBy, publishedAt, rejectReason, null, null, false);
    }
}
