-- 邮件模板的语言维度（触达能力矩阵 G2c）。
--
-- 平台是三语（zh-CN / en / ar），而 msg_template 只有一份正文 ——
-- 英文用户收到的是中文邮件。此前不做是因为「模板怎么存」没定；现在定了：
--
-- **加 lang 列，不按模板号分**（不搞 TPL_MAIL_OPS_INIT_PWD_EN 这种）。
-- 按模板号分不用改键，但运营端通道页的模板列表会**变成三倍长且无法分组** ——
-- 那一页刚做好，一条业务模板在上面出现三次，运营认不出哪三条是同一件事。
-- 加列之后「一条模板 + N 份翻译」在数据上就是它本来的样子。
--
-- ⚠️ **唯一键要先删再建**：原来是 UNIQUE(template_no)，加了语言之后
-- 同一个 template_no 必须能有多行。不删的话第二种语言插不进去，
-- 而报错会指向「模板号重复」——与语言毫无关系，很难联想。
--
-- 存量行一律 zh-CN：它们本来就是中文，这是逐字等价的回填，不改变任何现有行为。

ALTER TABLE msg_template
    ADD COLUMN lang VARCHAR(16) NOT NULL DEFAULT 'zh-CN' COMMENT '语言 zh-CN/en/ar；回落链见 MailTemplatePort';

ALTER TABLE msg_template DROP INDEX uk_msg_template_no;

ALTER TABLE msg_template
    ADD CONSTRAINT uk_msg_template_no_lang UNIQUE (template_no, lang);

-- 密码重置的英文版。**只做这一封**：它的收件人就是发起请求的人，
-- Accept-Language 天然就是他自己的语言。
--
-- 账号开通那封没做 —— 那个请求是**管理员**发的，用他的 Accept-Language
-- 等于按管理员的语言给新同事发信。要做对得先有「按人存的语言」，
-- 而全库现在没有这个字段（矩阵 G2e）。
INSERT INTO msg_template (template_no, name, channel, lang, content, provider_template_id, enabled, created_at, updated_at)
SELECT 'TPL_MAIL_OPS_RESET_PWD', 'Ops password reset', 'MAIL', 'en',
       'Hi {realName},

Someone requested a password reset for your operations account.
Reset code (valid for {ttlMinutes} minutes, single use):

    {token}

If this was not you, just ignore this email — your password stays unchanged.
',
       NULL, 1, NOW(), NOW() FROM DUAL
 WHERE NOT EXISTS (SELECT 1 FROM msg_template x
                    WHERE x.template_no='TPL_MAIL_OPS_RESET_PWD' AND x.lang='en');
