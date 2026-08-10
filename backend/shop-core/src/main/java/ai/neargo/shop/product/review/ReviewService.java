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
}
