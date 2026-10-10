package ai.neargo.shop.product.service;

import ai.neargo.shop.product.dto.GoodsRevisionVO;

import java.util.List;

/**
 * 商品提交历史（TDD-商品编辑页-录入落点与发布历史 §5）。
 *
 * <p><b>为什么单独一个服务</b>：写入点散落在保存 / 发布 / 驳回三条路上，
 * 而它们都在 {@code MerchantGoodsServiceImpl} 那 2900 行里。把「记一笔」
 * 收成三个方法，调用点就只剩一行，不会在某条分支上漏掉 —— 而漏掉的后果
 * 是静默的：历史少一版，没人会报错。
 */
public interface GoodsRevisionService {

    /**
     * 记一次保存。**同一商品至多一行未发布**（与 {@code prd_goods_draft} 同语义）：
     * 已有 DRAFT 行就更新它，不插新行。连续存草稿十次只占一行 ——
     * 否则边输边识别加上自动存草稿，一个商品能刷出几百行，而商家只想看「待发布那一版」。
     *
     * @param entrySource 录入方式，见 {@code PrdGoodsRevision.SRC_*}
     */
    void recordSave(String goodsNo, String entityNo, String payload,
                    String changeSummary, String entrySource);

    /**
     * 记一次发布：把未发布那一行翻 ONLINE，把上一个 ONLINE 翻 SUPERSEDED。
     *
     * <p>没有未发布行时**什么都不做**（不补一行）—— 那说明这次发布没经过保存
     * （免审直通里的某些路径、历史数据），补一行等于凭空造一份看不出来源的快照。
     */
    void recordPublished(String goodsNo, String publishedBy);

    /** 记一次驳回：未发布那一行翻 REJECTED 并落原因 */
    void recordRejected(String goodsNo, String reason);

    /** 某商品的全部版本，新的在前。两份差异不算（列表不需要，算一遍要读两份 payload） */
    List<GoodsRevisionVO> list(String merchantNo, String goodsNo);

    /**
     * 某一版的详情：带<b>两份</b>差异。
     *
     * <p>两份回答的是两个不同的问题：{@code changesFromPrev}「这一版当年干了什么」
     * 用于追溯，{@code changesVsOnline}「它跟现在差多少」用于决定要不要取回。
     * 只给一份的话，商家没法判断取回会动到哪些东西。
     */
    GoodsRevisionVO detail(String merchantNo, String goodsNo, int revisionNo);

    /**
     * 以某一版建草稿（AC12）。
     *
     * <p><b>不直接改线上</b> —— 回滚也是一次发布，要走同一道差异确认。
     * 所以这里只把那一版的 payload 原样写成一份新的未发布版本，
     * 发布那一步照旧。线上在售那一版取回毫无意义，拒。
     *
     * @return 新建出来的那一版
     */
    GoodsRevisionVO fork(String merchantNo, String goodsNo, int revisionNo);

    /**
     * 一版的快照（AC13 算「会被覆盖掉什么」用）。不走权限 —— 调用方是服务内部。
     *
     * @param payload 整份 SaveCommand 的 JSON
     */
    record Snapshot(int revisionNo, String payload, String publishedBy) {
    }

    /** 此刻线上在售那一版的快照。没发布过回 null */
    Snapshot onlineSnapshot(String goodsNo);

    /**
     * 未发布那一版**所基于的**那一版的快照。
     *
     * <p>用它而不是 {@code prd_goods_draft.base_version}：后者是
     * {@code prd_goods.version}（乐观锁列），不是版本号，查不到对应的快照。
     * 未发布那一行的 {@code base_revision} 才是「我存草稿时线上是哪一版」。
     */
    Snapshot pendingBaseSnapshot(String goodsNo);
}
