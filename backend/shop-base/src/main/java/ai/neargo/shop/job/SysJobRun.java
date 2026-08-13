package ai.neargo.shop.job;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Getter;
import lombok.Setter;

import java.time.LocalDateTime;

/**
 * 一个定时任务的运行记录（**一个任务一行**，每轮覆盖）。
 *
 * <p>不继承 {@code BaseEntity}：那上面有逻辑删除，而运行记录没有「删除」这个动作 ——
 * 任务还在跑，它的记录就该在。
 */
@Getter
@Setter
@TableName("sys_job_run")
public class SysJobRun {

    public static final String OK = "OK";
    public static final String FAILED = "FAILED";

    @TableId(type = IdType.AUTO)
    private Long id;

    private String jobName;
    private LocalDateTime lastRunAt;
    private Long durationMs;
    private String status;

    /** 这一轮做了什么。**空着比写「成功」有用得多** —— 「投出 12 条」才回答得了问题 */
    private String detail;

    private String error;

    /** 连续失败次数。成功即清零 */
    private Integer consecutiveFailures;

    private Long runCount;
}
