package ai.neargo.shop.user.entity;

import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Getter;
import lombok.Setter;

import java.time.LocalDateTime;

/** Verified operator identity belongs to a session, independently of permanent account credentials. */
@Getter
@Setter
@TableName("usr_mp_session")
public class UsrMpSession {
    @TableId
    private String tokenHash;
    private String userNo;
    private String openid;
    private LocalDateTime createdAt;
}
