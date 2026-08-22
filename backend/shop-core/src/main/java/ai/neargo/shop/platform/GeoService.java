package ai.neargo.shop.platform;

/**
 * 逆地理编码（P2）：坐标 → 地址。后端代理地图厂商，密钥不落端。
 * 没配密钥时抛 {@code GEO_UNAVAILABLE}，端上据此藏掉「定位取地址」按钮。
 */
public interface GeoService {

    ReverseVO reverse(double lat, double lng);

    /** recommend 是带楼盘/门牌的人话版，address 是标准地址；端上填 recommend */
    record ReverseVO(String recommend, String address) {
    }
}
