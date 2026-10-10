package ai.neargo.shop.merchant.entity;

import ai.neargo.shop.common.BaseEntity;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Getter;
import lombok.Setter;

/**
 * 商家的一条地理覆盖项（ADR-013 阶段二）。
 *
 * <p>一家店可以同时勾三个小区加一个区 —— 这正是三档枚举做不到、而本表存在的理由。
 *
 * <p><b>本表走物理删除，不留墓碑。</b> 逻辑删 + 业务唯一键这个组合在本仓库已经
 * 踩过四次（门店角色、商品社区池、商家社区表各修了一个 revive）：删掉的行还占着
 * 唯一索引位，「移除之后又加回同一条」直接撞键，商家看到的是「系统开小差了」。
 * 而这是纯关联集合，没有历史价值 —— 谁在什么时候框过哪个区由审计日志回答。
 * 所以从根上消掉那类 bug，而不是打第五个补丁。
 */
@Getter
@Setter
@TableName("mch_service_area")
public class MchServiceArea extends BaseEntity {

    /** 业务键。审核单靠它指回本行 —— 自增 id 不对外，重建库就变 */
    private String areaNo;

    private String entityNo;

    /**
     * 这条范围属于**哪家门店**（V381）。经营范围是门店级的：每家店各有各的范围，
     * 可见性按这家店自己的范围算 —— 不是全主体共用一份。
     *
     * <p>空 = 迁移前写下、而主体当时没有任何门店的孤行，不参与任何门店的可见性。
     */
    private String storeNo;

    /**
     * 粒度：COMMUNITY 社区/楼栋 · VILLAGE 村/居委会 · STREET 街道 · DISTRICT 区县 · CITY 城市 · PROVINCE 省
     * · POLYGON 地图多边形 · UNLIMITED 全平台不限（ADR-034）。
     *
     * <p>行政五级按国标码前缀匹配（省 2 / 市 4 / 区县 6 / 街道 9 / 村居 12 位）；
     * POLYGON 经 S2 网格派生表 {@code mch_service_area_cell} 命中、边界 cell 再用 {@link #geometry} 精判；
     * UNLIMITED 只对快递/自送路生效，自提没有落点。
     */
    private String level;

    /**
     * {@code COMMUNITY} 时是 {@code community_no}；行政级是 {@code region_code}；
     * {@code POLYGON} 是几何指纹（规范化顶点 JSON 的 SHA-256 前 32 位——几何不变则项不变、area_no 得以沿用）；
     * {@code UNLIMITED} 恒为 {@link #UNLIMITED_REF}。
     */
    private String refCode;

    /** SELF 商家自选 / OPS 运营指定 */
    private String source;

    /** ACTIVE 已生效 / PENDING 待审（勾区、市要审 —— 影响面差一个量级） */
    private String status;

    /**
     * 覆盖方向：{@code INCLUDE} 纳入（默认）/ {@code EXCLUDE} 排除。
     *
     * <p>它回答的是「商家框了小区，算不算覆盖里面每栋楼」—— **默认算**，
     * 但给一个显式的出口：勾了整个小区、单独排除 3 幢。
     *
     * <p>展开时**先并后减**，EXCLUDE 优先于 INCLUDE。
     * 而更好的做法是在输入端就不让矛盾发生（B 端勾了排除就把对应的 include 去掉）——
     * 从输入端消除比从判定端消除诚实，后者要求用户记住一条规则。
     */
    private String mode;

    /**
     * {@code level=POLYGON} 时的顶点 JSON：{@code [[lngE6,latE6],...]}，规范化、首尾不重复（V396）。
     * 其余 level 为 null。网格派生表可由它全量重建。
     */
    private String geometry;

    public static final String MODE_INCLUDE = "INCLUDE";
    public static final String MODE_EXCLUDE = "EXCLUDE";

    public static final String ACTIVE = "ACTIVE";
    public static final String PENDING = "PENDING";

    public static final String LEVEL_COMMUNITY = "COMMUNITY";
    public static final String LEVEL_VILLAGE = "VILLAGE";
    public static final String LEVEL_STREET = "STREET";
    public static final String LEVEL_DISTRICT = "DISTRICT";
    public static final String LEVEL_CITY = "CITY";
    public static final String LEVEL_PROVINCE = "PROVINCE";
    public static final String LEVEL_POLYGON = "POLYGON";
    public static final String LEVEL_UNLIMITED = "UNLIMITED";
    /** UNLIMITED 项的 ref_code 恒为它：一店一条，由唯一键 (entity_no, store_no, level, ref_code) 保证 */
    public static final String UNLIMITED_REF = "*";

    /** 行政五级：按 region_code 前缀匹配的那几档 */
    public static final java.util.Set<String> ADMIN_LEVELS = java.util.Set.of(
            LEVEL_PROVINCE, LEVEL_CITY, LEVEL_DISTRICT, LEVEL_STREET, LEVEL_VILLAGE);
}
