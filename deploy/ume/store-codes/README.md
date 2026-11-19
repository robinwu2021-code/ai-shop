# 门店英文代码批量导入

依据：[TDD-门店代码与商户渠道展示](../../../docs/technical/TDD-门店代码与商户渠道展示.md) A3。代码只用英文（用户 2026-11-19），写进 `mch_store.slug`；门店同步会把它挂到该门店启用中的整店收款码上，形成 `tpay.neargo.ai/s/<code>`。门店还没有整店收款码时，代码先写入，开码后链接自动生效。

清单（UTF-8 CSV，表头必须是 `storeNo,code`）：

```
storeNo,code
STUME12811472160039108608,ume-cafe
STUME12380164319477760000,chuangke-pay
```

先校验（不写），确认没有 INVALID / DUPLICATE 后再写：

```bash
NEARSHOP_BASE=https://nearshop.umeshop.ai/ops-shop NEARSHOP_TOKEN=... python3 -I deploy/ume/store-codes/import.py codes.csv
NEARSHOP_BASE=https://nearshop.umeshop.ai/ops-shop NEARSHOP_TOKEN=... python3 -I deploy/ume/store-codes/import.py codes.csv --apply --json result.json
```

- 令牌只从环境变量读，不进报告；运营账号要有 `store:page:audit`（运营改门店基础信息的权限）。
- 每行一个结论：`OK`、`INVALID`（不合规或保留词）、`DUPLICATE`（清单内重复）、`FAILED`（接口拒绝，如代码已被其他门店占用）。有失败时退出码为 1，其余行照常写入。
- 规则与 ai-shop `StoreSlugs` 同口径：小写字母、数字、连字符，3–32 位，首尾非连字符，不能是保留词。
- 测试：`python3 -I -m unittest discover -s deploy/ume/store-codes -p 'test_*.py'`
