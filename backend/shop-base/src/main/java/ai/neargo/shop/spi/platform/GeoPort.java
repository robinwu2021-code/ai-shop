package ai.neargo.shop.spi.platform;

import java.util.List;
import java.util.Optional;

/**
 * 地理能力（高德 Web 服务，后端代理，密钥不落端）。merchant / community 域通过它做
 * 地址校验、坐标归一与逆地理，不直接认识厂商。
 *
 * <p><b>全站坐标一律 gcj02</b>。App 端系统定位给的是 wgs84，调用方带 {@code coordSys}，
 * 由 {@link #toGcj02} 归一；没配密钥时 {@link #available()} 为 false，调用方<b>跳过校验而不是拒</b>——
 * 地图能力是增强，不是开店的前置。
 */
public interface GeoPort {

    String WGS84 = "wgs84";
    String GCJ02 = "gcj02";

    boolean available();

    /** wgs84 → gcj02；已是 gcj02 或不可用时原样返回 */
    int[] toGcj02(int latE6, int lngE6, String coordSys);

    /** 坐标 → 地址。不可用时 empty */
    Optional<Reverse> reverse(int latE6, int lngE6);

    /** 地址 → 坐标 + 标准化。不可用时 empty；解析不到门牌/兴趣点级时 {@code ok=false} */
    Optional<Geocode> geocode(String address, String city);

    /** 输入提示（小区/村/门牌）。不可用或关键词太短时空表 */
    List<Tip> tips(String keyword, String city);

    /**
     * 周边搜索：以一个点为圆心、关键词过滤，找附近的地点。
     *
     * <p>与 {@link #tips} 的区别：tips 是「输入联想」（人打字时用，city 只是偏好），
     * around 是「这一片有什么」（选择器逐级点下去，看某个社区/村底下有哪些小区用）——
     * 后者要真按坐标圈范围，不能退化成全国关键词搜。
     */
    /** @param types 高德 POI 类目过滤（可空 = 不限类目）。农村自然村数据比城区住宅小区稀疏，
     *                限死「住宅区/住宅小区」类目会把它们全滤掉，所以农村那条路径要传空 */
    List<Tip> around(String keyword, int latE6, int lngE6, int radiusM, String types);

    /**
     * @param recommend 带楼盘/门牌的人话版（端上填这个）
     * @param adcode    国标 6 位区县码，与 sys_region 同口径
     * @param township  街道/镇名（自动归属用）
     */
    record Reverse(String recommend, String address, String adcode, String township,
                   String city, int latE6, int lngE6) {
    }

    record Geocode(boolean ok, String level, String formatted, int latE6, int lngE6, String adcode) {
    }

    record Tip(String name, String address, String adcode, Integer latE6, Integer lngE6, String typecode) {
    }
}
