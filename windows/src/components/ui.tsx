import { useEffect, type JSX, type ReactNode } from 'react';

export function cx(...values: Array<string | false | null | undefined>): string {
  return values.filter(Boolean).join(' ');
}

/* --------------------------------- 按钮 --------------------------------- */

type ButtonVariant = 'primary' | 'ghost' | 'outline' | 'danger' | 'subtle';

const BUTTON_STYLES: Record<ButtonVariant, string> = {
  primary:
    'bg-violet-600 text-white hover:bg-violet-500 border border-violet-500/60 disabled:bg-violet-900/60 disabled:text-violet-300/60',
  ghost: 'bg-transparent text-slate-300 hover:bg-slate-800/70 border border-transparent',
  outline:
    'bg-slate-900/60 text-slate-200 hover:bg-slate-800 border border-slate-700 hover:border-slate-600',
  danger: 'bg-rose-700/90 text-white hover:bg-rose-600 border border-rose-500/60',
  subtle: 'bg-slate-800 text-slate-200 hover:bg-slate-700 border border-slate-700',
};

export function Button({
  children,
  onClick,
  variant = 'outline',
  disabled,
  title,
  size = 'md',
  className,
}: {
  children: ReactNode;
  onClick?: () => void;
  variant?: ButtonVariant;
  disabled?: boolean;
  title?: string;
  size?: 'sm' | 'md';
  className?: string;
}): JSX.Element {
  return (
    <button
      type="button"
      title={title}
      disabled={disabled}
      onClick={onClick}
      className={cx(
        'inline-flex items-center justify-center gap-1 rounded-md font-medium transition-colors disabled:cursor-not-allowed',
        size === 'sm' ? 'px-2 py-1 text-[12px]' : 'px-3 py-1.5',
        BUTTON_STYLES[variant],
        className,
      )}
    >
      {children}
    </button>
  );
}

/* --------------------------------- 容器 --------------------------------- */

export function Card({
  children,
  className,
  title,
  actions,
}: {
  children: ReactNode;
  className?: string;
  title?: ReactNode;
  actions?: ReactNode;
}): JSX.Element {
  return (
    <section
      className={cx(
        'rounded-lg border border-slate-800 bg-slate-900/40 shadow-sm shadow-black/30',
        className,
      )}
    >
      {(title || actions) && (
        <header className="flex items-center justify-between gap-3 border-b border-slate-800 px-3 py-2">
          <div className="text-[13px] font-semibold text-slate-200">{title}</div>
          <div className="flex items-center gap-2">{actions}</div>
        </header>
      )}
      <div className="p-3">{children}</div>
    </section>
  );
}

export function Badge({
  children,
  tone = 'slate',
}: {
  children: ReactNode;
  tone?: 'slate' | 'violet' | 'emerald' | 'amber' | 'rose' | 'sky';
}): JSX.Element {
  const tones: Record<string, string> = {
    slate: 'bg-slate-800 text-slate-300 border-slate-700',
    violet: 'bg-violet-950/70 text-violet-300 border-violet-800',
    emerald: 'bg-emerald-950/70 text-emerald-300 border-emerald-800',
    amber: 'bg-amber-950/70 text-amber-300 border-amber-800',
    rose: 'bg-rose-950/70 text-rose-300 border-rose-800',
    sky: 'bg-sky-950/70 text-sky-300 border-sky-800',
  };
  return (
    <span
      className={cx(
        'inline-flex items-center rounded border px-1.5 py-0.5 text-[11px] font-medium leading-none',
        tones[tone],
      )}
    >
      {children}
    </span>
  );
}

/* --------------------------------- 表单 --------------------------------- */

export function Field({
  label,
  hint,
  children,
  className,
}: {
  label: string;
  hint?: string;
  children: ReactNode;
  className?: string;
}): JSX.Element {
  return (
    <label className={cx('flex flex-col gap-1', className)}>
      <span className="text-[11px] font-medium uppercase tracking-wide text-slate-500">{label}</span>
      {children}
      {hint && <span className="text-[11px] text-slate-500">{hint}</span>}
    </label>
  );
}

const INPUT_CLASS =
  'w-full rounded-md border border-slate-700 bg-slate-950/70 px-2 py-1.5 text-slate-100 outline-none transition-colors placeholder:text-slate-600 focus:border-violet-500 focus:ring-1 focus:ring-violet-500/30 disabled:opacity-50';

export function TextInput({
  value,
  onChange,
  placeholder,
  disabled,
  type = 'text',
  className,
}: {
  value: string | number;
  onChange: (value: string) => void;
  placeholder?: string;
  disabled?: boolean;
  type?: string;
  className?: string;
}): JSX.Element {
  return (
    <input
      type={type}
      value={value}
      disabled={disabled}
      placeholder={placeholder}
      onChange={(event) => onChange(event.target.value)}
      className={cx(INPUT_CLASS, className)}
    />
  );
}

