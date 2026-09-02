"use client";

// 支付通道设置与费率（后端 sys_pay_channel + sys_pay_channel_rate）。
//
// **与隔壁「分档费率与服务费」是两笔钱**：那边配的是平台向商家收的佣金，
// 这边配的是通道向我们收的手续费。两者都进 stl_bill，但来源与谈判对象完全不同 ——
// 放在同一个 tab 里会让人以为改一个就够了。
//
// 同一条规矩：**只增不改**。结算按下单时刻的版本算，调整不影响已生成的账。
import { useState } from "react";
import { useMutation, useQuery, useQueryClient } from "@tanstack/react-query";
import { api } from "@/lib/api";
import { notify } from "@/lib/notify";
import { fill } from "@/lib/use-copy";
import { fmtTime } from "@/lib/utils";
import type { PayChannelSetting, PayChannelRateVersion } from "@/lib/types";
import { Badge } from "@/components/ui/badge";
import { Button } from "@/components/ui/button";
import { ConfigCard } from "@/components/ui/config-card";
import { FilterSelect } from "@/components/ui/filter-select";
import { DataTable, type Column } from "@/components/ui/data-table";
import { Input } from "@/components/ui/input";
import { Label } from "@/components/ui/label";
import { HelpNote } from "@/components/ui/help-note";
import { ReadOnlyNotice } from "@/components/read-only-notice";
import type { FinanceCopy } from "./copy";

/** 万分比 → 百分比。财务说的是「0.38%」不是「38 个万分点」。 */
const pct = (bp: number) => `${(bp / 100).toFixed(2)}%`;

/** `*` 是存储值，不该直接显示给人看。 */
const anyLabel = (v: string) => (v === "*" ? "—" : v);

/**
 * 可用市场：库里存的是 JSON 串（`["CN"]`），**不能原样端上来**。
 *
 * 两件事都要说对：
 *   · 空 / null = **全部市场**，不是「没有市场」—— 后端 `marketAllowed` 就是这么判的。
 *     显示成「—」会让运营以为这个通道谁都用不了，而它其实是全开的。
 *   · 有值时按市场名显示，`CN` 这种码只有做过这块的人认得。
 */
function marketsLabel(raw: string | null | undefined, names: Map<string, string>, all: string): string {
  if (!raw || !raw.trim()) return all;
  let codes: string[];
  try {
    const v = JSON.parse(raw);
    codes = Array.isArray(v) ? v.map(String) : [String(v)];
  } catch {
    // 不是 JSON 就按逗号分隔兜一手：解析失败时显示原串也好过显示「全部」
    codes = raw.split(",").map((x) => x.trim()).filter(Boolean);
  }
  if (!codes.length) return all;
  return codes.map((code) => names.get(code) ?? code).join("、");
}

