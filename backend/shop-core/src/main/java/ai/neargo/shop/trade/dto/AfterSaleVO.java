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
                          /**
                           * 商家同意/驳回时的说明。
                           *
                           * <p><b>叫 merchantReply 而不是库里那个 merchant_remark</b>：
                           * 两个端的契约与文案都用前者（C 端订单页「商家回复：」直接读它）。
                           * 此前发的是列名，于是 <b>C 端那句「商家回复」永远不显示</b> ——
                           * `v-if` 拿到 undefined 就整块不渲染，不报错、看着像商家没回。
                           */
                          String merchantReply,
                          /** 用户寄回的运单号（RETURN_REFUND）。契约里叫 returnExpressNo */
                          String returnExpressNo,
                          /** 责任方，平台裁决后才有（P-6.1.4，口径未定） */
                          String liability,
                          long createdAt,
                          /**
                           * 最后一次状态变更时间。
                           *
                           * <p>此前只发 `createdAt`，而两个端的列表都按「最后动了什么时候」排/显示 ——
                           * B 端售后页每一行的时间因此是 `NaN-NaN-NaN NaN:NaN`
                           * （契约里声明了 updatedAt，后端根本没这个字段）。
                           * 超时自动同意之类的时效规则也以它为基准。
                           */
                          long updatedAt,
                          List<TimelineNode> timeline) {

    public record TimelineNode(String status, String label, long at) {
    }
}
