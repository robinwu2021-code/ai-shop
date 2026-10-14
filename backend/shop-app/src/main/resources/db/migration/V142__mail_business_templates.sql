-- 两封业务邮件的模板（触达能力矩阵 §2.5.1）。
--
-- 邮件与短信/微信不同：模板是**平台自己定义的业务模板**，没有报备制，
-- 库里这份就是发出去的那份（不是副本）。所以它们本该一开始就在数据里 ——
-- 而此前正文是 Java 字符串拼接（OpsServiceImpl:763 / :867），
-- 改一句话要发版，而需要改文案的时刻（措辞引起误解、要加合规提示）恰恰不该等发版。
--
-- ⚠️ **正文含密**：初始密码与重置码在占位里。`NotifyLogWriter` 本来就不记正文，
-- 模板化不改变这一点 —— 发送记录里仍然只有掩码后的收件人与模板号。
--
-- ⚠️ **停用不会让邮件发不出去**：这两封是账号类邮件，
-- 发不出的后果是「新同事永远登不进来」。所以渲染侧的行为是
-- 「模板缺失或停用 → 回落内置默认文案 + 记 WARN」，见 MailTemplatePortImpl。
-- 页面上也照这个口径说明，不然运营会以为停用能拦住它们。
--
-- 占位与 OpsServiceImpl 传的参数名一一对应，改名要两处一起改。
--
-- ⚠️ **换行必须写成真换行，不能写 `\n`**：MariaDB 把字符串里的反斜杠当转义
-- （`\n` → 真换行），H2 不当（`\n` → 字面两个字符）。写 `\n` 的话，
-- 生产收到的是分行的邮件、测试库里却是一整行 —— 测试绿了也证明不了生产对。
-- 这坑第一次是 OpsForgotPasswordFlowTest 按行抠重置码时暴露的。

INSERT INTO msg_template (template_no, name, channel, content, provider_template_id, enabled, created_at, updated_at)
SELECT 'TPL_MAIL_OPS_INIT_PWD', '运营账号开通', 'MAIL',
       '你好 {realName}，

你的运营端账号已开通。
登录名：{username}
初始密码：{password}

首次登录会要求你立即修改密码。请勿转发本邮件。
',
       NULL, 1, NOW(), NOW() FROM DUAL
 WHERE NOT EXISTS (SELECT 1 FROM msg_template x WHERE x.template_no='TPL_MAIL_OPS_INIT_PWD');

INSERT INTO msg_template (template_no, name, channel, content, provider_template_id, enabled, created_at, updated_at)
SELECT 'TPL_MAIL_OPS_RESET_PWD', '运营密码重置', 'MAIL',
       '你好 {realName}，

有人为你的运营端账号申请了密码重置。
重置码（{ttlMinutes} 分钟内有效，只能用一次）：

    {token}

如果不是你本人操作，忽略本邮件即可，你的密码不会有任何变化。
',
       NULL, 1, NOW(), NOW() FROM DUAL
 WHERE NOT EXISTS (SELECT 1 FROM msg_template x WHERE x.template_no='TPL_MAIL_OPS_RESET_PWD');
