package ai.neargo.shop.elec.svc;

import ai.neargo.shop.elec.gateway.ElecColumnAi;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Primary;

import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.Function;

/** 顶替 cdw 上的 qwen：回什么由用例定，并数调用次数（「不该调的时候没调」要能断言） */
public class FakeColumnAi implements ElecColumnAi {

    public final AtomicInteger calls = new AtomicInteger();
    /** 按表的前几行给答案；回 null = 模型不可用 */
    public volatile Function<List<List<String>>, Guess> answer = head -> null;

    @Override
    public boolean isEnabled() {
        return true;
    }

    @Override
    public Guess guess(List<List<String>> head, List<FieldSpec> fields) {
        calls.incrementAndGet();
        return answer.apply(head);
    }

    @TestConfiguration
    public static class Config {
        @Bean
        @Primary
        public FakeColumnAi fakeColumnAi() {
            return new FakeColumnAi();
        }
    }
}
