import type { ComponentProps } from 'react'
import { Button } from '@/components/ui/button'
import { cn } from '@/lib/utils'

/** Wide tables scroll inside their own box so the page never scrolls sideways on a phone. */
export function Table({ className, ...props }: ComponentProps<'table'>) {
  return (
    <div className="overflow-x-auto rounded-lg border bg-card">
      <table className={cn('w-full text-sm [&_td]:px-3 [&_td]:py-2.5 [&_th]:px-3 [&_th]:py-2', className)} {...props} />
    </div>
  )
}

export function Th({ className, ...props }: ComponentProps<'th'>) {
  return (
    <th
      scope="col"
      className={cn('border-b bg-secondary/50 text-left text-xs font-medium whitespace-nowrap text-muted-foreground', className)}
      {...props}
    />
  )
}

export function Tr({ className, ...props }: ComponentProps<'tr'>) {
  return <tr className={cn('border-b last:border-0', className)} {...props} />
}

export function Pager({
  page,
  size,
  total,
  onPage,
  labels,
}: {
  page: number
  size: number
  total: number
  onPage: (page: number) => void
  labels: { previous: string; next: string; range: string }
}) {
  return (
    <div className="flex items-center justify-between gap-3 text-sm text-muted-foreground">
      <span className="tabular-nums">{labels.range}</span>
      <div className="flex gap-2">
        <Button
          variant="outline"
          size="sm"
          disabled={page === 0}
          onClick={() => onPage(page - 1)}
        >
          {labels.previous}
        </Button>
        <Button
          variant="outline"
          size="sm"
          disabled={(page + 1) * size >= total}
          onClick={() => onPage(page + 1)}
        >
          {labels.next}
        </Button>
      </div>
    </div>
  )
}
