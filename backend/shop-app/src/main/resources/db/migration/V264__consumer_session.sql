-- C 端会话与登录日志。
--
-- **按端分三条迁移，不合并。** 现在没有区别；将来把某一端搬去独立库时，
-- 「只把这一端的迁移拿过去跑」才成立。合成一条的话，拆库那天要先做一次拆迁移。
--
-- 会话表只有八列，它只回答一件事：这个令牌属于谁、还有效吗。
-- 刻意不存 nickname / roles / perms / tenant_no / scope —— 那些变动频率高于会话生命期
-- （会话活 30 天，角色可能今天改），存进来就有第二个真源，
-- 而过期的那一份不会报错，只会让人拥有他昨天的权限。身份由用户表现读现算。
--
-- 也不存 realm：表本身就是按端分的，再存一列等于允许
-- 「运营端的行出现在 C 端表里」这种状态存在。
--
-- token_hash 是 SHA-256 的十六进制，明文令牌永不入库 —— 库被拖走 ≠ 会话被拿走。
-- 用 SHA-256 而不是 bcrypt：令牌是 128 位随机串不是密码，没有字典攻击面；
-- 上 KDF 等于给每一个请求加几十毫秒。
--
-- revoked_at 是软撤销，不物理删：删了就分不清「没这行」和「被踢了」，
-- 而「我为什么突然被登出」是运营最常问的问题之一。

CREATE TABLE IF NOT EXISTS usr_session
(
    id            BIGINT      NOT NULL AUTO_INCREMENT,
    token_hash    CHAR(64)    NOT NULL,
    user_no       VARCHAR(32) NOT NULL,
    issued_at     DATETIME    NOT NULL,
    expires_at    DATETIME    NOT NULL,
    last_seen_at  DATETIME,
    revoked_at    DATETIME,
    revoke_reason VARCHAR(32),
    PRIMARY KEY (id),
    UNIQUE KEY uk_usr_session_token (token_hash),
    KEY idx_usr_session_user (user_no, revoked_at),
    KEY idx_usr_session_expires (expires_at),
    KEY idx_usr_session_revoked (revoked_at)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='C端会话。只存令牌与主体，身份由 usr_account 现读';

-- idx_*_revoked 是撤销轮询用的：每 N 秒读一次「上次之后新撤销的」，
-- 只剔那几条的本地缓存，不清空整个缓存 —— 清空会让踢一个人时所有在线用户
-- 的下一次请求一起回源，把一次撤销放大成库上的尖峰。

CREATE TABLE IF NOT EXISTS usr_login_log
(
    id         BIGINT       NOT NULL AUTO_INCREMENT,
    at         DATETIME     NOT NULL,
    event      VARCHAR(24)  NOT NULL,
    user_no    VARCHAR(32),
    success    TINYINT      NOT NULL DEFAULT 1,
    reason     VARCHAR(64),
    client_ip  VARCHAR(64),
    user_agent VARCHAR(255),
    PRIMARY KEY (id),
    KEY idx_usr_login_user (user_no, at),
    KEY idx_usr_login_at (at)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='C端登录审计。保留 90 天。IP/UA 记这里不记会话表';

-- 这张表**不是控制平面**：要做「失败 N 次锁定」，计数单独放（现有 RateLimiter 就是这么做的）。
-- 审计可以丢可以异步可以采样，控制平面不行；合在一起等于让安全策略依赖一条允许丢失的写入。
