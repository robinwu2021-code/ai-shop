package ai.neargo.shop.user.dto;

/** 收藏门店卡片。merchantNo 保留经营主体标识；店名、评分与跳转取实际门店。 */
public record StoreBriefVO(String merchantNo, String name, String logo,
                           double rating, boolean verified, int breachCount, String storeNo) {
}
