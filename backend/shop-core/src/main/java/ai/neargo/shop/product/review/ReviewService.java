package ai.neargo.shop.product.review;

import ai.neargo.shop.product.review.dto.ReviewVO;

import java.util.List;

/** 评价（C-RV-01~03）。商家侧的回复与申诉在 B 端，走另一组方法。 */
public interface ReviewService {

    /**
     * 评价列表。{@code goodsNo} 与 {@code merchantNo} **二选一**，都不传直接拒绝 ——
     * 无条件全表返回评价没有任何使用场景，只会变成一次慢查询。
     */
    List<ReviewVO> list(String goodsNo, String merchantNo);

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
     * @param status        PENDING / UPHELD（支持商家，差评下架）/ DISMISSED（驳回申诉，差评保留）
     * @param evidenceCount 举证材料数量
     */
    record OpsAppealVO(String appealNo, String reviewNo, String merchantNo, String merchantName,
                       String reason, int evidenceCount, String status, long submittedAt,
                       String verdict) {
    }
}
