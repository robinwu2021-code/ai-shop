package ai.neargo.shop.merchant.service;

/**
 * 门店发货设置（TDD-快递100商家寄件 §7 AC12）：寄件人、电话、寄件地址、默认快递公司、默认重量。
 *
 * <p>全部可空，空 = 回落门店名 / 店主登录手机 / 门店地址。回落值一并下发（{@code default*}），
 * 端上拿它当 placeholder —— 商家一眼看到「不填会用什么」。
 */
public interface StoreShipSettingService {

    ShipSettingVO get(String entityNo, String storeNo);

    ShipSettingVO save(String entityNo, String storeNo, ShipSettingCmd cmd);

    /** 空串或 null 都表示「用默认」 */
    record ShipSettingCmd(String senderName, String senderPhone, String address, String carrier, Integer weightG) {
    }

    /**
     * @param carrier 微信 delivery_id；@param weightG 克
     */
    record ShipSettingVO(String storeNo, String senderName, String senderPhone, String address, String carrier,
                         Integer weightG, String defaultSenderName, String defaultSenderPhone,
                         String defaultAddress) {
    }
}
