package ai.neargo.secret.core;

import ai.neargo.secret.api.SecretException;
import ai.neargo.secret.api.SecretNamespace;
import java.util.Arrays;
import java.util.Base64;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import javax.crypto.SecretKey;
import javax.crypto.spec.SecretKeySpec;

/**
 * 部署环境注入的主密钥（{@code provider = ENV}）：材料来自 systemd EnvironmentFile 等，<b>配置文件里只有占位符、没有默认值</b>
 * （{@code NoSecretDefaultInConfigTest}）。每个命名空间每个环境至多一把 active。
 *
 * <p>材料是 32 字节（AES-256）的 Base64。对象一构造完，原始字符串就不再被持有；{@link #toString()} 只列名字。
 */
public final class InjectedMasterKeyProvider implements MasterKeyProvider {

    private final Map<String, SecretKey> keys = new HashMap<>();
    private final List<Descriptor> declared;

    /**
     * 一把注入的主密钥。
     *
     * @param material Base64 的 32 字节；只在构造时读一次
     */
    public record Entry(String alias, SecretNamespace namespace, String material, boolean active) {

        @Override
        public String toString() {
            return "Entry[alias=" + alias + ", namespace=" + namespace + ", active=" + active + "]";
        }
    }

    public InjectedMasterKeyProvider(List<Entry> entries) {
        Set<SecretNamespace> activeSeen = new HashSet<>();
        for (Entry e : entries) {
            if (e.alias() == null || e.alias().isBlank() || e.namespace() == null) {
                throw new IllegalArgumentException("master key alias and namespace are required");
            }
            byte[] raw;
            try {
                raw = Base64.getDecoder().decode(e.material() == null ? "" : e.material().trim());
            } catch (IllegalArgumentException bad) {
                // 不把材料拼进消息：它可能只差一个字符就是真密钥
                throw new IllegalArgumentException("master key " + e.alias() + " is not valid Base64");
            }
            if (raw.length != 32) {
                throw new IllegalArgumentException("master key " + e.alias() + " must be 32 bytes, got " + raw.length);
            }
            if (keys.put(e.alias(), new SecretKeySpec(raw, "AES")) != null) {
                throw new IllegalArgumentException("duplicate master key alias " + e.alias());
            }
            Arrays.fill(raw, (byte) 0);
            if (e.active() && !activeSeen.add(e.namespace())) {
                throw new IllegalArgumentException("more than one active master key for " + e.namespace());
            }
        }
        this.declared = entries.stream().map(e -> new Descriptor(e.alias(), e.namespace(), e.active())).toList();
    }

    @Override
    public String providerType() {
        return "ENV";
    }

    @Override
    public List<Descriptor> declared() {
        return declared;
    }

    @Override
    public boolean has(String alias) {
        return keys.containsKey(alias);
    }

    @Override
    public byte[] wrap(String alias, byte[] dataKey, byte[] aad) {
        return EnvelopeCipher.encrypt(key(alias), dataKey, aad);
    }

    @Override
    public byte[] unwrap(String alias, byte[] wrapped, byte[] aad) {
        return EnvelopeCipher.decrypt(key(alias), wrapped, aad);
    }

    private SecretKey key(String alias) {
        SecretKey k = keys.get(alias);
        if (k == null) {
            throw new SecretException(SecretException.Reason.NO_MASTER_KEY, "master key " + alias + " is not loaded");
        }
        return k;
    }

    @Override
    public String toString() {
        return "InjectedMasterKeyProvider" + declared;
    }
}
