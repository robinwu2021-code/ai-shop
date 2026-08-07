import * as React from "react";
import { cn } from "@/lib/utils";

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
