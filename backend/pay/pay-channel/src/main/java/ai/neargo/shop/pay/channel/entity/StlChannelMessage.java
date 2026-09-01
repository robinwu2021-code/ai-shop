package ai.neargo.shop.pay.channel.entity;

import ai.neargo.shop.common.BaseEntity;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Getter;
import lombok.Setter;

/**
 * 渠道报文：通道推给我方的回调、我方发给通道的调用（V286）。
 *
 * <p><b>这张表的读者是排查故障的人，不是程序。</b>没有任何业务逻辑读它 ——
 * 所以它的价值全在「出事那天能不能答上话」，而出事时最想看的
 * 恰恰是<b>被拒掉的那几次</b>：验签失败、回查失败、回查说没付、认领不到单号。
 * 那四条今天全是「log 一句然后回 FAIL」，通道那边一直重推，
 * 而运营问「它到底推了什么过来」时没人答得上。
 *
 * <p><b>报文是脱敏后的，不能拿去重放验签。</b>签名、证书序列号、Authorization
 * 都不进库 —— 这张表会被备份、会被导出、会被贴进工单。想验签去通道后台看原件。
 */
@Getter
@Setter
@TableName("stl_channel_message")
public class StlChannelMessage extends BaseEntity {

    /** 通道推给我方 */
    public static final String CALLBACK = "CALLBACK";
    /** 我方发给通道 */
    public static final String SEND = "SEND";

    /**
     * 已落库，但<b>还没处理完</b>。
     *
     * <p>停在这个状态的行是有信息量的，不是脏数据：它意味着
     * 报文收到了、而处理过程中途抛异常回滚了。**这正是报文要独立事务落的理由** ——
     * 处理失败的那一次，恰恰是最需要报文的那一次。
     */
    public static final String RECEIVED = "RECEIVED";
    /** 回调：验过签、回查过、账也落了 */
    public static final String ACCEPTED = "ACCEPTED";
    /** 回调：我方拒绝了（验签失败 / 缺字段 / 回查不一致 / 认领不到），已回 FAIL */
    public static final String REJECTED = "REJECTED";
    /** 发送：通道回执成功 */
    public static final String OK = "OK";
    /** 发送：通道回执失败或调用异常 */
    public static final String FAILED = "FAILED";

    private String messageNo;

    private String payChannel;

    /** {@link #CALLBACK} 或 {@link #SEND} */
    private String msgType;

    /** 回调是端点路径，发送是接口坐标 */
    private String api;

    /** 我方单号。<b>验签失败时拿不到，会是 null</b> —— 那种行只能按时间和通道翻 */
    private String bizNo;

    /** 认领到的支付流水号。认领之前为空 */
    private String paymentNo;

    private String outcome;

    /** 拒绝或失败的原因，直接显示给运营 */
    private String reason;

    /** 脱敏并截断后的报文 */
    private String payload;

    /** 脱敏后的请求头，仅回调有 */
    private String headers;
}
