-- 消息模板种子（TDD-触达中心界面优化 §2.5）。
--
-- msg_template 建表于 V20，字段完全对得上（模板号 / 通道 / 正文 / 服务商模板号 / 启停），
-- 但**建表之后一行数据都没写过** —— 于是运营端「消息模板」列表一直是空的，
-- 而模拟发送也无从展示「这条发出去长什么样」。
--
-- 本轮把五条通道的模板落进来，用途有两个：
--   1) 模拟发送抽屉按通道读它，展示模板正文 + 可填参数 + 实时预览；
--   2) 运营能在「站内信模板与推送任务」里停用某条模板（既有能力，此前没有数据可停）。
--
-- ⚠️ channel 取值沿用 sys_notify_log 的四个常量 + INAPP：
-- V20 的列注释里写的是 SUBSCRIBE/PUSH/INBOX 那套旧叫法，而代码里从来用的是
-- SMS/MAIL/WXSUB/PUSH（SysNotifyLog 的常量）。**以代码为准**，
-- 两套名字并存的话，按通道筛模板会筛出空列表。
--
-- ⚠️ 正文里的 {xxx} 是**展示与预览用的占位**，不是发送时的模板引擎：
-- 短信真正发出去用的是阿里云那边报备的模板（provider_template_id），
-- 这里的 content 只是让运营在界面上看得见「会发出什么」。两者要人工保持一致 ——
-- 不一致的后果是预览与实际不符，所以改报备模板时也要改这里。
--
-- 可重入（WHERE NOT EXISTS）：迁移重跑、本地库切分支都会让它再执行一次。

INSERT INTO msg_template (template_no, name, channel, content, provider_template_id, enabled, created_at, updated_at)
SELECT 'TPL_SMS_OTP', '验证码', 'SMS',
       '【数智邻购】您的验证码是 {code}，5 分钟内有效，请勿泄露。',
       'SMS_474945291', 1, NOW(), NOW() FROM DUAL
 WHERE NOT EXISTS (SELECT 1 FROM msg_template x WHERE x.template_no='TPL_SMS_OTP');

INSERT INTO msg_template (template_no, name, channel, content, provider_template_id, enabled, created_at, updated_at)
SELECT 'TPL_MAIL_TEST', '通道联通测试', 'MAIL',
       '{subject}\n\n{body}',
       NULL, 1, NOW(), NOW() FROM DUAL
 WHERE NOT EXISTS (SELECT 1 FROM msg_template x WHERE x.template_no='TPL_MAIL_TEST');

INSERT INTO msg_template (template_no, name, channel, content, provider_template_id, enabled, created_at, updated_at)
SELECT 'TPL_WX_ARRIVED', '到货通知', 'WXSUB',
       '您有 {number1} 件包裹已到自提点 · {thing2}',
       NULL, 1, NOW(), NOW() FROM DUAL
 WHERE NOT EXISTS (SELECT 1 FROM msg_template x WHERE x.template_no='TPL_WX_ARRIVED');

INSERT INTO msg_template (template_no, name, channel, content, provider_template_id, enabled, created_at, updated_at)
SELECT 'TPL_WX_REFUNDED', '退款通知', 'WXSUB',
       '退款 {amount1} 已处理 · {thing2}',
       NULL, 1, NOW(), NOW() FROM DUAL
 WHERE NOT EXISTS (SELECT 1 FROM msg_template x WHERE x.template_no='TPL_WX_REFUNDED');

INSERT INTO msg_template (template_no, name, channel, content, provider_template_id, enabled, created_at, updated_at)
SELECT 'TPL_PUSH_TEST', '通用推送', 'PUSH',
       '{subject}\n{body}',
       NULL, 1, NOW(), NOW() FROM DUAL
 WHERE NOT EXISTS (SELECT 1 FROM msg_template x WHERE x.template_no='TPL_PUSH_TEST');

INSERT INTO msg_template (template_no, name, channel, content, provider_template_id, enabled, created_at, updated_at)
SELECT 'TPL_INAPP_TEST', '站内信', 'INAPP',
       '{subject}\n{body}',
       NULL, 1, NOW(), NOW() FROM DUAL
 WHERE NOT EXISTS (SELECT 1 FROM msg_template x WHERE x.template_no='TPL_INAPP_TEST');
