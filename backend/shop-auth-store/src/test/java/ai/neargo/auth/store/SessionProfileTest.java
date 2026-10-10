package ai.neargo.auth.store;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.time.Duration;

import static org.junit.jupiter.api.Assertions.*;

/**
 * 档位校验。**这些错误必须在启动时炸，不能等到第一次查询。**
 */
class SessionProfileTest {

    private SessionProfile withTable(String table) {
        return new SessionProfile("p", table, "usr_login_log", "ctk_",
                Duration.ofDays(30), Duration.ofSeconds(60), Duration.ofSeconds(30),
                Duration.ofSeconds(5), Duration.ofHours(1), false, 90);
    }

    @Test
    @DisplayName("★ 表名要过形状校验 —— 它会被拼进 SQL")
    void tableNameIsValidated() {
        assertDoesNotThrow(() -> withTable("usr_session"));
        for (String bad : new String[]{"usr session", "usr;drop", "USR", "u", "", "1abc"}) {
            assertThrows(IllegalArgumentException.class, () -> withTable(bad),
                    "非法表名应当当场拒绝：" + bad);
        }
        assertThrows(IllegalArgumentException.class, () -> withTable(null));
    }

    @Test
    @DisplayName("★ cacheTtl 比 revokePoll 还短 = 撤销轮询形同虚设，当场喊出来")
    void cacheTtlShorterThanPollIsRejected() {
        IllegalArgumentException e = assertThrows(IllegalArgumentException.class, () ->
                new SessionProfile("consumer", "usr_session", "usr_login_log", "ctk_",
                        Duration.ofDays(30), Duration.ofSeconds(3), Duration.ofSeconds(30),
                        Duration.ofSeconds(5), Duration.ofHours(1), false, 90));
        assertTrue(e.getMessage().contains("形同虚设"), e.getMessage());
        assertTrue(e.getMessage().contains("consumer"), "报错要指名是哪一端");
    }

    @Test
    @DisplayName("前缀不能为空 —— 它是端隔离的第一道")
    void prefixIsRequired() {
        assertThrows(IllegalArgumentException.class, () ->
                new SessionProfile("p", "usr_session", "usr_login_log", "  ",
                        Duration.ofDays(30), Duration.ofSeconds(60), Duration.ofSeconds(30),
                        Duration.ofSeconds(5), Duration.ofHours(1), false, 90));
    }

    @Test
    @DisplayName("时限必须为正")
    void durationsMustBePositive() {
        assertThrows(IllegalArgumentException.class, () ->
                new SessionProfile("p", "usr_session", "usr_login_log", "ctk_",
                        Duration.ZERO, Duration.ofSeconds(60), Duration.ofSeconds(30),
                        Duration.ofSeconds(5), Duration.ofHours(1), false, 90));
    }
}
