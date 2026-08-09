package ai.neargo.shop.trade.dto;

import java.util.List;

/** 售后单（对齐契约 AfterSale）。 */
public record AfterSaleVO(String afterSaleNo,
                          String subOrderNo,
                          String orderNo,
                          String type,
                          String status,
                          String reason,
                          List<String> images,
                          long refundMinor,
                          /** 极速退：命中阈值自动通过，商家只可见不可拒 */
                          boolean instant,
                          String merchantRemark,
                          String expressNo,
                          /** 责任方，平台裁决后才有（P-6.1.4，口径未定） */
                          String liability,
                          long createdAt,
                          List<TimelineNode> timeline) {

    public record TimelineNode(String status, String label, long at) {
    }
}
