package ai.neargo.shop.media;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

import java.time.LocalDateTime;

/**
 * 一次人工确认对应一行。<b>删文件这件事，从此有名有姓有时间。</b>
 *
 * <p>之所以要有批次实体而不是只在资产上挂一个号：
 * <ul>
 *   <li>运营端「回收记录」要答「谁、什么时候、删了多少、花了多久」——
 *       这些是批次的属性，挂在每行资产上就要聚合几万行才能答一个问题</li>
 *   <li>失败的要能重跑，而「重跑哪一批」没有批次实体就无从表达</li>
 * </ul>
 */
@Data
@TableName("sys_media_purge_batch")
public class SysMediaPurgeBatch {

    /** 已提交，等任务捡起来。 */
    public static final String QUEUED = "QUEUED";
    /** 正在删。 */
    public static final String RUNNING = "RUNNING";
    /** 全部成功。 */
    public static final String DONE = "DONE";
    /** 有失败的 —— 失败的那几张仍留在批次里，运营点一下重试。 */
    public static final String PARTIAL = "PARTIAL";

    @TableId(type = IdType.AUTO)
    private Long id;

    private String batchNo;

    /** 运营账号。<b>不可逆动作必须记名。</b> */
    private String operator;

    /**
     * 发起时的显示名，快照。
     *
     * <p>只存账号的话，人离职、账号改名之后这条记录就再也说不清是谁了 ——
     * 而追溯一次误删往往正是发生在很久以后。
     */
    private String operatorName;

    /** {@link #QUEUED} / {@link #RUNNING} / {@link #DONE} / {@link #PARTIAL} */
    private String status;

    private Integer totalCount;
    private Long totalBytes;
    private Integer purgedCount;
    private Integer failedCount;

    private LocalDateTime startedAt;
    private LocalDateTime finishedAt;
    private LocalDateTime createdAt;
    private LocalDateTime updatedAt;
}
