package ai.neargo.shop.support.port;

import ai.neargo.shop.spi.pay.PayQueryPort;
import org.springframework.context.annotation.Primary;
import org.springframework.context.annotation.Profile;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.List;

/**
 * 可编排的假查单，供对账用例摆布「通道会怎么回」。
 *
 * <p><b>为什么是一个普通的 {@code @Component} 而不是 {@code @TestConfiguration}</b>：
 * 后者会让这个测试类拥有**自己的 Spring 上下文**，而上下文一多，
 * {@code schema-test.sql} 就会在同一个 H2 库里跑第二遍 —— 整套测试成片挂在主键冲突上。
 * {@code application-opsdb.yml} 的注释里早就写着这件事，我还是踩了一次。
 *
 * <p>所以：一个 bean、一份上下文，用例之间靠 {@link #reset()} 隔离。
 */
@Profile("test")
@Primary
@Component
public class FakePayQueryPort implements PayQueryPort {

    /** 下一次查单返回什么。默认「通道没有这笔」—— 与 StubPayGateway 一致 */
    private Result next = new Result(true, false, false, 0, null);

    private final List<String> asked = new ArrayList<>();

    /** 按通道分别作答。<b>比 {@link #answer} 优先</b> —— 用来造「一家挂了、一家正常」 */
    private final java.util.Map<String, Result> byChannel = new java.util.HashMap<>();

    public void answer(Result r) {
        this.next = r;
    }

    /**
     * 只让这个通道这么答，其余仍走 {@link #answer}。
     *
     * <p>对账要按渠道看，而「一家查不通、另一家正常」是那件事唯一能证伪的场景 ——
     * 两家都正常或都挂掉时，按不按渠道分解出来的数字长得一样。
     */
    public void answerFor(String payChannel, Result r) {
        byChannel.put(payChannel, r);
    }

    public List<String> asked() {
        return asked;
    }

    public void reset() {
        asked.clear();
        byChannel.clear();
        next = new Result(true, false, false, 0, null);
    }

    @Override
    public Result query(String payChannel, String outTradeNo) {
        asked.add(outTradeNo);
        return byChannel.getOrDefault(payChannel, next);
    }
}