export function TextArea({
  value,
  onChange,
  rows = 6,
  placeholder,
  mono,
}: {
  value: string;
  onChange: (value: string) => void;
  rows?: number;
  placeholder?: string;
  mono?: boolean;
}): JSX.Element {
  return (
    <textarea
      value={value}
      rows={rows}
      placeholder={placeholder}
      onChange={(event) => onChange(event.target.value)}
      className={cx(INPUT_CLASS, 'resize-y leading-relaxed', mono && 'font-mono text-[12px]')}
    />
  );
}

export function Select({
  value,
  onChange,
  options,
  disabled,
  className,
}: {
  value: string;
  onChange: (value: string) => void;
  options: Array<{ value: string; label: string }>;
  disabled?: boolean;
  className?: string;
}): JSX.Element {
  return (
    <select
      value={value}
      disabled={disabled}
      onChange={(event) => onChange(event.target.value)}
      className={cx(INPUT_CLASS, 'cursor-pointer', className)}
    >
      {options.map((option) => (
        <option key={option.value} value={option.value}>
          {option.label}
        </option>
      ))}
    </select>
  );
}

export function Toggle({
  checked,
  onChange,
  label,
}: {
  checked: boolean;
  onChange: (value: boolean) => void;
  label: string;
}): JSX.Element {
  return (
    <button
      type="button"
      onClick={() => onChange(!checked)}
      className="inline-flex items-center gap-2 rounded-md border border-slate-700 bg-slate-950/60 px-2 py-1.5 text-left"
    >
      <span
        className={cx(
          'relative h-4 w-7 rounded-full transition-colors',
          checked ? 'bg-violet-500' : 'bg-slate-700',
        )}
      >
        <span
          className={cx(
            'absolute top-0.5 h-3 w-3 rounded-full bg-white transition-all',
            checked ? 'left-3.5' : 'left-0.5',
          )}
        />
      </span>
      <span className="text-[12px] text-slate-300">{label}</span>
    </button>
  );
}

/* --------------------------------- 反馈 --------------------------------- */

export function ProgressBar({ percent }: { percent: number }): JSX.Element {
  const clamped = Math.max(0, Math.min(100, percent));
  return (
    <div className="h-1.5 w-full overflow-hidden rounded-full bg-slate-800">
      <div
        className="h-full rounded-full bg-gradient-to-r from-violet-500 to-sky-400 transition-all"
        style={{ width: `${clamped}%` }}
      />
    </div>
  );
}

export function EmptyState({
  title,
  description,
  action,
}: {
  title: string;
  description?: ReactNode;
  action?: ReactNode;
}): JSX.Element {
  return (
    <div className="flex h-full min-h-[240px] flex-col items-center justify-center gap-2 rounded-lg border border-dashed border-slate-800 p-8 text-center">
      <div className="text-[14px] font-semibold text-slate-300">{title}</div>
      {description && <div className="max-w-md text-[12px] leading-relaxed text-slate-500">{description}</div>}
      {action}
    </div>
  );
}

export function Modal({
  open,
  title,
  onClose,
  children,
  footer,
  width = 'max-w-2xl',
}: {
  open: boolean;
  title: string;
  onClose: () => void;
  children: ReactNode;
  footer?: ReactNode;
  width?: string;
}): JSX.Element | null {
  useEffect(() => {
    if (!open) return;
    const handler = (event: KeyboardEvent): void => {
      if (event.key === 'Escape') onClose();
    };
    window.addEventListener('keydown', handler);
    return () => window.removeEventListener('keydown', handler);
  }, [open, onClose]);

  if (!open) return null;
  return (
    <div className="fixed inset-0 z-50 flex items-center justify-center bg-black/70 p-6">
      <div
        className={cx(
          'flex max-h-[85vh] w-full flex-col overflow-hidden rounded-xl border border-slate-700 bg-slate-900 shadow-2xl',
          width,
        )}
      >
        <header className="flex items-center justify-between border-b border-slate-800 px-4 py-3">
          <h2 className="text-[14px] font-semibold text-slate-100">{title}</h2>
          <Button variant="ghost" size="sm" onClick={onClose}>
            关闭
          </Button>
        </header>
        <div className="flex-1 overflow-auto p-4">{children}</div>
        {footer && (
          <footer className="flex items-center justify-end gap-2 border-t border-slate-800 px-4 py-3">
            {footer}
          </footer>
        )}
      </div>
    </div>
  );
}
