import { Plus } from 'lucide-react'
import { useState } from 'react'
import { Button } from '@/components/ui/button'
import { Dialog } from '@/components/ui/dialog'
import { Field, FormMessage, Input } from '@/components/ui/input'
import { Table, Th, Tr } from '@/components/ui/table'
import { can, useMe } from '@/features/auth/session'
import { useWarehouses } from '@/features/warehouses/api'
import { errorMessage } from '@/lib/errors'
import { t } from '@/lib/i18n'
import { usePutawayRuleAction, usePutawayRules, type PutawayRule } from './putawayRules'

/** /app/putaway-rules: tell the system where goods go (fixed bin or zone), by product, category or for everything. */
export function PutawayRulesPage() {
  const me = useMe()
  const manage = can(me.data, 'tasks:assign')
  const warehouses = useWarehouses()
  const [chosen, setChosen] = useState('')
  const warehouseId = chosen || warehouses.data?.items[0]?.id
  const rules = usePutawayRules(warehouseId)
  const action = usePutawayRuleAction()
  const [editing, setEditing] = useState<PutawayRule | 'new' | null>(null)
  return (
    <div className="grid gap-4">
      <div className="flex flex-wrap items-end justify-between gap-3">
        <div>
          <h1 className="text-2xl font-semibold">{t('prules.title')}</h1>
          <p className="max-w-3xl text-sm text-muted-foreground">{t('prules.hint')}</p>
        </div>
        <div className="flex flex-wrap gap-2">
          {(warehouses.data?.items.length ?? 0) > 1 && (
            <select aria-label={t('po.col.warehouse')} className="h-10 rounded-md border border-input bg-background px-3" value={warehouseId ?? ''}
              onChange={(e) => setChosen(e.target.value)}>
              {warehouses.data?.items.map((w) => <option key={w.id} value={w.id}>{w.code} · {w.name}</option>)}
            </select>
          )}
          {manage && warehouseId && <Button onClick={() => setEditing('new')}><Plus /> {t('prules.new')}</Button>}
        </div>
      </div>
      {rules.isError && <FormMessage tone="error">{errorMessage(rules.error)}</FormMessage>}
      {action.isError && !editing && <FormMessage tone="error">{errorMessage(action.error)}</FormMessage>}
      <Table>
        <thead>
          <tr><Th className="text-right">{t('prules.col.priority')}</Th><Th>{t('prules.col.appliesTo')}</Th><Th>{t('prules.col.goesTo')}</Th>
            <Th className="w-1"><span className="sr-only">{t('users.col.actions')}</span></Th></tr>
        </thead>
        <tbody>
          {rules.data?.map((r) => (
            <Tr key={r.id}>
              <td className="text-right tabular-nums">{r.priority}</td>
              <td>{r.appliesTo === 'PRODUCT' ? <><span className="font-mono text-sm font-semibold">{r.sku}</span> {r.productName}</>
                : r.appliesTo === 'CATEGORY' ? t('prules.category', { c: r.category ?? '' })
                : r.appliesTo === 'CLIENT' ? t('prules.client', { c: r.clientCode ?? '' }) : t('prules.all')}
                {!r.active && <span className="ml-1 text-xs text-muted-foreground">{t('refill.off')}</span>}</td>
              <td>{r.binCode ? t('prules.toBin', { bin: r.binCode }) : t(r.onlyZone ? 'prules.onlyZone' : 'prules.toZone', { zone: r.zoneCode ?? '' })}</td>
              <td className="whitespace-nowrap">{manage && <Button size="sm" variant="ghost" onClick={() => setEditing(r)}>{t('refill.edit')}</Button>}</td>
            </Tr>
          ))}
          {rules.data && rules.data.length === 0 && <tr><td colSpan={4} className="py-8 text-center text-muted-foreground">{t('prules.empty')}</td></tr>}
        </tbody>
      </Table>
      {editing === 'new' && warehouseId && <NewRuleDialog warehouseId={warehouseId} onClose={() => setEditing(null)} />}
      {editing && editing !== 'new' && <EditRuleDialog rule={editing} onClose={() => setEditing(null)} />}
    </div>
  )
}

const PRIORITY = /^\d{1,3}$/

