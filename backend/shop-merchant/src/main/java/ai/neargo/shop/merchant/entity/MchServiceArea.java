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

    /** COMMUNITY / VILLAGE / STREET / DISTRICT / CITY */
    private String level;

    /** {@code level=COMMUNITY} 时是 {@code community_no}，否则是 {@code region_code} */
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

    public static final String MODE_INCLUDE = "INCLUDE";
    public static final String MODE_EXCLUDE = "EXCLUDE";

    public static final String ACTIVE = "ACTIVE";
    public static final String PENDING = "PENDING";
}
