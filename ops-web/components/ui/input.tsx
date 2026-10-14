import * as React from "react";
import { cn } from "@/lib/utils";
import { SEARCH_DEBOUNCE_MS } from "@/lib/constants";

/** 错误态环。`Textarea` 与 `FormDrawer` 共用同一份写法，改这里两处一起变。 */
export const ERR_RING = "ring-2 ring-[var(--destructive)]";

export interface InputProps extends React.InputHTMLAttributes<HTMLInputElement> {
  /**
   * 校验未通过。**错误态要沉在组件里**：此前每个调用处自己拼 ring 类，
   * 于是 FormDrawer 之外的手写表单干脆就没有错误态。
   * 同时落 `aria-invalid`，读屏用户才知道错的是哪一项。
   */
  invalid?: boolean;
}

export const Input = React.forwardRef<HTMLInputElement, InputProps>(
  ({ className, invalid, ...props }, ref) => (
    <input
      ref={ref}
      aria-invalid={invalid || undefined}
      className={cn(
        // 控件档 6px，与 Button / Select / FilterSelect 同档。
        // 搜索框不再特殊化成药丸：工具栏一行里的控件形状必须一致。
        "flex h-[var(--ctl-h)] w-full rounded-field bg-secondary px-3.5 py-1 txt-body transition-colors placeholder:text-muted-foreground focus-ring disabled:cursor-not-allowed disabled:opacity-50",
        invalid && ERR_RING,
        className,
      )}
      {...props}
    />
  ),
);
Input.displayName = "Input";

export interface SelectProps extends React.SelectHTMLAttributes<HTMLSelectElement> {
  invalid?: boolean;
}

export const Select = React.forwardRef<HTMLSelectElement, SelectProps>(
  ({ className, children, invalid, ...props }, ref) => (
    <select
      ref={ref}
      aria-invalid={invalid || undefined}
      className={cn(
        // disabled 视觉此前只有 Input 有、Select 没有 —— 禁用下拉与可用下拉长得一模一样，
        // 这是真缺陷不是风格差异：看不出能不能改，就会一直点。
        "h-[var(--ctl-h)] rounded-field bg-secondary px-3.5 txt-body transition-colors focus-ring disabled:cursor-not-allowed disabled:opacity-50",
        invalid && ERR_RING,
        className,
      )}
      {...props}
    >
      {children}
    </select>
  ),
);
Select.displayName = "Select";

/**
 * 受控输入 + 防抖对外通知。**筛选类输入框都该走它。**
 *
 * 为什么必须防抖：值一旦进了 `queryKey`，每敲一个字符就是一次请求 ——
 * 「商家」两个字在本地实测 300ms 内连发数次，接了真后端就是几倍的无谓查询，
 * 而且回包乱序时列表会闪。本地 state 立刻回显（输入不卡顿），只把**对外的通知**押后。
 *
 * 这段逻辑此前只长在 `Toolbar` 私有的 `SearchBox` 里，于是页面往工具栏里塞一个裸
 * `<Input>` 当筛选时，既没有防抖、也没有筛选回显 —— 两个缺陷一起来。
 *
 * 返回的 `push(v, immediate)`：`immediate` 用于回车与清空
 * —— 用户已经明确表达「就现在」，不该再等 300ms。
 */
export function useDebouncedPush(value: string, onChange: (v: string) => void) {
  const [local, setLocal] = React.useState(value);
  const timer = React.useRef<ReturnType<typeof setTimeout> | null>(null);

  // 外部改了值（切 tab 清空、点 chip 的 ×）要同步回来，否则框里还留着旧词
  React.useEffect(() => { setLocal(value); }, [value]);
  React.useEffect(() => () => { if (timer.current) clearTimeout(timer.current); }, []);

  const push = React.useCallback((v: string, immediate = false) => {
    setLocal(v);
    if (timer.current) clearTimeout(timer.current);
    if (immediate) { onChange(v); return; }
    timer.current = setTimeout(() => onChange(v), SEARCH_DEBOUNCE_MS);
  }, [onChange]);

  return { local, push };
}