function NewRuleDialog({ warehouseId, onClose }: { warehouseId: string; onClose: () => void }) {
  const action = usePutawayRuleAction()
  const [to, setTo] = useState<'PRODUCT' | 'CATEGORY' | 'CLIENT' | 'ALL'>('PRODUCT')
  const [what, setWhat] = useState('')
  const [dest, setDest] = useState<'BIN' | 'ZONE'>('ZONE')
  const [where, setWhere] = useState('')
  const [only, setOnly] = useState(false)
  const [priority, setPriority] = useState('100')
  const valid = PRIORITY.test(priority.trim()) && where.trim() !== '' && (to === 'ALL' || what.trim() !== '')
  return (
    <Dialog open onOpenChange={(o) => !o && onClose()} title={t('prules.new')} description={t('prules.dialogHint')}>
      <form className="grid gap-3" onSubmit={(e) => {
        e.preventDefault()
        if (!valid) return
        action.mutate({ kind: 'create', rule: { warehouseId, priority: Number(priority), onlyZone: dest === 'ZONE' && only,
          ...(to === 'PRODUCT' ? { sku: what.trim() } : to === 'CATEGORY' ? { category: what.trim() }
            : to === 'CLIENT' ? { clientCode: what.trim().toUpperCase() } : {}),
          ...(dest === 'BIN' ? { binCode: where.trim() } : { zoneCode: where.trim() }) } }, { onSuccess: onClose })
      }}>
        {action.isError && <FormMessage tone="error">{errorMessage(action.error)}</FormMessage>}
        <Field label={t('prules.col.appliesTo')}>{(p) => (
          <select {...p} className="h-10 rounded-md border border-input bg-background px-3" value={to} onChange={(e) => setTo(e.target.value as typeof to)}>
            <option value="PRODUCT">{t('prules.toProduct')}</option>
            <option value="CATEGORY">{t('prules.toCategory')}</option>
            <option value="CLIENT">{t('prules.toClient')}</option>
            <option value="ALL">{t('prules.all')}</option>
          </select>
        )}</Field>
        {to !== 'ALL' && (
          <Field label={to === 'PRODUCT' ? t('refill.product') : to === 'CLIENT' ? t('prules.clientCode') : t('prules.categoryName')}>
            {(p) => <Input {...p} className={to === 'CATEGORY' ? '' : 'font-mono'} maxLength={to === 'PRODUCT' ? 64 : to === 'CLIENT' ? 20 : 40} value={what} onChange={(e) => setWhat(e.target.value)} />}
          </Field>
        )}
        <Field label={t('prules.col.goesTo')}>{(p) => (
          <select {...p} className="h-10 rounded-md border border-input bg-background px-3" value={dest} onChange={(e) => setDest(e.target.value as typeof dest)}>
            <option value="ZONE">{t('prules.aZone')}</option>
            <option value="BIN">{t('prules.aBin')}</option>
          </select>
        )}</Field>
        <Field label={dest === 'BIN' ? t('prules.binCode') : t('prules.zoneCode')}>
          {(p) => <Input {...p} className="font-mono" maxLength={dest === 'BIN' ? 60 : 10} value={where} onChange={(e) => setWhere(e.target.value)} />}
        </Field>
        {dest === 'ZONE' && (
          <label className="flex items-center gap-2 text-sm"><input type="checkbox" checked={only} onChange={(e) => setOnly(e.target.checked)} /> {t('prules.onlyField')}</label>
        )}
        <Field label={t('prules.col.priority')}>
          {(p) => <Input {...p} inputMode="numeric" value={priority} onChange={(e) => setPriority(e.target.value)} />}
        </Field>
        <div className="flex justify-end gap-2">
          <Button type="button" variant="outline" onClick={onClose}>{t('common.cancel')}</Button>
          <Button type="submit" disabled={!valid || action.isPending}>{t('common.save')}</Button>
        </div>
      </form>
    </Dialog>
  )
}

function EditRuleDialog({ rule, onClose }: { rule: PutawayRule; onClose: () => void }) {
  const action = usePutawayRuleAction()
  const [priority, setPriority] = useState(String(rule.priority))
  const [only, setOnly] = useState(rule.onlyZone)
  const [active, setActive] = useState(rule.active)
  const done = { onSuccess: onClose }
  return (
    <Dialog open onOpenChange={(o) => !o && onClose()} title={t('prules.editTitle')} description={t('prules.editHint')}>
      <form className="grid gap-3" onSubmit={(e) => {
        e.preventDefault()
        if (PRIORITY.test(priority.trim())) action.mutate({ kind: 'update', rule, priority: Number(priority), onlyZone: only, active }, done)
      }}>
        {action.isError && <FormMessage tone="error">{errorMessage(action.error)}</FormMessage>}
        <Field label={t('prules.col.priority')}>
          {(p) => <Input {...p} inputMode="numeric" value={priority} onChange={(e) => setPriority(e.target.value)} />}
        </Field>
        {rule.zoneCode && (
          <label className="flex items-center gap-2 text-sm"><input type="checkbox" checked={only} onChange={(e) => setOnly(e.target.checked)} /> {t('prules.onlyField')}</label>
        )}
        <label className="flex items-center gap-2 text-sm"><input type="checkbox" checked={active} onChange={(e) => setActive(e.target.checked)} /> {t('refill.active')}</label>
        <div className="flex justify-between gap-2">
          <Button type="button" variant="outline" className="text-destructive" disabled={action.isPending}
            onClick={() => action.mutate({ kind: 'delete', id: rule.id }, done)}>{t('prules.delete')}</Button>
          <div className="flex gap-2">
            <Button type="button" variant="outline" onClick={onClose}>{t('common.cancel')}</Button>
            <Button type="submit" disabled={!PRIORITY.test(priority.trim()) || action.isPending}>{t('common.save')}</Button>
          </div>
        </div>
      </form>
    </Dialog>
  )
}
