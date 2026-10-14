package ai.neargo.shop.scenario;

import ai.neargo.shop.message.MessageService;
import ai.neargo.shop.message.entity.MsgMessage;
import ai.neargo.shop.message.mapper.MessageMappers.MessageMapper;
import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.ActiveProfiles;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;

/**
 * 站内信查重与唯一键必须同口径。
 *
 * <p><b>这是一颗埋着的雷</b>：{@code uk_msg_dedup} 是**单列**唯一键（不含 deleted），
 * 而查重原本走 MyBatis-Plus 的逻辑删除过滤 —— 查重看不见软删的行，唯一键看得见。
 * 于是某条站内信被软删之后：查重说「没用过」→ insert → 撞唯一键 →
 * {@code DuplicateKeyException} 抛给 OutboxDispatcher → **那条事件无限重投**。
 *
 * <p><b>症状指向一个毫不相干的地方</b>：重投把 {@code sys_outbox.retrying} 顶起来，
 * 最后变红的是判「投递任务是不是停了」的 {@code OpsLinkHealthFlowTest}。
 * 2026-09-29 在测试里真实发生过一次，追了三轮才找到这里。
 */
@SpringBootTest
@ActiveProfiles("test")
class MessageDedupSoftDeleteTest {

    @Autowired
    private MessageService messageService;
    @Autowired
    private MessageMapper messageMapper;

    @Test
    @DisplayName("★★★ 软删之后用同一个 dedupKey 再推 —— 不能抛，否则那条事件会无限重投")
    void pushAfterSoftDeleteDoesNotBlowUp() {
        String user = "U-DEDUP-" + System.nanoTime();
        String dedup = "EVT-DEDUP-" + System.nanoTime();

        messageService.push(user, MessageService.TRADE, "到货了", "第一次", "/pages/x", dedup);
        assertThat(rows(user)).as("第一条就没写进去，后面测的都不算数").hasSize(1);

        // 软删（业务上「删站内信」会走到这里；唯一键仍被这一行占着）
        messageMapper.delete(Wrappers.<MsgMessage>lambdaQuery().eq(MsgMessage::getReceiverNo, user));
        assertThat(rows(user)).as("没删掉的话这条测试测不到东西").isEmpty();

        assertThatCode(() ->
                messageService.push(user, MessageService.TRADE, "到货了", "重投", "/pages/x", dedup))
                .as("查重看不见软删行 → insert → 撞 uk_msg_dedup → 事件无限重投")
                .doesNotThrowAnyException();

        // 幂等：重投应当被查重挡住，而不是写出第二条
        assertThat(rows(user)).as("软删的那条不该被「复活」成新的一条").isEmpty();
    }

    @Test
    @DisplayName("★★ 正常重投（没删过）照旧静默跳过，不写第二条")
    void normalRedeliveryIsStillDeduped() {
        String user = "U-DEDUP2-" + System.nanoTime();
        String dedup = "EVT-DEDUP2-" + System.nanoTime();

        messageService.push(user, MessageService.TRADE, "到货了", "第一次", "/pages/x", dedup);
        messageService.push(user, MessageService.TRADE, "到货了", "重投", "/pages/x", dedup);

        assertThat(rows(user)).hasSize(1);
        assertThat(rows(user).getFirst().getBody()).isEqualTo("第一次");
    }

    private java.util.List<MsgMessage> rows(String userNo) {
        return messageMapper.selectList(Wrappers.<MsgMessage>lambdaQuery()
                .eq(MsgMessage::getReceiverNo, userNo));
    }
}
