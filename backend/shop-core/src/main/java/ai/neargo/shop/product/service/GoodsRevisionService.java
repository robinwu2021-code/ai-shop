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

    /**
     * 某商品的全部版本，新的在前。
     *
     * <p>AC12 的「某一版详情 + 两份差异」与 AC13 的 overwrites **不在这一批** ——
     * 见 {@link GoodsRevisionVO} 的类注释。
     */
    List<GoodsRevisionVO> list(String merchantNo, String goodsNo);
}
