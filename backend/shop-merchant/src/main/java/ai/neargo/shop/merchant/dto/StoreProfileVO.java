package ai.neargo.shop.merchant.dto;

import java.util.List;

/**
 * 店铺资料（对齐 b-app {@code StoreProfile}）。
 *
 * <p>它<b>横跨两张表</b>：门面（公告/营业时间/地址/主推）在 {@code mch_store}，
 * 经营范围在 {@code mch_entity} 与 {@code mch_entity_community}。
 * 前端看到的是一份资料，库里分开存的理由见 V30 迁移的注释。
 *
 * @param serviceScope        @deprecated 三档枚举（ADR-009）。ADR-013 阶段二起由
 *                            {@code fulfillmentReach} × {@code serviceAreas} 取代，
 *                            这里只为存量端上不炸而保留一版
 * @param serviceCommunityNos @deprecated 同上。新模型里社区只是覆盖项的一种
 * @param fulfillmentReach    PICKUP 靠自提点 / ONSITE 上门或同城 / SHIPPING 快递无半径。
 *                            <b>只说「怎么送到你手上」</b>
 * @param serviceAreas        地理覆盖项，可跨粒度组合（三个小区 + 一个区）。
 *                            <b>空的含义由 fulfillmentReach 决定</b>：PICKUP 空 = 谁也看不到，
 *                            ONSITE/SHIPPING 空 = 不限（ADR-013 §6.2）
 * @param latE6               门店坐标（gcj02，E6），端上地图选点回填；没标过为 null
 */
public record StoreProfileVO(String announcement, String openHours, String address,
                             List<String> featured, String serviceScope,
                             List<String> serviceCommunityNos, String serviceCityCode,
                             String fulfillmentReach, List<ServiceAreaVO> serviceAreas,
                             Integer latE6, Integer lngE6) {

    /**
     * 一条覆盖项。
     *
     * @param level   COMMUNITY / STREET / DISTRICT / CITY
     * @param refCode level=COMMUNITY 时是社区号，否则是区划码
     * @param name    展示名。<b>后端拼好给</b> —— 端上只拿到 330106 的话，
     *                要么显示一串数字，要么自己再查一次
     * @param status  {@code ACTIVE} 已生效 / {@code PENDING} 待运营审核。
     *                <b>必须下发</b>：区、街道级的覆盖要审（ADR-013 §4.2），待审的不参与展开 ——
     *                不告诉端上的话，商家保存完看见它好端端在清单里，
     *                却一个订单也不来，而这是他自己永远查不出来的
     */
    /** @param areaNo 业务键（P2 范围子集按它引用；审核单也靠它指回） */
    public record ServiceAreaVO(String level, String refCode, String name, String status, String areaNo) {
    }
}
