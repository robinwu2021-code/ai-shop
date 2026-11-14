package ai.neargo.secret.store.mapper;

import ai.neargo.secret.store.entity.SecAccessLog;
import ai.neargo.secret.store.entity.SecKey;
import ai.neargo.secret.store.entity.SecKeyEvent;
import ai.neargo.secret.store.entity.SecKeyPolicy;
import ai.neargo.secret.store.entity.SecKeyVersion;
import ai.neargo.secret.store.entity.SecMasterKey;
import ai.neargo.secret.store.entity.SecSecretBlob;
import com.baomidou.mybatisplus.core.mapper.BaseMapper;

/**
 * {@code sec_*} 七张表的 Mapper（db/secret V1）。
 *
 * <p>只由宿主的 secret 专用工厂扫描（shop-app {@code SecretDataSourceConfig}，包 {@code ai.neargo.secret.store.mapper}）。
 * 包名不在 {@code ai.neargo.shop} 之下，平台那个全局 {@code @MapperScan} 天然扫不到它 —— 不需要再加排除规则。
 */
public final class SecretMappers {

    private SecretMappers() {
    }

    public interface MasterKeyMapper extends BaseMapper<SecMasterKey> {
    }

    public interface SecretBlobMapper extends BaseMapper<SecSecretBlob> {
    }

    public interface KeyPolicyMapper extends BaseMapper<SecKeyPolicy> {
    }

    public interface KeyMapper extends BaseMapper<SecKey> {
    }

    public interface KeyVersionMapper extends BaseMapper<SecKeyVersion> {
    }

    public interface KeyEventMapper extends BaseMapper<SecKeyEvent> {
    }

    public interface AccessLogMapper extends BaseMapper<SecAccessLog> {
    }
}
