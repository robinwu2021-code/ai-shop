package ai.neargo.job.engine;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

/** params 解析。**判据全在「坏配置会怎样」上** —— 好配置能用是最容易写对的那一半。 */
class JobParamsTest {

    @Test
    @DisplayName("平铺的 JSON 对象照单全收，数字与布尔按文本给出")
    void flatObject() {
        Map<String, String> p = JobParams.parse("j", """
                {"channel":"wechat","batchSize":500,"dryRun":true}""");
        assertEquals("wechat", p.get("channel"));
        assertEquals("500", p.get("batchSize"), "参数是 Map<String,String>，类型转换归任务侧");
        assertEquals("true", p.get("dryRun"));
    }

    @Test
    @DisplayName("空与 null 都是空参数，不是错误 —— 绝大多数任务本来就不带参数")
    void blankIsEmpty() {
        assertTrue(JobParams.parse("j", null).isEmpty());
        assertTrue(JobParams.parse("j", "").isEmpty());
        assertTrue(JobParams.parse("j", "   ").isEmpty());
    }

    @Test
    @DisplayName("★ 坏 JSON 不能停任务：空参数 + 一条 WARN，而不是抛异常")
    void brokenJsonDoesNotThrow() {
        assertTrue(assertDoesNotThrow(() -> JobParams.parse("j", "{这不是 JSON")).isEmpty(),
                "一行手打错的配置不该让这个任务从此不跑 —— 那种停摆看上去像业务故障");
    }

    @Test
    @DisplayName("顶层不是对象（数组、裸值）同样按空参数处理")
    void nonObjectRoot() {
        assertTrue(JobParams.parse("j", "[1,2,3]").isEmpty());
        assertTrue(JobParams.parse("j", "\"just-a-string\"").isEmpty());
    }

    @Test
    @DisplayName("★ 嵌套值跳过那一个键，其余照用 —— 不是整份丢掉")
    void nestedValueIsSkippedNotFatal() {
        Map<String, String> p = JobParams.parse("j", """
                {"channel":"wechat","nested":{"a":1},"list":[1,2]}""");
        assertEquals(Map.of("channel", "wechat"), p,
                "把嵌套项塞成 JSON 字符串等于让每个 handler 再解析一次");
    }

    @Test
    @DisplayName("返回的 map 不可变 —— 任务体改了它不该影响下一次重试")
    void resultIsImmutable() {
        Map<String, String> p = JobParams.parse("j", "{\"a\":\"1\"}");
        assertThrows(UnsupportedOperationException.class, () -> p.put("b", "2"));
    }
}
