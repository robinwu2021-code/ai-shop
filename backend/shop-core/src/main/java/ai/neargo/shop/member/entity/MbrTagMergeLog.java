package ai.neargo.shop.member.entity;

import ai.neargo.shop.common.BaseEntity;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Getter;
import lombok.Setter;

/**
 * 标签合并留痕。合并不可逆 —— 没有它就回答不了商家那句「这批人的标签怎么变了」。
 */
@Getter
@Setter
@TableName("mbr_tag_merge_log")
public class MbrTagMergeLog extends BaseEntity {

    private String entityNo;
    private String fromTagNo;
    private String toTagNo;

    /** 合并当时算好存下 —— 事后再算，算出来的是现在的样子，不是当时的 */
    private Integer affectedCount;

    private String operatorNo;
    private Long mergedAt;
}
