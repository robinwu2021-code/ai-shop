-- 测试号固定验证码白名单（TDD-测试号固定验证码）。
--
-- 这张表里的每一行都是一把**能登进那个手机号账号的钥匙**：命中的号请求验证码时
-- 不发真实短信，码恒为这里配的值。它存在的唯一理由是苹果审核 ——
-- 审核员在美国，收不到中国短信，而 App 的登录是手机号 + 验证码。
--
-- 为什么不用现成的 shop.auth.otp.fixed：那个是**全局**的，一开任何手机号都能用同一个码
-- 登进去，所以 FixedOtpGuard 在「短信通道是真的」时直接拒绝启动 —— 生产正是那个形状，
-- 它在生产上永远不可用，那是有意的设计。本表把作用域从「任意手机号」收窄到「列出的号」。
--
-- 为什么落库而不是配置项：配置项改一次就要重启一次生产，而重启会把所有在线商家踢掉。
-- 落库之后，出事能在运营端当场停用，不用等一次部署。
--
-- **为什么是 usr_ 不是 sys_**：菜单挂在「平台管理 · 系统设置」下，但这是 user 域的数据 ——
-- 读它的是 AuthServiceImpl.sendOtp，而最关键那条护栏要查 usr_identity。
-- 叫 sys_ 的话，代码要么跟着表搬进 platform 域（于是两条跨域依赖、两个新 SPI Port），
-- 要么留在 user 域而 ER 图把它画进「系统」域 —— 两种都是「代码与数据分家」。
-- 参照 OpsBannedWordController 的口径：**代码跟着数据走，菜单位置是另一回事。**
--
-- ⚠️ 护栏在服务层，不在这张表上，读代码的人别只看 DDL：
--   · **拒绝录入已存在账号的手机号** —— 这条最关键。演示账号的用法是「先录白名单、
--     再注册」，录的时候那个号不存在；而要拿别人的店，那个号一定已经存在。
--     有这条，拿到权限码的人也登不进任何现有商家。
--   · 启用中的条目有上限；码长下限 6 位。
--   · 增 / 删 / 启停都写审计。
CREATE TABLE IF NOT EXISTS usr_otp_test_phone
(
    id BIGINT(20) NOT NULL AUTO_INCREMENT,
    phone VARCHAR(32) NOT NULL COMMENT '白名单手机号。命中时不发短信，验证码恒为 code',
    code VARCHAR(16) NOT NULL COMMENT '固定验证码，至少 6 位（与 PWD_MIN_LEN 同档）',
    enabled TINYINT(4) NOT NULL DEFAULT 1 COMMENT '停用即时生效，不等缓存过期、不等重启',
    remark VARCHAR(128) DEFAULT NULL COMMENT '这一条为什么存在。不写清楚，半年后没人敢删也没人敢留',
    tenant_no VARCHAR(32) NOT NULL DEFAULT 'MAIN',
    created_at DATETIME NOT NULL,
    created_by VARCHAR(64) DEFAULT NULL,
    updated_at DATETIME NOT NULL,
    updated_by VARCHAR(64) DEFAULT NULL,
    version BIGINT(20) NOT NULL DEFAULT 0,
    deleted TINYINT(4) NOT NULL DEFAULT 0,
    PRIMARY KEY (id),
    UNIQUE KEY uk_otp_test_phone (phone)
) COMMENT='测试号固定验证码白名单（苹果审核演示账号用）';

-- 系统自带的演示号。**这个组合是公开可猜的**（13800000000 + 123456），
-- 由此有两条硬约束，写在这里是为了让改这张表的人看得见：
--   · 它名下那家店只能放测试数据，不接真实订单、不绑真实收款号；
--   · 别的号要运营端手动录，且受「已存在账号不得录入」拦着。
-- INSERT IGNORE 而不是 INSERT…SELECT…WHERE NOT EXISTS：后者在 MySQL 里没有 FROM 会语法错，
-- 而且那种写法会被表清单生成器静默丢掉（它只认常量 VALUES）。幂等靠上面的唯一键。
INSERT IGNORE INTO usr_otp_test_phone
    (phone, code, enabled, remark, created_at, updated_at, created_by)
VALUES
    ('13800000000', '123456', 1, '苹果审核演示账号（App Store Connect 审核资料里填的就是它）',
     NOW(), NOW(), 'V354');
