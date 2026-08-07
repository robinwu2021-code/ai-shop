package ai.neargo.shop.product.review.dto;

import java.util.List;

/**
 * 评价（契约 {@code Review}）。
 *
 * @param nickname  下单时的快照，不随用户改昵称变动 —— 历史评价的署名不该被追溯修改
 * @param spec      购买规格快照：让读评价的人知道这条说的是哪个 SKU
 * @param liked     **当前用户**是否点过赞。未登录恒为 false
 * @param scores    三维分。老评价没有维度分，此时为 null —— 列表页只显示总分
 * @param appeal    商家申诉。C 端不展示，保留是因为契约里有；平台裁决台要用
 */
public record ReviewVO(String reviewNo,
                       String goodsNo,
                       String merchantNo,
                       String nickname,
                       String avatar,
                       int rating,
                       String content,
                       List<String> images,
                       String spec,
                       long createdAt,
                       int likeCount,
                       boolean liked,
                       String reply,
                       Scores scores,
                       Appeal appeal) {

    /** 三维度：商品本身 / 履约（快慢、包装、缺损）/ 服务（沟通、售后态度） */
    public record Scores(int goods, int fulfillment, int service) {
    }

    public record Appeal(String appealNo, String reason, String status, String verdict) {
    }
}
