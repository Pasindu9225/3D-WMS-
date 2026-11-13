import { useId, type ComponentProps, type ReactNode } from 'react'
import { cn } from '@/lib/utils'

export function Input({ className, ...props }: ComponentProps<'input'>) {
  return (
    <input
      className={cn(
        'h-10 w-full min-w-0 rounded-md border border-input bg-background px-3 text-sm shadow-xs',
        'placeholder:text-muted-foreground focus-visible:outline-2 focus-visible:outline-ring',
        'aria-invalid:border-destructive disabled:opacity-50',
        className,
      )}
      {...props}
    />
  )
}

/** Label + control + error, wired for screen readers (aria-invalid, aria-describedby). */
export function Field({
  label,
  error,
  children,
}: {
  label: string
  error?: string
  children: (props: { id: string; 'aria-invalid'?: true; 'aria-describedby'?: string }) => ReactNode
}) {
  const id = useId()
  const errorId = `${id}-error`
  return (
    <div className="grid gap-1.5">
      <label htmlFor={id} className="text-sm font-medium">
        {label}
      </label>
      {children({ id, 'aria-invalid': error ? true : undefined, 'aria-describedby': error ? errorId : undefined })}
      {error && (
        <p id={errorId} className="text-sm text-destructive">
          {error}
        </p>
      )}
    </div>
  )
}

/** Form-level message: an error (role=alert) or a success note. */
export function FormMessage({ tone, children }: { tone: 'error' | 'success'; children: ReactNode }) {
  return (
    <p
      role={tone === 'error' ? 'alert' : 'status'}
      className={cn(
        'rounded-md border px-3 py-2 text-sm',
        tone === 'error' ? 'border-destructive/40 bg-destructive/10 text-destructive' : 'border-primary/30 bg-primary/10',
      )}
    >
      {children}
    </p>
  )
}
