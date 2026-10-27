package ai.neargo.secret.core;

import ai.neargo.secret.store.mapper.SecretMappers;
import org.mybatis.spring.SqlSessionTemplate;

/** 七个 Mapper 一起传，省得每个服务的构造器排七个参数 */
public record SecretStoreMappers(
        SecretMappers.MasterKeyMapper masterKeys,
        SecretMappers.SecretBlobMapper blobs,
        SecretMappers.KeyPolicyMapper policies,
        SecretMappers.KeyMapper keys,
        SecretMappers.KeyVersionMapper versions,
        SecretMappers.KeyEventMapper events,
        SecretMappers.AccessLogMapper accessLogs) {

    public static SecretStoreMappers of(SqlSessionTemplate session) {
        return new SecretStoreMappers(
                session.getMapper(SecretMappers.MasterKeyMapper.class),
                session.getMapper(SecretMappers.SecretBlobMapper.class),
                session.getMapper(SecretMappers.KeyPolicyMapper.class),
                session.getMapper(SecretMappers.KeyMapper.class),
                session.getMapper(SecretMappers.KeyVersionMapper.class),
                session.getMapper(SecretMappers.KeyEventMapper.class),
                session.getMapper(SecretMappers.AccessLogMapper.class));
    }
}
