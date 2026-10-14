package ai.neargo.shop.platform;

import ai.neargo.shop.spi.platform.GeoPort;

import java.util.List;
import java.util.Optional;

/**
 * 逆地理编码等地理能力的 core 侧入口。
 *
 * <p><b>不再 {@code extends GeoPort}</b>（2026-08-30）。此前继承的后果是
 * {@code GeoServiceImpl} 同时是本域 Service 的实现和跨域 Port 的实现，
 * 于是它同时落在两条架构规则里：{@code *ServiceImpl} 要在 {@code ..impl..}，
 * Port 实现要在 {@code ..port..} —— <b>两条规则互相拧，怎么放都红</b>，
 * 而这正是「一个类服务两拨受众」的症状，不是规则的毛病。
 *
 * <p>现在的分工：本接口给 platform / portal 自己用（多一条给端上的
 * {@link #reverse(double, double, String)}）；跨域的 merchant / community 走
 * {@link GeoPort}，实现是 {@code platform.port.GeoPortImpl} 的薄转发。
 * 两份签名看着重复，但它们的受众本来就不同 —— 以后要给端上加能力
 * 不必连带扩大跨域契约。
 *
 * <p>没配密钥时抛 {@code GEO_UNAVAILABLE}，端上据此藏掉「定位取地址」按钮。
 */
public interface GeoService {

    /** 配了密钥、能用。false 时调用方**跳过校验而不是拒** —— 地图能力是增强 */
    boolean available();

    /** wgs84 → gcj02；已是 gcj02 或不可用时原样返回 */
    int[] toGcj02(int latE6, int lngE6, String coordSys);

    /** 坐标 → 地址。不可用时 empty */
    Optional<GeoPort.Reverse> reverse(int latE6, int lngE6);

    /** 地址 → 坐标 + 标准化。不可用时 empty；解析不到门牌/兴趣点级时 {@code ok=false} */
    Optional<GeoPort.Geocode> geocode(String address, String city);

    /** 输入提示（小区/村/门牌）。不可用或关键词太短时空表 */
    List<GeoPort.Tip> tips(String keyword, String city);

    /** 周边搜索：以一个点为圆心按关键词找附近的地点。与 {@link #tips} 的区别见 {@link GeoPort#around} */
    List<GeoPort.Tip> around(String keyword, int latE6, int lngE6, int radiusM, String types);

    /** 给端上的逆地理：自动把 wgs84 归一成 gcj02 再查；不可用抛 GEO_UNAVAILABLE */
    ReverseVO reverse(double lat, double lng, String coordSys);

    /** recommend 是带楼盘/门牌的人话版，address 是标准地址；端上填 recommend */
    record ReverseVO(String recommend, String address, String adcode, String township,
                     int latE6, int lngE6) {
    }
}
