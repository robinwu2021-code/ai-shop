package ai.neargo.shop.user.community.entity;

import ai.neargo.shop.common.BaseEntity;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Getter;
import lombok.Setter;

/**
 * 社区（小区/网格）。用户的商品池、价格、履约时效都由它决定。
 *
 * <p>经纬度存 ×1e6 的整数：C 端选社区只需要「谁更近」的排序，米级精度足够，
 * 而整数比较在 SQL 里比浮点稳定得多。
 */
@Getter
@Setter
@TableName("cmt_community")
public class CmtCommunity extends BaseEntity {

    private String communityNo;
    private String name;
    private String address;

    private Integer latE6;
    private Integer lngE6;

    /** OPEN / CLOSED —— 开城开关（P-2.1.2）。CLOSED 的社区不出现在选点列表。 */
    private String status;
    /** 所属城市编码：scope=CITY 的商家靠它判定可达。 */
    private String cityCode;


    /** 本社区是否开放积分。四级串联的第二级 —— 上层关，下层一定关。 */
    private Boolean pointsEnabled;
}
