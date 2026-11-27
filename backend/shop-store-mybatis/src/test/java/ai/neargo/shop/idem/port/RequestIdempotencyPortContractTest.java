package ai.neargo.shop.idem.port;

import ai.neargo.shop.idem.SysIdempotent;
import ai.neargo.shop.idem.SysIdempotentMapper;

import ai.neargo.shop.common.BizException;
import ai.neargo.shop.common.ErrorCode;
import ai.neargo.shop.spi.idempotency.RequestIdempotencyPort;
import ai.neargo.shop.spi.idempotency.RequestIdempotencyPort.ConcurrentRequestException;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.dao.DuplicateKeyException;
import tools.jackson.databind.ObjectMapper;

import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/** 统一防重端口的语义 S1–S5、S7（TDD-基础能力库对齐 §2.2 1g）；S6 同事务由 shop-app 的 {@code IdempotencyTest} 在库上验。 */
@DisplayName("接口级防重：统一端口的语义")
class RequestIdempotencyPortContractTest {

    private final SysIdempotentMapper mapper = mock(SysIdempotentMapper.class);
    private final RequestIdempotencyPort port = new RequestIdempotencyPortImpl(mapper, new ObjectMapper());

    private static SysIdempotent receipt(String user, String hash, String result) {
        SysIdempotent row = new SysIdempotent();
        row.setUserNo(user);
        row.setRequestHash(hash);
        row.setResultJson(result);
        return row;
    }

    private void alreadyClaimed(SysIdempotent existing) {
        when(mapper.insert(any(SysIdempotent.class))).thenThrow(new DuplicateKeyException("dup"));
        when(mapper.selectOne(any())).thenReturn(existing);
    }

    @Test
    @DisplayName("S1 首次执行并记下结果；重复返回首次结果原样、不再执行")
    void firstExecutesThenReplaysOriginal() {
        AtomicInteger runs = new AtomicInteger();
        assertThat(port.execute("K", "POST /x", "U1", "H", String.class, () -> "R" + runs.incrementAndGet()))
                .isEqualTo("R1");
        verify(mapper).updateById(any(SysIdempotent.class));

        alreadyClaimed(receipt("U1", "H", "\"R1\""));
        assertThat(port.execute("K", "POST /x", "U1", "H", String.class, () -> "R" + runs.incrementAndGet()))
                .isEqualTo("R1");
        assertThat(runs.get()).isEqualTo(1);
    }

    @Test
    @DisplayName("S2 同键请求内容变了 → CONFLICT")
    void changedContentConflicts() {
        alreadyClaimed(receipt("U1", "H1", "\"R1\""));
        assertThatThrownBy(() -> port.execute("K", "POST /x", "U1", "H2", String.class, () -> "R2"))
                .isInstanceOfSatisfying(BizException.class, e -> assertThat(e.errorCode()).isEqualTo(ErrorCode.CONFLICT));
    }

    @Test
    @DisplayName("S3 同键换了人 → NOT_FOUND")
    void otherUserSeesNothing() {
        alreadyClaimed(receipt("U1", "H", "\"R1\""));
        assertThatThrownBy(() -> port.execute("K", "POST /x", "U2", "H", String.class, () -> "R2"))
                .isInstanceOfSatisfying(BizException.class, e -> assertThat(e.errorCode()).isEqualTo(ErrorCode.NOT_FOUND));
    }

    @Test
    @DisplayName("S4 首次未完成时的重复 → ConcurrentRequestException，不执行")
    void inFlightIsRejected() {
        alreadyClaimed(receipt("U1", "H", null));
        AtomicInteger runs = new AtomicInteger();
        assertThatThrownBy(() -> port.execute("K", "POST /x", "U1", "H", String.class,
                () -> "R" + runs.incrementAndGet())).isInstanceOf(ConcurrentRequestException.class);
        assertThat(runs.get()).isZero();
    }

    @Test
    @DisplayName("S5 不带键 → 直接执行，不记回执")
    void noKeyNoDedup() {
        assertThat(port.execute(" ", "POST /x", "U1", String.class, () -> "R")).isEqualTo("R");
        verify(mapper, never()).insert(any(SysIdempotent.class));
    }

    @Test
    @DisplayName("S7 findCompleted 只读：有完成回执返回结果，没有返回空，都不占位")
    void findCompletedIsReadOnly() {
        when(mapper.selectOne(any())).thenReturn(receipt("U1", "H", "\"R1\""));
        assertThat(port.findCompleted("K", "POST /x", "U1", "H", String.class)).contains("R1");
        when(mapper.selectOne(any())).thenReturn(null);
        assertThat(port.findCompleted("K", "POST /x", "U1", "H", String.class)).isEmpty();
        verify(mapper, never()).insert(any(SysIdempotent.class));
    }
}
