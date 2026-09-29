package ai.neargo.shop.product.review;

import ai.neargo.shop.product.review.dto.ReviewVO;

import java.util.List;

/** 评价（C-RV-01~03）。商家侧的回复与申诉在 B 端，走另一组方法。 */
public interface ReviewService {

    /**
     * 评价列表。{@code goodsNo} 与 {@code merchantNo} **二选一**，都不传直接拒绝 ——
     * 无条件全表返回评价没有任何使用场景，只会变成一次慢查询。
     */
    /**
     * 评价列表。
     *
     * <p><b>加了筛选与分页</b>（§3.3）：此前它返回**全部** VISIBLE 行且没有上限 ——
     * 现在线上评价为 0 所以看不出来，有单量之后这是一次整表下发。
     *
     * @param filter {@link #FILTER_ALL} / {@link #FILTER_IMAGE}（有图）/
     *               {@link #FILTER_GOOD}（好评 4~5 星）/ {@link #FILTER_BAD}（差评 1~2 星）。
     *               空或不认识的值按全部处理 —— 筛选是便利，不该因为传错一个词就报错
     * @param page   从 1 开始
     * @param size   每页条数，上限 {@link #MAX_PAGE_SIZE}
     */
    List<ReviewVO> list(String goodsNo, String merchantNo, String filter, int page, int size);

    /** @param storeNo 只看这家门店的评价；空 = 不按门店筛 */
    List<ReviewVO> list(String goodsNo, String merchantNo, String storeNo, String filter, int page, int size);

    /** 旧签名：全部、第一页。留着是因为它在别处还有调用方 */
    default List<ReviewVO> list(String goodsNo, String merchantNo) {
        return list(goodsNo, merchantNo, FILTER_ALL, 1, MAX_PAGE_SIZE);
    }

    /**
     * 评分概览：平均分、星级分布、有图条数、三个维度各自的平均分。
     *
     * <p><b>与列表分开</b>：列表是分页的，而概览说的是整体 —— 从当前这一页算平均分，
     * 翻页时数字会变，那是一个看起来很正常、却每一页都不一样的「总分」。
     */
    ReviewSummaryVO summary(String goodsNo, String merchantNo);

    String FILTER_ALL = "ALL";
    String FILTER_IMAGE = "IMAGE";
    String FILTER_GOOD = "GOOD";
    String FILTER_BAD = "BAD";
    int MAX_PAGE_SIZE = 50;

    /**
     * @param total        可见评价总数
     * @param avg          平均分（保留一位小数，例如 4.6）
     * @param dist         1~5 星各自的条数，**下标 0 是 1 星**
     * @param withImages   有图的条数
     * @param avgGoods     商品分；没人打过这一维时为 0
     * @param avgFulfillment 履约分
     * @param avgService   服务分
     */
    record ReviewSummaryVO(int total, double avg, List<Integer> dist, int withImages,
                           double avgGoods, double avgFulfillment, double avgService) {
    }

    /** 发表评价。要求订单已完成且未评价过 —— 两条都由库唯一键 + 服务端校验双重挡住。 */
    ReviewVO create(CreateCommand cmd);

    /** 点赞/取消点赞。同一用户对同一条评价幂等切换。 */
    ReviewVO toggleLike(String reviewNo);

    record CreateCommand(String orderNo, String goodsNo, int rating,
                         String content, List<String> images, Scores scores) {
    }

    record Scores(Integer goods, Integer fulfillment, Integer service) {
    }

    // ---------------------------------------------------------------- 商家侧（B-11.7）

    /** 待商家回复的评价数（工作台待办）。 */
    int pendingReplyCount(String merchantNo);

    /**
     * 回复评价。<b>一条评价只能回一次</b> —— 回复是公开的对外表态，
     * 允许反复改会变成商家和买家在评论区来回改口。要补充说明走客服。
     */
    ReviewVO reply(String merchantNo, String reviewNo, String reply);

    /**
     * 申诉差评（B-9.4）。
     *
     * <p><b>只有低分评价可申诉</b> —— 四星五星去申诉没有意义，开放了只会变成
     * 「凡是不满意的评价都申诉一遍」，把平台裁决台淹掉。
     *
     * <p>一条评价只能申诉一次，由 {@code uk_review} 在库上兜底 ——
     * 先查后插必然有竞态，而重复申诉会在裁决台上变成两条互相矛盾的待办。
     */
    ReviewVO appeal(String merchantNo, String reviewNo, String reason, List<String> images);

    // ---------------------------------------------------------------- 平台治理（P-13.1）

    /** 平台侧评价列表。{@code status} 为空时给全部（含已驳回的 —— 治理要看得到自己驳过什么）。 */
    List<OpsReviewVO> opsList(String status, String merchantNo, String keyword);

    /**
     * 评价审核。{@code pass=false} 时**必须写理由** —— 与门店审核同一条规矩：
     * 驳回不写理由，被驳的人无从改起，只会反复提交同一份。
     */
    OpsReviewVO decide(String reviewNo, boolean pass, String reason, String operatorNo);

    /** 待裁决的差评申诉。 */
    List<OpsAppealVO> appeals(String status);

    /**
     * 裁决申诉。<b>无论支持还是驳回都必须写裁决说明</b> —— 商家会看到它。
     *
     * <p>{@code uphold=true} 支持商家：把评价置为 REJECTED，它从 C 端消失；
     * {@code false} 驳回申诉：评价保留。两种结果都是终态，不能再裁一次。
     */
    OpsAppealVO decideAppeal(String appealNo, boolean uphold, String verdict, String operatorNo);

    /**
     * @param riskFlags 刷评线索。**是线索不是结论** —— 命中不等于判定，给人审用
     * @param imageCount 配图数量。列表页不下发图本身，点进详情才取
     */
    record OpsReviewVO(String reviewNo, String orderNo, String merchantNo, String merchantName,
                       String authorNickname, int score, int scoreProduct, int scoreFulfill,
                       int scoreService, String content, int imageCount, String status,
                       List<String> riskFlags, long createdAt, String reason) {
    }

    /**
     * @param status        PENDING / UPHELD（支持商家，差评下架）/ REJECTED（驳回申诉，差评保留）
     * @param evidenceCount 举证材料数量
     */
    /**
     * @param reviewRating  被申诉那条评价的星级
     * @param reviewContent 被申诉那条评价的正文
     */
    record OpsAppealVO(String appealNo, String reviewNo, String merchantNo, String merchantName,
                       int reviewRating, String reviewContent,
                       String reason, int evidenceCount, String status, long submittedAt,
                       String verdict) {
    }
}
