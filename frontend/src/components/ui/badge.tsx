import type { ComponentProps } from 'react'
import { cn } from '@/lib/utils'

const tones = {
  neutral: 'bg-secondary text-secondary-foreground',
  good: 'bg-emerald-500/15 text-emerald-700 dark:text-emerald-300',
  warn: 'bg-accent/25 text-accent-foreground dark:text-accent',
  bad: 'bg-destructive/15 text-destructive',
}

export function Badge({ tone = 'neutral', className, ...props }: ComponentProps<'span'> & { tone?: keyof typeof tones }) {
  return (
    <span
      className={cn('inline-flex items-center rounded-full px-2 py-0.5 text-xs font-medium whitespace-nowrap', tones[tone], className)}
      {...props}
    />
  )
}
