import { keepPreviousData, useQuery } from '@tanstack/react-query'
import { useState } from 'react'
import { Badge } from '@/components/ui/badge'
import { FormMessage, Input } from '@/components/ui/input'
import { Pager, Table, Th, Tr } from '@/components/ui/table'
import type { Page } from '@/features/users/api'
import { api } from '@/lib/api'
import { errorMessage, label } from '@/lib/errors'
import { t } from '@/lib/i18n'

interface AuditRow {
  at: string
  action: string
  entity: string
  entityId: string | null
  user: { id: string; fullName: string } | null
  ip: string | null
  correlationId: string | null
  before: string | null
  after: string | null
}

const SIZE = 25
const ENTITIES = ['app_user', 'user_session', 'warehouse', 'rack_template', 'bin', 'product', 'partner', 'purchase_order', 'receipt', 'stock_adjustment']

export function AuditPage() {
  const [entity, setEntity] = useState('')
  const [from, setFrom] = useState('')
  const [to, setTo] = useState('')
  const [page, setPage] = useState(0)
  const params = new URLSearchParams({ page: String(page), size: String(SIZE) })
  if (entity) params.set('entity', entity)
  // Date inputs are local days; send the exact instants for the start of `from` and the end of `to`.
  if (from) params.set('from', new Date(`${from}T00:00:00`).toISOString())
  if (to) params.set('to', new Date(new Date(`${to}T00:00:00`).getTime() + 86_400_000).toISOString())
  const logs = useQuery({
    queryKey: ['audit', params.toString()],
    queryFn: () => api<Page<AuditRow>>(`/audit-logs?${params}`),
    placeholderData: keepPreviousData,
  })
  const data = logs.data
  const reset = <T,>(set: (v: T) => void) => (v: T) => {
    set(v)
    setPage(0)
  }

  return (
    <div className="grid gap-4">
      <div className="space-y-1">
        <h1 className="text-2xl font-semibold">{t('audit.title')}</h1>
        <p className="text-muted-foreground">{t('audit.body')}</p>
      </div>

      <div className="flex flex-wrap items-end gap-3">
        <label className="grid gap-1 text-sm">
          <span className="font-medium">{t('audit.filter.entity')}</span>
          <select className="h-10 rounded-md border border-input bg-background px-3" value={entity}
            onChange={(e) => reset(setEntity)(e.target.value)}>
            <option value="">{t('audit.filter.all')}</option>
            {ENTITIES.map((e) => <option key={e} value={e}>{label('audit.entity', e)}</option>)}
          </select>
        </label>
        <label className="grid gap-1 text-sm">
          <span className="font-medium">{t('audit.filter.from')}</span>
          <Input type="date" value={from} max={to || undefined} onChange={(e) => reset(setFrom)(e.target.value)} />
        </label>
        <label className="grid gap-1 text-sm">
          <span className="font-medium">{t('audit.filter.to')}</span>
          <Input type="date" value={to} min={from || undefined} onChange={(e) => reset(setTo)(e.target.value)} />
        </label>
      </div>

      {logs.isError && <FormMessage tone="error">{errorMessage(logs.error)}</FormMessage>}

      <Table>
        <thead>
          <tr>
            <Th>{t('audit.col.time')}</Th>
            <Th>{t('audit.col.user')}</Th>
            <Th>{t('audit.col.action')}</Th>
            <Th className="hidden md:table-cell">{t('audit.col.record')}</Th>
          </tr>
        </thead>
        <tbody>
          {data?.items.map((row, i) => (
            <Tr key={`${row.at}-${i}`}>
              <td className="whitespace-nowrap tabular-nums text-muted-foreground">
                {new Date(row.at).toLocaleString(undefined, { dateStyle: 'medium', timeStyle: 'short' })}
              </td>
              <td>{row.user?.fullName ?? <span className="text-muted-foreground">{t('audit.system')}</span>}</td>
              <td>
                <Badge tone={row.action === 'TOKEN_REUSE' ? 'bad' : 'neutral'}>{label('action', row.action)}</Badge>
                {(row.before || row.after) && (
                  <details className="mt-1.5 text-xs">
                    <summary className="cursor-pointer text-muted-foreground">{t('audit.details')}</summary>
                    <div className="mt-1 grid gap-2 sm:grid-cols-2">
                      <Snapshot title={t('audit.before')} json={row.before} />
                      <Snapshot title={t('audit.after')} json={row.after} />
                    </div>
                  </details>
                )}
              </td>
              <td className="hidden font-mono text-xs text-muted-foreground md:table-cell">
                {label('audit.entity', row.entity)} {row.entityId}
              </td>
            </Tr>
          ))}
          {data && data.items.length === 0 && (
            <tr><td colSpan={4} className="py-8 text-center text-muted-foreground">{t('audit.empty')}</td></tr>
          )}
        </tbody>
      </Table>

      {data && data.total > SIZE && (
        <Pager page={data.page} size={data.size} total={data.total} onPage={setPage}
          labels={{
            previous: t('common.previous'),
            next: t('common.next'),
            range: t('common.pageOf', { from: data.page * data.size + 1, to: data.page * data.size + data.items.length, total: data.total }),
          }} />
      )}
    </div>
  )
}

function Snapshot({ title, json }: { title: string; json: string | null }) {
  if (!json) return null
  let text = json
  try {
    text = JSON.stringify(JSON.parse(json), null, 2)
  } catch {
    // show as stored
  }
  return (
    <div className="min-w-0">
      <div className="mb-0.5 font-medium">{title}</div>
      <pre className="overflow-x-auto rounded bg-secondary p-2 text-[11px] leading-relaxed">{text}</pre>
    </div>
  )
}
