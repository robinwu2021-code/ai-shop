-- 进销存独立服务 · 系统凭证（TDD-进销存独立服务与多业态 §2.4，M8 T8.3）
--
-- 商城是多商家的宿主：每个商家一本账，逐个签凭证要在商城里再造一套密钥存取。
-- 改为给系统签一把「系统凭证」（owner_id 为空），调用时以请求头 X-Inventory-Owner-Ref 指定本系统的哪个业主；
-- 服务只放行同系统、且写入方已翻到独立服务的业主。业主凭证（owner_id 非空）照旧绑定一个业主、不看请求头。

ALTER TABLE inv_open_credential MODIFY owner_id VARCHAR(32) NULL COMMENT '业主凭证：只能动这一个业主；空 = 系统凭证（按请求头指定本系统的业主）';
