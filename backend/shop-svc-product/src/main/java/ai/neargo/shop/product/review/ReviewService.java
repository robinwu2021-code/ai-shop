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
}
