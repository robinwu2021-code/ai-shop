package ai.neargo.shop.member.entity;

import ai.neargo.shop.common.BaseEntity;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Getter;
import lombok.Setter;

/** 谁被打了哪个标签。<b>只存 {@code tagNo}</b>，文本在字典里。 */
@Getter
@Setter
@TableName("mbr_member_tag")
public class MbrMemberTag extends BaseEntity {

    private String entityNo;
    private String memberNo;
    private String tagNo;
    private String tagType;

    /** 谁打的。系统标签为空 */
    private String taggedBy;

    private Long taggedAt;
}
