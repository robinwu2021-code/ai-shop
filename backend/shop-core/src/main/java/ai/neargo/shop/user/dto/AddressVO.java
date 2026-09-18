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
                        String region,
                        String province,
                        String city,
                        String district,
                        String detail,
                        /** 门牌号（V319）。存量地址为空 —— 那时它还混在 detail 里 */
                        String houseNo,
                        boolean isDefault,
                        String tag,
                        /** ISO 3166-1 两位码。非 CN 时端上整段换形状（见实体上的说明） */
                        String countryCode,
                        /** 邮编。中国大陆为空 */
                        String postalCode,
                        /** 手机国家区号（不带 +） */
                        String phoneCc,
                        /** 坐标（gcj02，E6）。可能为 null —— 存量地址是纯手填的，没有坐标 */
                        Integer latE6,
                        Integer lngE6) {

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
                a.getRegion(), a.getProvince(), a.getCity(), a.getDistrict(), a.getDetail(),
                a.getHouseNo(),
                Boolean.TRUE.equals(a.getIsDefault()), a.getTag(),
                // **加了列要真的读出来**：只改写入的话这三列永远读回 null，
                // 界面上看不出区别（海外地址会被当成 CN 渲染），闸门也全绿
                a.getCountryCode(), a.getPostalCode(), a.getPhoneCc(),
                a.getLatE6(), a.getLngE6());
    }
}
