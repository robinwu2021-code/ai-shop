package ai.neargo.shop.platform;

import ai.neargo.shop.spi.platform.GeoPort;

/**
 * 逆地理编码等地理能力的 core 侧入口；能力定义在 {@link GeoPort}（merchant 域也要用）。
 * 没配密钥时抛 {@code GEO_UNAVAILABLE}，端上据此藏掉「定位取地址」按钮。
 */
public interface GeoService extends GeoPort {

    /** 给端上的逆地理：自动把 wgs84 归一成 gcj02 再查；不可用抛 GEO_UNAVAILABLE */
    ReverseVO reverse(double lat, double lng, String coordSys);

    /** recommend 是带楼盘/门牌的人话版，address 是标准地址；端上填 recommend */
    record ReverseVO(String recommend, String address, String adcode, String township,
                     int latE6, int lngE6) {
    }
}
