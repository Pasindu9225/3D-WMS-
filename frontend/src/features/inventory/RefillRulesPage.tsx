import { Plus, RefreshCw } from 'lucide-react'
import { useState } from 'react'
import { Button } from '@/components/ui/button'
import { Dialog } from '@/components/ui/dialog'
import { Field, FormMessage, Input } from '@/components/ui/input'
import { Table, Th, Tr } from '@/components/ui/table'
import { can, useMe } from '@/features/auth/session'
import { useWarehouses } from '@/features/warehouses/api'
import { errorMessage } from '@/lib/errors'
import { t } from '@/lib/i18n'
import { useRefillRules, useRuleAction, type RefillRule } from './refill'

const QTY = /^\d{1,7}(\.\d{1,3})?$/
const num = (n: number) => Number(Number(n).toFixed(3)).toLocaleString('en')

/** /app/refill: keep pick-face bins between min and max; tasks are made when a pick drops a bin below min. */
export function RefillRulesPage() {
  const me = useMe()
  const manage = can(me.data, 'tasks:assign')
  const warehouses = useWarehouses()
  const [chosen, setChosen] = useState('')
  const warehouseId = chosen || warehouses.data?.items[0]?.id
  const rules = useRefillRules(warehouseId)
  const action = useRuleAction()
  const [editing, setEditing] = useState<RefillRule | 'new' | null>(null)
  const [made, setMade] = useState<number | null>(null)
  return (
    <div className="grid gap-4">
      <div className="flex flex-wrap items-end justify-between gap-3">
        <div>
          <h1 className="text-2xl font-semibold">{t('refill.title')}</h1>
          <p className="text-sm text-muted-foreground">{t('refill.hint')}</p>
        </div>
        <div className="flex flex-wrap gap-2">
          {(warehouses.data?.items.length ?? 0) > 1 && (
            <select aria-label={t('po.col.warehouse')} className="h-10 rounded-md border border-input bg-background px-3" value={warehouseId ?? ''}
              onChange={(e) => setChosen(e.target.value)}>
              {warehouses.data?.items.map((w) => <option key={w.id} value={w.id}>{w.code} · {w.name}</option>)}
            </select>
          )}
          {manage && warehouseId && (
            <>
              <Button variant="outline" disabled={action.isPending}
                onClick={() => action.mutate({ kind: 'check', warehouseId }, { onSuccess: (r) => setMade((r as { made: number }).made) })}>
                <RefreshCw /> {t('refill.checkNow')}
              </Button>
              <Button onClick={() => setEditing('new')}><Plus /> {t('refill.new')}</Button>
            </>
          )}
        </div>
      </div>
      {rules.isError && <FormMessage tone="error">{errorMessage(rules.error)}</FormMessage>}
      {action.isError && !editing && <FormMessage tone="error">{errorMessage(action.error)}</FormMessage>}
      {made !== null && <FormMessage tone="success">{t('refill.made', { n: made })}</FormMessage>}
      <Table>
        <thead>
          <tr><Th>{t('refill.col.bin')}</Th><Th>{t('stock.col.product')}</Th><Th className="text-right">{t('refill.col.minMax')}</Th>
            <Th className="text-right">{t('stock.col.onHand')}</Th><Th>{t('refill.col.task')}</Th><Th className="w-1"><span className="sr-only">{t('users.col.actions')}</span></Th></tr>
        </thead>
        <tbody>
          {rules.data?.map((r) => (
            <Tr key={r.id}>
              <td className="font-mono text-sm">{r.binCode}</td>
              <td><span className="font-mono text-sm font-semibold">{r.sku}</span> {r.productName}
                {!r.active && <span className="ml-1 text-xs text-muted-foreground">{t('refill.off')}</span>}</td>
              <td className="text-right tabular-nums">{num(r.minQty)} – {num(r.maxQty)} <span className="text-xs text-muted-foreground">{r.baseUom}</span></td>
              <td className={`text-right tabular-nums ${r.onHand < r.minQty ? 'font-medium text-destructive' : ''}`}>{num(r.onHand)}</td>
              <td className="font-mono text-sm">{r.openTask ?? '—'}</td>
              <td>{manage && <Button size="sm" variant="ghost" onClick={() => setEditing(r)}>{t('refill.edit')}</Button>}</td>
            </Tr>
          ))}
          {rules.data && rules.data.length === 0 && <tr><td colSpan={6} className="py-8 text-center text-muted-foreground">{t('refill.empty')}</td></tr>}
        </tbody>
      </Table>
      {editing && warehouseId && <RuleDialog warehouseId={warehouseId} rule={editing === 'new' ? null : editing} onClose={() => setEditing(null)} />}
    </div>
  )
}

function RuleDialog({ warehouseId, rule, onClose }: { warehouseId: string; rule: RefillRule | null; onClose: () => void }) {
  const action = useRuleAction()
  const [bin, setBin] = useState('')
  const [sku, setSku] = useState('')
  const [min, setMin] = useState(rule ? String(rule.minQty) : '')
  const [max, setMax] = useState(rule ? String(rule.maxQty) : '')
  const [active, setActive] = useState(rule?.active ?? true)
  const valid = QTY.test(min.trim()) && QTY.test(max.trim()) && Number(max) > Number(min) && (rule !== null || (bin.trim() !== '' && sku.trim() !== ''))
  return (
    <Dialog open onOpenChange={(o) => !o && onClose()} title={rule ? t('refill.editTitle', { bin: rule.binCode, sku: rule.sku }) : t('refill.new')}
      description={t('refill.dialogHint')}>
      <form className="grid gap-3" onSubmit={(e) => {
        e.preventDefault()
        if (!valid) return
        const done = { onSuccess: onClose }
        if (rule) action.mutate({ kind: 'update', id: rule.id, version: rule.version, minQty: Number(min), maxQty: Number(max), active }, done)
        else action.mutate({ kind: 'create', warehouseId, binCode: bin.trim(), sku: sku.trim(), minQty: Number(min), maxQty: Number(max) }, done)
      }}>
        {action.isError && <FormMessage tone="error">{errorMessage(action.error)}</FormMessage>}
        {!rule && (
          <>
            <Field label={t('refill.pickBin')}>{(p) => <Input {...p} autoFocus className="font-mono" maxLength={60} value={bin} onChange={(e) => setBin(e.target.value)} />}</Field>
            <Field label={t('refill.product')}>{(p) => <Input {...p} className="font-mono" maxLength={64} value={sku} onChange={(e) => setSku(e.target.value)} />}</Field>
          </>
        )}
        <div className="grid grid-cols-2 gap-2">
          <Field label={t('refill.min')}>{(p) => <Input {...p} inputMode="decimal" value={min} onChange={(e) => setMin(e.target.value)} />}</Field>
          <Field label={t('refill.max')}>{(p) => <Input {...p} inputMode="decimal" value={max} onChange={(e) => setMax(e.target.value)} />}</Field>
        </div>
        {rule && (
          <label className="flex items-center gap-2 text-sm"><input type="checkbox" checked={active} onChange={(e) => setActive(e.target.checked)} /> {t('refill.active')}</label>
        )}
        <div className="flex justify-end gap-2">
          <Button type="button" variant="outline" onClick={onClose}>{t('common.cancel')}</Button>
          <Button type="submit" disabled={!valid || action.isPending}>{t('common.save')}</Button>
        </div>
      </form>
    </Dialog>
  )
}
