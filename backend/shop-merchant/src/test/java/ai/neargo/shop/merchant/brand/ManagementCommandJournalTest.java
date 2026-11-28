package ai.neargo.shop.merchant.brand;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import ai.neargo.shop.common.BizException;
import java.util.Map;
import org.junit.jupiter.api.Test;

/**
 * 管理命令的重放与变更历史：原先在 mch_management_command，新库不建，改走统一防重与统一审计（TDD-平台基础库换表 G5、G6）。
 */
class ManagementCommandJournalTest {
    @Test void originalCommandReplaysWithoutCreatingAnyDelivery() throws Exception {
        var f=new BrandFixture();
        var first=f.space();var replay=f.space();
        assertThat(replay.get("managementNo")).isEqualTo(first.get("managementNo"));
        assertThat(f.journal.completed).hasSize(1);
        String key=f.journal.completed.keySet().iterator().next();
        String command=key.substring(0,key.indexOf('|'));
        @SuppressWarnings("unchecked") Map<String,Object> original=f.journal.findCompleted(command,"management.command",null,null,Map.class).orElseThrow();
        assertThat(f.repo.replay(command,(String)original.get("commandHash"))).isEqualTo(original.get("resultSnapshot"));
        assertThat(f.jdbc.queryForObject("SELECT COUNT(*) FROM mch_group",Long.class)).isEqualTo(1);
        assertThat(f.jdbc.queryForObject("SELECT COUNT(*) FROM sys_outbox",Long.class)).isZero();
        assertThat(f.journal.audits).as("建集团写一条统一审计").hasSize(1);
        assertThatThrownBy(()->f.repo.replay(command,"DIFFERENT")).isInstanceOf(BizException.class);
    }
    @Test void commandAndAuditAreRecordedOnlyAfterTheBusinessTransactionCommits() throws Exception {
        var f=new BrandFixture();
        assertThatThrownBy(()->f.repo.write(()->{f.space();throw new IllegalStateException("rollback");})).hasMessage("rollback");
        assertThat(f.journal.completed).as("回滚的写不留可重放的结果").isEmpty();
        assertThat(f.journal.audits).as("回滚的写不留变更历史").isEmpty();
        assertThat(f.jdbc.queryForObject("SELECT COUNT(*) FROM mch_group",Long.class)).isZero();
        assertThat(f.jdbc.queryForObject("SELECT COUNT(*) FROM sys_outbox",Long.class)).isZero();
    }
}
