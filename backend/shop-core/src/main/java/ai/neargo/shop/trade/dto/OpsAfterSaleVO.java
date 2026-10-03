package ai.neargo.shop.trade.dto;

import java.util.List;

/**
 * 售后单 · 平台仲裁视角。
 *
 * <p>与消费者端 {@link AfterSaleVO} 分开建型——不是重复：仲裁台要看「谁的店、谁买的」，
 * 消费者自己的售后单不需要商家名与自己的昵称。合并成一个类型的后果是要么消费者端多背两个
 * 用不上的字段，要么运营端永远拿不到这两个字段，此前就是后者。
 */
public record OpsAfterSaleVO(String afterSaleNo,
                             String subOrderNo,
                             String orderNo,
                             String merchantNo,
                             String merchantName,
                             String buyerNickname,
                             String type,
                             String status,
                             String reason,
                             List<String> images,
                             long refundMinor,
                             boolean instant,
                             String merchantRemark,
                             String expressNo,
                             String liability,
                             long createdAt,
                             List<AfterSaleVO.TimelineNode> timeline) {
}
