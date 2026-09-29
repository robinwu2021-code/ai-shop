package ai.neargo.shop.elec.entity;

import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Getter;
import lombok.Setter;

/** 料号分段键：料号从某个字母/数字交界处起的后缀，让中段也能按前缀索引搜到。 */
@Getter
@Setter
@TableName("elc_part_key")
public class ElcPartKey extends ElcEntity {

    private String keyNorm;

    private String partNo;

    private Integer pos;
}
