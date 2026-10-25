package ai.neargo.shop.spi.storedvalue;

import static org.assertj.core.api.Assertions.assertThat;

import ai.neargo.shop.spi.storedvalue.StoredValueWallets.ExecutionScope;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.ObjectMapper;

/**
 * coreFinanceProtocol=0 在序列化时被省略（NON_DEFAULT，旧快照的文本与哈希因此不变），
 * 读回来时必须还原成 0 —— 否则任何存进预览快照的钱包证明都读不出来
 * （Jackson 3 默认 FAIL_ON_NULL_FOR_PRIMITIVES，缺字段即报错）。
 */
class ExecutionScopeJsonTest {
    private final ObjectMapper json = new ObjectMapper();

    @Test
    void protocolZeroIsOmittedAndReadsBackAsZero() {
        var scope = new ExecutionScope("MAIN", "U1", "MG1", "E1", "S1", 3, "B1", "BD1", 2, "CNY", 5);
        String text = json.writeValueAsString(scope);
        assertThat(text).doesNotContain("coreFinanceProtocol");
        assertThat(json.readValue(text, ExecutionScope.class)).isEqualTo(scope);
    }

    @Test
    void nonZeroProtocolRoundTrips() {
        var scope = new ExecutionScope("MAIN", "U1", "MG1", "E1", "S1", 3, "B1", "BD1", 2, "CNY", 5, 2);
        String text = json.writeValueAsString(scope);
        assertThat(text).contains("\"coreFinanceProtocol\":2");
        assertThat(json.readValue(text, ExecutionScope.class)).isEqualTo(scope);
    }
}
