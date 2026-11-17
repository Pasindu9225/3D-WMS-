import { Plus } from 'lucide-react'
import { useState } from 'react'
import { Link, useNavigate } from 'react-router'
import { Badge } from '@/components/ui/badge'
import { Button } from '@/components/ui/button'
import { Dialog } from '@/components/ui/dialog'
import { Field, FormMessage, Input } from '@/components/ui/input'
import { Table, Th, Tr } from '@/components/ui/table'
import { can, useMe } from '@/features/auth/session'
import { useWarehouses } from '@/features/warehouses/api'
import { errorMessage } from '@/lib/errors'
import { t, type MessageKey } from '@/lib/i18n'
import { useCounts, useCreateCount, type CountScope, type CountStatus } from './counts'

const SELECT = 'h-10 w-full rounded-md border border-input bg-background px-3'
const TONE: Record<CountStatus, 'neutral' | 'warn' | 'good' | 'bad'> = { OPEN: 'neutral', REVIEW: 'warn', APPROVED: 'good', CANCELLED: 'bad' }

export function CountStatusBadge({ status }: { status: CountStatus }) {
  return <Badge tone={TONE[status]}>{t(`count.status.${status}` as MessageKey)}</Badge>
}

/** /app/counts: cycle counts of my warehouses; managers start new ones. */
export function CountsPage() {
  const me = useMe()
  const manage = can(me.data, 'counts:approve')
  const [done, setDone] = useState(false)
  const counts = useCounts(done)
  const [creating, setCreating] = useState(false)
  return (
    <div className="grid gap-4">
      <div className="flex flex-wrap items-center justify-between gap-3">
        <div>
          <h1 className="text-2xl font-semibold">{t('counts.title')}</h1>
          <p className="text-sm text-muted-foreground">{t('counts.hint')}</p>
        </div>
        {manage && <Button onClick={() => setCreating(true)}><Plus /> {t('counts.new')}</Button>}
      </div>
      <div role="radiogroup" aria-label={t('counts.show')} className="flex gap-1 text-sm">
        {[false, true].map((d) => (
          <button key={String(d)} type="button" role="radio" aria-checked={done === d} onClick={() => setDone(d)}
            className={`rounded px-3 py-1.5 font-medium ${done === d ? 'bg-primary text-primary-foreground' : 'hover:bg-secondary'}`}>
            {t(d ? 'counts.closed' : 'counts.open')}
          </button>
        ))}
      </div>
      {counts.isError && <FormMessage tone="error">{errorMessage(counts.error)}</FormMessage>}
      <Table>
        <thead>
          <tr><Th>{t('counts.col.number')}</Th><Th>{t('po.col.warehouse')}</Th><Th>{t('counts.col.scope')}</Th>
            <Th className="text-right">{t('counts.col.counted')}</Th><Th>{t('po.col.status')}</Th></tr>
        </thead>
        <tbody>
          {counts.data?.items.map((c) => (
            <Tr key={c.id}>
              <td className="font-mono font-medium">{manage ? <Link className="hover:underline" to={`/app/counts/${c.id}`}>{c.number}</Link>
                : <Link className="hover:underline" to={`/app/count/${c.id}`}>{c.number}</Link>}</td>
              <td className="font-mono text-sm">{c.warehouseCode}</td>
              <td>{t(`count.scope.${c.scope}` as MessageKey, { v: c.scopeValue ?? '' })}</td>
              <td className="text-right tabular-nums">{c.counted} / {c.lines}</td>
              <td><CountStatusBadge status={c.status} /></td>
            </Tr>
          ))}
          {counts.data && counts.data.items.length === 0 && (
            <tr><td colSpan={5} className="py-8 text-center text-muted-foreground">{t('counts.empty')}</td></tr>
          )}
        </tbody>
      </Table>
      {creating && <NewCountDialog onClose={() => setCreating(false)} />}
    </div>
  )
}

function NewCountDialog({ onClose }: { onClose: () => void }) {
  const warehouses = useWarehouses()
  const create = useCreateCount()
  const navigate = useNavigate()
  const [warehouse, setWarehouse] = useState('')
  const [scope, setScope] = useState<CountScope>('ZONE')
  const [value, setValue] = useState('')
  const warehouseId = warehouse || warehouses.data?.items[0]?.id || ''
  const valid = warehouseId !== '' && (scope === 'ALL' || value.trim() !== '')
  return (
    <Dialog open onOpenChange={(o) => !o && onClose()} title={t('counts.new')} description={t('counts.newHint')}>
      <form className="grid gap-3" onSubmit={(e) => {
        e.preventDefault()
        if (valid) {
          create.mutate({ warehouseId, scope, scopeValue: scope === 'ALL' ? undefined : value.trim() },
            { onSuccess: (plan) => navigate(`/app/counts/${plan.id}`) })
        }
      }}>
        {create.isError && <FormMessage tone="error">{errorMessage(create.error)}</FormMessage>}
        <Field label={t('po.col.warehouse')}>
          {(p) => (
            <select {...p} className={SELECT} value={warehouseId} onChange={(e) => setWarehouse(e.target.value)}>
              {warehouses.data?.items.map((w) => <option key={w.id} value={w.id}>{w.code} · {w.name}</option>)}
            </select>
          )}
        </Field>
        <Field label={t('counts.col.scope')}>
          {(p) => (
            <select {...p} className={SELECT} value={scope} onChange={(e) => {
              setScope(e.target.value as CountScope)
              setValue('')
            }}>
              <option value="ZONE">{t('counts.byZone')}</option>
              <option value="ABC">{t('counts.byAbc')}</option>
              <option value="ALL">{t('counts.all')}</option>
            </select>
          )}
        </Field>
        {scope === 'ZONE' && (
          <Field label={t('counts.zoneCode')}>
            {(p) => <Input {...p} className="font-mono uppercase" maxLength={20} placeholder="A" value={value} onChange={(e) => setValue(e.target.value)} />}
          </Field>
        )}
        {scope === 'ABC' && (
          <Field label={t('counts.abcClass')}>
            {(p) => (
              <select {...p} className={SELECT} value={value} onChange={(e) => setValue(e.target.value)}>
                <option value="">{t('po.pick')}</option>
                <option value="A">A</option>
                <option value="B">B</option>
                <option value="C">C</option>
              </select>
            )}
          </Field>
        )}
        <div className="flex justify-end gap-2">
          <Button type="button" variant="outline" onClick={onClose}>{t('common.cancel')}</Button>
          <Button type="submit" disabled={!valid || create.isPending}>{t('counts.start')}</Button>
        </div>
      </form>
    </Dialog>
  )
}
