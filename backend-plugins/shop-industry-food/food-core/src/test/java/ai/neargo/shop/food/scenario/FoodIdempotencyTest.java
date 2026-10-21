package ai.neargo.shop.food.scenario;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import ai.neargo.shop.common.BizException;
import ai.neargo.shop.common.ErrorCode;
import ai.neargo.shop.idem.IdempotencyService;
import ai.neargo.shop.idem.SysIdempotentMapper;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.Test;
import org.apache.ibatis.session.SqlSessionFactory;
import org.mybatis.spring.mapper.MapperFactoryBean;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import tools.jackson.databind.ObjectMapper;

class FoodIdempotencyTest extends FoodWriteGuardFixture {
    @Test void suppliesOriginalSharedIdempotencyAndMapper() {
        runner().run(context -> assertThat(context).hasNotFailed().hasSingleBean(IdempotencyService.class)
                .hasSingleBean(SysIdempotentMapper.class));
    }
    @Test void retainsExistingPlatformServiceAndMapper() {
        runner().withUserConfiguration(ExistingPlatform.class).run(context -> {
            assertThat(context).hasNotFailed().hasSingleBean(IdempotencyService.class)
                    .hasSingleBean(SysIdempotentMapper.class);
            assertThat(context.getBean(IdempotencyService.class))
                    .isSameAs(context.getBean("existingIdempotencyService"));
            assertThat(context).doesNotHaveBean("idempotencyService").doesNotHaveBean("sysIdempotentMapper");
        });
    }
    @Test void replaySameRequestExecutesOnce() {
        runner().run(context -> {
            var fixture = prepare(context);
            var idem = context.getBean(IdempotencyService.class);
            var calls = new AtomicInteger();
            for (int retry = 0; retry < 2; retry++) {
                String value = fixture.tx().execute(status -> idem.execute("key", "FOOD:fixture:E:A", "actor",
                        "hash", String.class, () -> "result-" + calls.incrementAndGet()));
                assertThat(value).isEqualTo("result-1");
            }
            assertThat(calls).hasValue(1);
            assertThat(fixture.sql().queryForObject("SELECT COUNT(*) FROM sys_idempotent", Integer.class)).isOne();
        });
    }
    @Test void rejectChangedRequestWithSameKey() {
        runner().run(context -> {
            var fixture = prepare(context);
            var idem = context.getBean(IdempotencyService.class);
            fixture.tx().execute(status -> idem.execute("key", "FOOD:fixture:E:A", "actor", "hash-v1",
                    String.class, () -> "first"));
            var calls = new AtomicInteger();
            assertThatThrownBy(() -> fixture.tx().execute(status -> idem.execute("key", "FOOD:fixture:E:A", "actor",
                    "hash-v2", String.class, () -> { calls.incrementAndGet(); return "changed"; })))
                    .isInstanceOfSatisfying(BizException.class,
                            error -> assertThat(error.errorCode()).isEqualTo(ErrorCode.CONFLICT));
            assertThat(calls).hasValue(0);
            assertThat(fixture.sql().queryForObject("SELECT request_hash FROM sys_idempotent", String.class))
                    .isEqualTo("hash-v1");
        });
    }
    @Test void separateStoreOrOperationDomainsDoNotReplayEachOther() {
        runner().run(context -> {
            var fixture = prepare(context);
            var idem = context.getBean(IdempotencyService.class);
            var calls = new AtomicInteger();
            for (String endpoint : new String[] {"FOOD:create:E:A", "FOOD:create:E:B", "FOOD:archive:E:A"}) {
                fixture.tx().execute(status -> idem.execute("same-client-key", endpoint, "actor", "hash", String.class,
                        () -> "result-" + calls.incrementAndGet()));
            }
            assertThat(calls).hasValue(3);
        });
    }
    @Test void anotherActorCannotReplayAStoredResult() {
        runner().run(context -> {
            var fixture = prepare(context);
            var idem = context.getBean(IdempotencyService.class);
            fixture.tx().execute(status -> idem.execute("key", "FOOD:fixture:E:A", "original", "hash",
                    String.class, () -> "private"));
            assertThatThrownBy(() -> fixture.tx().execute(status -> idem.execute("key", "FOOD:fixture:E:A", "spoof",
                    "hash", String.class, () -> "wrong"))).isInstanceOfSatisfying(BizException.class,
                            error -> assertThat(error.errorCode()).isEqualTo(ErrorCode.NOT_FOUND));
        });
    }

    @Test void completedLookupNeverClaimsWorkAndPreservesActorFingerprintAndIncompleteGuards() {
        runner().run(context -> {
            var f = prepare(context); var idem = context.getBean(IdempotencyService.class);
            assertThat(idem.findCompleted("key", "FOOD:fixture:E:A", "actor", "hash", String.class)).isEmpty();
            assertThat(f.sql().queryForObject("SELECT COUNT(*) FROM sys_idempotent", Integer.class)).isZero();
            f.tx().execute(status -> idem.execute("key", "FOOD:fixture:E:A", "actor", "hash", String.class, () -> "first"));
            assertThat(idem.findCompleted("key", "FOOD:fixture:E:A", "actor", "hash", String.class)).contains("first");
            assertThat(idem.findCompleted("key", "FOOD:fixture:E:B", "actor", "hash", String.class)).isEmpty();
            assertThatThrownBy(() -> idem.findCompleted("key", "FOOD:fixture:E:A", "another", "hash", String.class))
                    .isInstanceOfSatisfying(BizException.class, e -> assertThat(e.errorCode()).isEqualTo(ErrorCode.NOT_FOUND));
            assertThatThrownBy(() -> idem.findCompleted("key", "FOOD:fixture:E:A", "actor", "changed", String.class))
                    .isInstanceOfSatisfying(BizException.class, e -> assertThat(e.errorCode()).isEqualTo(ErrorCode.CONFLICT));
            f.sql().update("UPDATE sys_idempotent SET result_json=NULL");
            assertThatThrownBy(() -> idem.findCompleted("key", "FOOD:fixture:E:A", "actor", "hash", String.class))
                    .isInstanceOf(IdempotencyService.ConcurrentRequestException.class);
        });
    }

    @Configuration(proxyBeanMethods = false)
    static class ExistingPlatform {
        @Bean MapperFactoryBean<SysIdempotentMapper> existingSysIdempotentMapper(
                @Qualifier("sqlSessionFactory") SqlSessionFactory factory) {
            var mapper = new MapperFactoryBean<>(SysIdempotentMapper.class);
            mapper.setSqlSessionFactory(factory);
            return mapper;
        }
        @Bean IdempotencyService existingIdempotencyService(SysIdempotentMapper mapper, ObjectMapper json) {
            return new IdempotencyService(mapper, json);
        }
    }
}
