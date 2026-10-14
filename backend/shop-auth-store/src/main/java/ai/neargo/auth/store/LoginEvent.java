package ai.neargo.auth.store;

/** 登录审计里记的是哪一类事。 */
public enum LoginEvent {
    LOGIN,
    LOGOUT,
    LOGIN_FAILED,
    /** 被踢（停用、强制下线、归属变更），带 {@link RevokeReason} 作为 reason */
    REVOKED,
    /** 令牌有效但用户表里查不到 —— 数据不一致，**必须看得见** */
    ORPHAN_SESSION
}
