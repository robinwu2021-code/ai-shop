-- 「立即执行」需要一列，因为**运营端与 worker 之间不通信**。
--
-- 运营页面直读 job 库、不调 worker：worker 挂了的时候页面仍要显示
-- 「最后一次跑是 2 小时前」—— 那正是最需要看的时刻。若页面向 worker 要数据，
-- worker 一挂页面就是空白，等于把最关键的那次故障变成了盲区。
--
-- 代价是「立即执行」也要经过库：运营点按钮 → 写 trigger_requested_at，
-- worker 下一轮轮询看到它比 last_triggered_at 新，就跑一次并把后者推上去。
-- 最长一个轮询周期（默认 30 秒）后动，运维上与「立刻」没有区别。
--
-- **用两列而不是一个布尔标志**：布尔标志要「跑完清掉」，而清掉那一步失败或进程被杀，
-- 这个任务就会每轮都跑一次，直到有人发现。两个时间戳比大小是幂等的 ——
-- 重复读到同一个请求不会重复执行，而漏掉一次请求也只是那次点击没生效。

ALTER TABLE job_definition ADD COLUMN trigger_requested_at DATETIME NULL;
ALTER TABLE job_definition ADD COLUMN last_triggered_at DATETIME NULL;
