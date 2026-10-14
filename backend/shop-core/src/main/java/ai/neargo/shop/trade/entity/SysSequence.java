package ai.neargo.shop.trade.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Getter;
import lombok.Setter;

/**
 * 号段序列（ADR-035）。**不继承 BaseEntity** —— 它没有 id/version/deleted/逻辑删那一套，
 * 就一个游标。主键是 {@code seq_name}（业务指定，不是自增）。
 */
@Getter
@Setter
@TableName("sys_sequence")
public class SysSequence {

    @TableId(value = "seq_name", type = IdType.INPUT)
    private String seqName;

    /** 已发出的最大序列值；领段时 += 段长 */
    private Long nextValue;
}
