package ai.neargo.shop.elec.entity;

import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Getter;
import lombok.Setter;

/** 搜索需求日聚合。只存「哪天、搜了什么、几次、几次没结果」，不存是谁搜的。 */
@Getter
@Setter
@TableName("elc_search_daily")
public class ElcSearchDaily extends ElcMutableEntity {

    private java.time.LocalDate statDate;

    private String keyword;

    private Integer searchCnt;

    private Integer zeroCnt;

    private Integer stockCnt;
}
