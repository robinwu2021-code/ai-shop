package ai.neargo.auth.store;

/**
 * 为什么被撤销。**运营最常问的问题是「我为什么突然被登出」**，这一列就是答案。
 *
 * <p>刻意**不含**「改角色」「改数据域」：会话里不存这些，改了下一个请求就生效，
 * 不需要踢人。撤销收窄回它本来的语义，一个接口被用来实现四五件事时，
 * 总有人会漏调其中一处。
 */
public enum RevokeReason {
    /** 用户主动登出 */
    LOGOUT,
    /** 管理员强制下线 */
    FORCED_OUT,
    /** 账号被停用 */
    DISABLED,
    /** 归属变更（员工被移出门店/实体） */
    MEMBERSHIP_CHANGED,
    /** 定时清理 */
    PURGED
}
