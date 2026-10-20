package ai.neargo.shop.pay.storedvalue;
import static org.assertj.core.api.Assertions.*;
import ai.neargo.shop.common.BizException;
import ai.neargo.shop.pay.storedvalue.service.StoredValueCursorCodec;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.ObjectMapper;
class StoredValueCursorHistoryTest {
    private final StoredValueCursorCodec codec=new StoredValueCursorCodec("history-cursor-key-at-least-thirty-two-characters",new ObjectMapper());
    @Test void historicalEpochZeroAndOlderSourceFactsRemainPageable() {
        for(long at:new long[]{0,-1,java.time.Instant.parse("1000-01-01T00:00:00Z").toEpochMilli()}) {
            var cursor=codec.encode("RIGHTS_USAGE","MAIN","U1","filters",at,"EU1");
            assertThat(codec.decode(cursor,"RIGHTS_USAGE","MAIN","U1","filters").occurredAtMillis()).isEqualTo(at);
        }
    }
    @Test void historicalTimeSupportDoesNotRelaxMemberBindingOrOtherProtocols() {
        var cursor=codec.encode("RIGHTS_USAGE","MAIN","U1","filters",-1,"EU1");
        assertThatThrownBy(()->codec.decode(cursor,"RIGHTS_USAGE","MAIN","U2","filters")).isInstanceOf(BizException.class);
        assertThatThrownBy(()->codec.decode(cursor,"RIGHTS_USAGE","MAIN","U1","other")).isInstanceOf(BizException.class);
        var futureProtocol=codec.encode("SV_HISTORY","MAIN","U1","filters",-1,"SV1");
        assertThatThrownBy(()->codec.decode(futureProtocol,"SV_HISTORY","MAIN","U1","filters")).isInstanceOf(BizException.class);
    }
}