export function PayChannelTab({ c, canEdit }: { c: FinanceCopy; canEdit: boolean }) {
  const qc = useQueryClient();
  const channels = useQuery({ queryKey: ["pay-channels"], queryFn: () => api.listPayChannels() });
  // 市场名的真源在系统设置那一页，这里只借来把 `CN` 翻成人话；取不到就回落成码
  const markets = useQuery({ queryKey: ["markets"], queryFn: () => api.listMarkets() });
  const marketNames = new Map((markets.data ?? []).map((m) => [m.code, m.name]));

  const [channel, setChannel] = useState("");
  const [payMethod, setPayMethod] = useState("");
  const [legalForm, setLegalForm] = useState("");
  const [rate, setRate] = useState("");
  const [minFee, setMinFee] = useState("");
  const [from, setFrom] = useState("");
  const [remark, setRemark] = useState("");

  const rows = channels.data ?? [];
  const current = channel || rows[0]?.payChannel || "";

  const addRate = useMutation({
    mutationFn: () =>
      api.addPayChannelRate(current, {
        payMethod: payMethod || undefined,
        legalForm: legalForm || undefined,
        rateBp: Number(rate),
        minFeeMinor: minFee ? Number(minFee) : undefined,
        // 留空 = 立即生效；填了就是预约生效
        effectiveFrom: from ? Date.parse(from) : undefined,
        remark: remark || undefined,
      }),
    onSuccess: () => {
      qc.invalidateQueries({ queryKey: ["pay-channels"] });
      setRate("");
      setMinFee("");
      setFrom("");
      setRemark("");
      notify.success(c.pcToastAdded);
    },
  });

  const toggle = useMutation({
    mutationFn: (row: PayChannelSetting) =>
      api.updatePayChannel(row.payChannel, { enabled: !row.enabled }),
    onSuccess: () => {
      qc.invalidateQueries({ queryKey: ["pay-channels"] });
      notify.success(c.pcToastUpdated);
    },
  });

  /**
   * 改渠道的可用区域。
   *
   * <b>为什么是一个 prompt 而不是多选框</b>：市场清单是运营在系统设置里维护的，
   * 这里做成多选就要跟着那份清单同步 —— 而两处不同步的表现是
   * 「新开的市场在这儿选不到」，运营会以为是后端没支持。
   * 先用一个能看到当前值、能直接改的输入框把链路打通，
   * 等市场变成主数据表（见 TDD-支付域-数据库设计（目标态））再换成多选。
   *
   * 空字符串 = **所有市场可用**，与后端 marketAllowed 的语义一致；
   * 提示语里写明这一点，否则运营会以为清空等于「谁都不能用」。
   */
  const editMarkets = useMutation({
    mutationFn: ({ row, markets }: { row: PayChannelSetting; markets: string }) =>
      api.updatePayChannel(row.payChannel, { markets }),
    onSuccess: () => {
      qc.invalidateQueries({ queryKey: ["pay-channels"] });
      notify.success(c.pcToastUpdated);
    },
  });

  function promptMarkets(row: PayChannelSetting) {
    const current = row.markets ?? "";
    const next = window.prompt(c.pcMarketsPrompt, current);
    if (next === null || next === current) return;
    editMarkets.mutate({ row, markets: next });
  }

  const columns: Column<PayChannelSetting>[] = [
    {
      header: c.pcColChannel,
      /*
       * **测试渠道要一眼能认出来。**它是一条真的通道记录（进件、下单、
       * 回调、结算都按真通道走，只有与银行之间那一段是假的），
       * 所以它在这张表里长得和微信支付一模一样 ——
       * 而运营一旦把它开给真实商家，那些订单的钱一分都收不到，
       * 且系统每一步都「成功」。
       */
      cell: (r) => (r.payChannel === "TEST" ? (
        <span className="flex items-center gap-2">
          {r.name || r.payChannel}
          <Badge tone="warning">{c.pcTestChannelTag}</Badge>
        </span>
      ) : (r.name || r.payChannel)),
    },
    {
      header: c.pcColState,
      cell: (r) => (r.enabled
        ? <Badge tone="success">{c.pcEnabled}</Badge>
        : <Badge tone="muted">{c.pcDisabled}</Badge>),
    },
    {
      header: c.pcColMarkets,
      cell: (r) => (canEdit ? (
        <button
          type="button"
          className="underline underline-offset-2 hover:opacity-70"
          onClick={() => promptMarkets(r)}
          title={c.pcMarketsEditHint}
        >
          {marketsLabel(r.markets, marketNames, c.pcAllMarkets)}
        </button>
      ) : marketsLabel(r.markets, marketNames, c.pcAllMarkets)),
    },
    { header: c.pcColCurrency, cell: (r) => r.currency ?? "—" },
    { header: c.pcColSettleCycle, cell: (r) => r.settleCycle ?? "—" },
    {
      header: c.pcColRate,
      numeric: true,
      /*
       * **「未配置」不能显示成 0%。** 后端在没有任何版本时返回 null，
       * 而 0% 会让人以为「这个通道不收手续费」——那是一句假话，
       * 真实情况是结算时根本取不到版本。
       */
      cell: (r) => (r.currentRate
        ? pct(r.currentRate.rateBp)
        : <Badge tone="warning">{c.pcNoRate}</Badge>),
    },
    {
      header: "",
      /*
       * 按钮上写的是**要做的事**，不是当前状态 —— 隔壁那一列已经在说状态了。
       * 此前这里复用了状态词，于是「启用中」的那一行按钮写着「已停用」，
       * 读起来像是在陈述，而它其实是个动作。
       */
      cell: (r) => (canEdit ? (
        <Button variant="ghost" onClick={() => toggle.mutate(r)} disabled={toggle.isPending}>
          {r.enabled ? c.pcActionDisable : c.pcActionEnable}
        </Button>
      ) : null),
    },
  ];

  const picked = rows.find((r) => r.payChannel === current);
  const rateColumns: Column<PayChannelRateVersion>[] = [
    { header: c.pcFieldPayMethod, cell: (r) => anyLabel(r.payMethod) },
    { header: c.pcFieldLegalForm, cell: (r) => anyLabel(r.legalForm) },
    { header: c.frColRate, cell: (r) => pct(r.rateBp), numeric: true },
    {
      header: c.frColEffectiveFrom,
      cell: (r) => (r.effectiveFrom === 0
        ? c.frInitial : fmtTime(new Date(r.effectiveFrom).toISOString())),
    },
    {
      header: c.frColState,
      cell: (r) => (r.effectiveFrom > Date.now()
        ? <Badge tone="warning">{c.frScheduled}</Badge>
        : <Badge tone="success">{c.frActive}</Badge>),
    },
    { header: c.frColRemark, cell: (r) => r.remark ?? "—" },
  ];

  return (
    <div className="space-y-4">
      {!canEdit && (
        <ReadOnlyNotice what={c.rateReadOnlyWhat} perm="finance:rate:update" note={c.rateReadOnlyNote} />
      )}

      <HelpNote>{c.pcNotice}</HelpNote>

      {/*
        **表不进 ConfigCard**：那是 `max-w-2xl` 的表单卡片，7 列塞进 672px 之后
        「当前费率」与那个开关按钮要横向滚动才看得到 —— 而它们正是这张表的重点。
        与本页其它列表一致，表直接放在页面层级。
      */}
      <div>
        {/*
          `error` / `onRetry` / `empty` 与本页其它列表一样都要给：不给的话
          接口 404 或 500 时这里是一张**空表**，看着像「还没有配过通道」——
          而真相是这一页根本没拿到数据。mock 下永远看不到这个差别。
        */}
        <DataTable
          /*
           * `rows` 给 `channels.data` 而不是 `rows`（那个 `?? []`）——
           * DataTable 判骨架用的是 `loading && !rows`，喂 `[]` 进去它就是真值，
           * 于是**取数期间显示的是「还没有配置支付通道」**：一句关于事实的断言，
           * 而那一刻我们其实什么都还不知道。取数慢或接口挂时最容易撞上。
           */
          columns={columns} rows={channels.data} rowKey={(r) => r.payChannel}
          loading={channels.isLoading} error={channels.error}
          onRetry={() => channels.refetch()} empty={c.pcEmpty}
        />
        <p className="mt-2 text-xs text-muted-foreground">{c.pcDisableHint}</p>
        {rows.some((r) => !r.currentRate) && (
          <p className="mt-1 text-xs text-muted-foreground">{c.pcNoRateHint}</p>
        )}
        {picked && !picked.supportsSubsidy && (
          <p className="mt-1 text-xs text-muted-foreground">{c.pcSubsidyNo}</p>
        )}
      </div>

      {canEdit && (
        <ConfigCard title={c.pcAddRateTitle}>
          <div className="grid gap-3 sm:grid-cols-2">
            <div>
              <Label>{c.pcFieldChannel}</Label>
              {/* 用库件而不是裸 select：控件高走 var(--ctl-h)、焦点环由库件给 —— 自己搭的那个两样都没有 */}
              <FilterSelect
                value={current}
                onChange={setChannel}
                options={rows.map((r) => ({ value: r.payChannel, label: r.name || r.payChannel }))}
              />
            </div>
            <div>
              <Label>{c.frFieldRate}</Label>
              <Input value={rate} onChange={(e) => setRate(e.target.value)} inputMode="numeric" />
            </div>
            <div>
              <Label>{c.pcFieldPayMethod}</Label>
              <Input value={payMethod} onChange={(e) => setPayMethod(e.target.value)} placeholder="JSAPI" />
            </div>
            <div>
              <Label>{c.pcFieldLegalForm}</Label>
              <Input value={legalForm} onChange={(e) => setLegalForm(e.target.value)} placeholder="ENTERPRISE" />
            </div>
            <div>
              <Label>{c.pcFieldMinFee}</Label>
              <Input value={minFee} onChange={(e) => setMinFee(e.target.value)} inputMode="numeric" />
            </div>
            <div>
              <Label>{c.frFieldFrom}</Label>
              <Input type="datetime-local" value={from} onChange={(e) => setFrom(e.target.value)} />
            </div>
            <div className="sm:col-span-2">
              <Label>{c.frFieldRemark}</Label>
              <Input value={remark} onChange={(e) => setRemark(e.target.value)} />
            </div>
          </div>
          <p className="mt-2 text-xs text-muted-foreground">{c.pcAnyHint}</p>
          <p className="mt-1 text-xs text-muted-foreground">{c.frFromHint}</p>
          <div className="mt-3">
            <Button onClick={() => addRate.mutate()} disabled={!rate || addRate.isPending}>
              {addRate.isPending ? c.frAdding : c.frAdd}
            </Button>
          </div>
        </ConfigCard>
      )}

      {picked && (
        <ConfigCard title={fill(c.pcHistoryTitle, { ch: picked.name || picked.payChannel, n: picked.rates.length })}>
          <DataTable columns={rateColumns} rows={picked.rates} rowKey={(r) => r.rateNo} />
        </ConfigCard>
      )}
    </div>
  );
}
