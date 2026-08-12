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

    public void answer(Result r) {
        this.next = r;
    }

    public List<String> asked() {
        return asked;
    }

    public void reset() {
        asked.clear();
        next = new Result(true, false, false, 0, null);
    }

    @Override
    public Result query(String payChannel, String outTradeNo) {
        asked.add(outTradeNo);
        return next;
    }
}
