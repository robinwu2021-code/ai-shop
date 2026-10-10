package ai.neargo.shop.user.entity;

import ai.neargo.shop.common.BaseEntity;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Getter;
import lombok.Setter;

/**
 * 人档合并留痕。
 *
 * <p><b>这张表常年应该是空的。</b> 会员必须有手机号之后，合并只剩两种：
 * 换号撞上别人的线索档、人工纠错。<b>不空就说明别处错了</b> —— 它顺便是个健康指标。
 *
 * <p>合并不可逆，没有日志就回答不了商家那句「我的会员怎么少了一个」。
 */
@Getter
@Setter
@TableName("usr_person_merge_log")
public class UsrPersonMergeLog extends BaseEntity {

    /** 补号时撞上了一份没绑账号的线索档 */
    public static final String BIND_PHONE = "BIND_PHONE";
    /** 换号：新号已经有人档 */
    public static final String CHANGE_PHONE = "CHANGE_PHONE";
    /** 运营在申诉处置里手工合并 */
    public static final String OPS = "OPS";

    private String fromPersonNo;
    private String toPersonNo;
    private String reason;

    /** 受影响的会员关系条数。合并当时算好存下 —— 事后再算算不出当时的样子 */
    private Integer affectedMembers;

    private String operatorNo;
    private Long mergedAt;
}
