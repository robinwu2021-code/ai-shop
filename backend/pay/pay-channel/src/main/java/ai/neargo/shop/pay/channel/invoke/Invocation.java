package ai.neargo.shop.pay.channel.invoke;

import ai.neargo.shop.pay.channel.api.ChannelAccountContext;
import ai.neargo.shop.pay.channel.api.ChannelKey;
import ai.neargo.shop.pay.channel.api.ChannelResult;
import ai.neargo.shop.pay.channel.api.command.ChannelCommand;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

/**
 * 单次调用：一个命令从进管线到出管线（渠道接入统一封装设计 §5.3）。同一次调用的多次重试共用 {@link #invocationNo}，
 * {@link #attempt()} 递增。管线与处理器在同一个对象上协作，所以它是可变的；它不出管线。
 */
public final class Invocation {

    private final String invocationNo;
    private final ChannelKey key;
    private final ChannelAccountContext account;
    private final ChannelCommand command;
    private final Instant startedAt;
    private final List<InvocationListener> listeners = new ArrayList<>();
    private volatile InvocationState state = InvocationState.CREATED;
    private int attempt = 1;
    /** 这一次发送的请求是否已经离开本机。没离开的失败可以安全重发 */
    private volatile boolean requestSent;
    /** 调用记录行 id（S3 RecordingInterceptor 回填） */
    private Long callLogId;
    /** 本次生效的容错策略摘要，写进调用记录 */
    private String faultPolicySummary;
    private String breakerState;

    private Invocation(ChannelKey key, ChannelAccountContext account, ChannelCommand command) {
        this.invocationNo = "INV" + UUID.randomUUID().toString().replace("-", "");
        this.key = key;
        this.account = account;
        this.command = command;
        this.startedAt = Instant.now();
    }

    public static Invocation start(ChannelKey key, ChannelAccountContext account, ChannelCommand command) {
        return new Invocation(key, account, command);
    }

    public void addListener(InvocationListener listener) {
        listeners.add(listener);
    }

    /** 管线外层只调一次：通知监听者第一次发送开始 */
    public void begin() {
        listeners.forEach(l -> l.attemptStarted(this));
    }

    /** 结束本次发送并开始下一次（重试）。监听者据此关上一行、开新一行 */
    public void nextAttempt(ChannelResult previous) {
        listeners.forEach(l -> l.attemptFinished(this, previous));
        attempt++;
        requestSent = false;
        listeners.forEach(l -> l.attemptStarted(this));
    }

    /** 管线外层只调一次：通知监听者最后一次发送的结果 */
    public void end(ChannelResult result) {
        listeners.forEach(l -> l.attemptFinished(this, result));
        listeners.forEach(l -> l.invocationEnded(this, result));
    }

    public String invocationNo() {
        return invocationNo;
    }

    public ChannelKey key() {
        return key;
    }

    public ChannelAccountContext account() {
        return account;
    }

    public ChannelCommand command() {
        return command;
    }

    public Instant startedAt() {
        return startedAt;
    }

    public Duration elapsed() {
        return Duration.between(startedAt, Instant.now());
    }

    public InvocationState state() {
        return state;
    }

    public void state(InvocationState state) {
        this.state = state;
    }

    public int attempt() {
        return attempt;
    }

    public boolean requestSent() {
        return requestSent;
    }

    /** 传输层在把字节写出之前调用；之后发生的任何问题都意味着渠道可能已经收到 */
    public void markSent() {
        this.requestSent = true;
        this.state = InvocationState.SENT;
    }

    /**
     * 连接没建成（拒绝、DNS、连接超时）：请求没离开本机，撤回 {@link #markSent()}。
     * 只有传输层能分清这一点（{@code RecordingChannelClient}），重试据此判断能否安全重发。
     */
    public void connectFailed() {
        this.requestSent = false;
    }

    public Long callLogId() {
        return callLogId;
    }

    public void callLogId(Long callLogId) {
        this.callLogId = callLogId;
    }

    public String faultPolicySummary() {
        return faultPolicySummary;
    }

    public void faultPolicySummary(String summary) {
        this.faultPolicySummary = summary;
    }

    public String breakerState() {
        return breakerState;
    }

    public void breakerState(String breakerState) {
        this.breakerState = breakerState;
    }
}
