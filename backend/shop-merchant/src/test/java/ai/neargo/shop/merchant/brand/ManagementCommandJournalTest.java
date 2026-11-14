package ai.neargo.shop.merchant.brand;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import ai.neargo.shop.common.BizException;
import org.junit.jupiter.api.Test;

class ManagementCommandJournalTest {
    @Test void originalCommandReplaysWithoutCreatingAnyDelivery() throws Exception {
        var f=new BrandFixture();
        var first=f.space();var replay=f.space();
        assertThat(replay.get("managementNo")).isEqualTo(first.get("managementNo"));
        var original=f.repo.decode(f.jdbc.queryForObject("SELECT payload FROM mch_management_command",String.class));
        String storedCommand=f.jdbc.queryForObject("SELECT command_id FROM mch_management_command",String.class);
        assertThat(f.repo.replay(storedCommand,(String)original.get("commandHash"))).isEqualTo(original.get("resultSnapshot"));
        assertThat(f.jdbc.queryForObject("SELECT COUNT(*) FROM mch_management_command",Long.class)).isEqualTo(1);
        assertThat(f.jdbc.queryForObject("SELECT COUNT(*) FROM sys_outbox",Long.class)).isZero();
        String command=f.jdbc.queryForObject("SELECT command_id FROM mch_management_command",String.class);
        assertThatThrownBy(()->f.repo.replay(command,"DIFFERENT")).isInstanceOf(BizException.class);
    }
    @Test void commandAndAuditRollBackWithTheBusinessTransaction() throws Exception {
        var f=new BrandFixture();
        assertThatThrownBy(()->f.repo.write(()->{f.space();throw new IllegalStateException("rollback");})).hasMessage("rollback");
        assertThat(f.jdbc.queryForObject("SELECT COUNT(*) FROM mch_management_command",Long.class)).isZero();
        assertThat(f.jdbc.queryForObject("SELECT COUNT(*) FROM mch_management_space",Long.class)).isZero();
        assertThat(f.jdbc.queryForObject("SELECT COUNT(*) FROM sys_outbox",Long.class)).isZero();
    }
}
