package ai.neargo.shop.inventory.entity;

import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Getter;
import lombok.Setter;

import java.time.LocalDateTime;

/** Open API 凭证：服务端到服务端，不复用给人用的令牌 */
@Getter
@Setter
@TableName("inv_open_credential")
public class InvOpenCredential extends InvMutableEntity {

    private String credentialId;

    /** 这把钥匙只能看这一个业主的货 */
    private String ownerId;

    private String appKey;

    /** bcrypt。**不存明文** */
    private String appSecretHash;

    /** 给人看的：这把钥匙给了谁 */
    private String name;

    /** 逗号分隔：read / stock:sync */
    private String scopes;

    /** ACTIVE / REVOKED。**吊销不删行** —— 谁在什么时候被吊销要查得到 */
    private String status;

    /** 空 = 不过期 */
    private LocalDateTime expiresAt;

    /** 发现「这把钥匙半年没人用了」的唯一依据 */
    private LocalDateTime lastUsedAt;

}
