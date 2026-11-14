package ai.neargo.inventory.domain.entity;

import com.baomidou.mybatisplus.annotation.FieldFill;
import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableField;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Getter;
import lombok.Setter;

import java.time.LocalDateTime;

/**
 * 一条写命令的幂等记录：同键同参返回原结果，同键异参冲突（AC-03）。
 *
 * <p>不继承 {@link InvEntity}：这张表没有 {@code created_by}（操作人记在 {@code actor}），
 * 继承过来查询会带出一个库里没有的列。
 */
@Getter
@Setter
@TableName("inv_command")
public class InvCommand {
    @TableId(type = IdType.AUTO)
    private Long id;
    private String systemCode;
    private String ownerId;
    private String requestId;
    private String commandType;
    private String requestHash;
    /** DONE */
    private String status;
    private String resultBody;
    private String actor;
    @TableField(fill = FieldFill.INSERT)
    private LocalDateTime createdAt;
}
