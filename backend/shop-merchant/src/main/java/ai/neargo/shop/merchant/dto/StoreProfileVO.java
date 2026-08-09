package ai.neargo.shop.merchant.dto;

import java.util.List;

/**
 * 店铺资料（对齐 b-app {@code StoreProfile}）。
 *
 * <p>它<b>横跨两张表</b>：门面（公告/营业时间/地址/主推）在 {@code mch_store}，
 * 经营范围在 {@code mch_entity} 与 {@code mch_entity_community}。
 * 前端看到的是一份资料，库里分开存的理由见 V30 迁移的注释。
 *
 * @param serviceScope        COMMUNITY / CITY / PLATFORM（ADR-009）。
 *                            <b>决定这家店的货在 C 端能被谁看到</b>
 * @param serviceCommunityNos scope=COMMUNITY 时的覆盖社区。<b>空 = 对谁都不可见</b>，
 *                            而且没有任何报错 —— 所以保存时要拦，不能等商家发现没订单
 */
public record StoreProfileVO(String announcement, String openHours, String address,
                             List<String> featured, String serviceScope,
                             List<String> serviceCommunityNos, String serviceCityCode) {
}
