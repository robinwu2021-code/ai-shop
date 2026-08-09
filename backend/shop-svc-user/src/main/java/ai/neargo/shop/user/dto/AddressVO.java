package ai.neargo.shop.user.dto;

import ai.neargo.shop.user.entity.UsrAddress;

/**
 * 收货地址（对齐 c-app {@code Address}）。
 *
 * <p><b>两个视角两个工厂方法</b>（越权防线 ④ 的雏形）：属主看完整手机号（下单要核对），
 * 履约方看脱敏号（自提点承接方不该拿到用户完整联系方式，M11/B12）。
 * 用两个方法而不是一个带布尔参数的方法 —— 布尔参数传错不会报错，只会静默泄漏。
 */
public record AddressVO(String addressId,
                        String name,
                        String phone,
                        String province,
                        String city,
                        String district,
                        String detail,
                        boolean isDefault,
                        String tag) {

    /** 属主视角：完整手机号。 */
    public static AddressVO forOwner(UsrAddress a) {
        return build(a, a.getPhone());
    }

    /** 履约视角：仅后四位。 */
    public static AddressVO forFulfillment(UsrAddress a) {
        // 与结算账号同一口径（Masks.tail）：两处不一致会让人以为其中一处泄了更多
        return build(a, ai.neargo.shop.common.Masks.tail(a.getPhone()));
    }

    private static AddressVO build(UsrAddress a, String phone) {
        return new AddressVO(a.getAddressId(), a.getName(), phone,
                a.getProvince(), a.getCity(), a.getDistrict(), a.getDetail(),
                Boolean.TRUE.equals(a.getIsDefault()), a.getTag());
    }
}
