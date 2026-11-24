# 台湾 v2 发票 PDF 的服务器保留

服务器主机 `192.168.3.80` 的 `/data0/app/ai-shop-v2/print-private` 持久化挂到容器的 `/data/print-private`。
打印中心导出时登记 `expires_at`，默认保留 7 天。

用 `javac --release 17 InvoicePdfRetention.java` 编译后，将类文件和 `run.sh` 放到宿主机
`/data0/app/ai-shop-v2/runtime/invoice-pdf-retention`。先以 `--check` 检查，再配置主机用户 `ume` 的 crontab。

```cron
30 3 * * * /bin/bash /data0/app/ai-shop-v2/runtime/invoice-pdf-retention/run.sh --apply >> /data0/app/ai-shop-v2/runtime/invoice-pdf-retention/retention.log 2>&1 # ai-shop-v2-invoice-pdf-retention
```

任务读取数据库中的到期时间和打印件归属，只清理本机已过期的发票证明联与发票明细 PDF 文件。
停用时，从 `ume` 的 crontab 删除带 `ai-shop-v2-invoice-pdf-retention` 标记的这一行。
