import { Plus } from 'lucide-react'
import { useState } from 'react'
import { Badge } from '@/components/ui/badge'
import { Button } from '@/components/ui/button'
import { Dialog } from '@/components/ui/dialog'
import { Field, FormMessage, Input } from '@/components/ui/input'
import { Table, Th, Tr } from '@/components/ui/table'
import { can, useMe } from '@/features/auth/session'
import { errorMessage } from '@/lib/errors'
import { t } from '@/lib/i18n'
import { lkr, usePlanAction, usePlans, type Plan } from './api'

const MONEY = /^\d{1,9}(\.\d{1,2})?$/

/** /app/admin/plans: what can be sold — price per warehouse per month and the warehouses that come with it. */
export function PlansPage() {
  const me = useMe()
  const manage = can(me.data, 'platform:manage')
  const plans = usePlans()
  const [editing, setEditing] = useState<Plan | 'new' | null>(null)
  return (
    <div className="grid gap-4">
      <div className="flex flex-wrap items-end justify-between gap-3">
        <div>
          <h1 className="text-2xl font-semibold">{t('admin.plans')}</h1>
          <p className="text-sm text-muted-foreground">{t('admin.plansHint')}</p>
        </div>
        {manage && <Button onClick={() => setEditing('new')}><Plus /> {t('admin.newPlan')}</Button>}
      </div>
      {plans.isError && <FormMessage tone="error">{errorMessage(plans.error)}</FormMessage>}
      <Table>
        <thead>
          <tr><Th>{t('admin.col.plan')}</Th><Th className="text-right">{t('admin.col.price')}</Th><Th className="text-right">{t('admin.col.included')}</Th>
            <Th className="text-right">{t('admin.col.companies')}</Th><Th className="w-1"><span className="sr-only">{t('users.col.actions')}</span></Th></tr>
        </thead>
        <tbody>
          {plans.data?.map((p) => (
            <Tr key={p.code}>
              <td><span className="font-medium">{p.name}</span> <span className="font-mono text-xs text-muted-foreground">{p.code}</span>
                {!p.active && <Badge className="ml-2">{t('admin.planOff')}</Badge>}</td>
              <td className="text-right tabular-nums">{lkr(p.pricePerWarehouseMinor)}</td>
              <td className="text-right tabular-nums">{p.defaultMaxWarehouses}</td>
              <td className="text-right tabular-nums">{p.companies}</td>
              <td>{manage && <Button size="sm" variant="ghost" onClick={() => setEditing(p)}>{t('refill.edit')}</Button>}</td>
            </Tr>
          ))}
        </tbody>
      </Table>
      {editing && <PlanDialog plan={editing === 'new' ? null : editing} onClose={() => setEditing(null)} />}
    </div>
  )
}

function PlanDialog({ plan, onClose }: { plan: Plan | null; onClose: () => void }) {
  const action = usePlanAction()
  const [code, setCode] = useState('')
  const [name, setName] = useState(plan?.name ?? '')
  const [price, setPrice] = useState(plan ? (plan.pricePerWarehouseMinor / 100).toFixed(2) : '')
  const [max, setMax] = useState(plan ? String(plan.defaultMaxWarehouses) : '1')
  const [active, setActive] = useState(plan?.active ?? true)
  const valid = name.trim() !== '' && MONEY.test(price.trim()) && /^\d{1,4}$/.test(max) && Number(max) >= 1 && Number(max) <= 1000
    && (plan !== null || /^[A-Za-z0-9_-]{2,30}$/.test(code.trim()))
  return (
    <Dialog open onOpenChange={(o) => !o && onClose()} title={plan ? t('admin.editPlan', { code: plan.code }) : t('admin.newPlan')} description={t('admin.planHint')}>
      <form className="grid gap-3" onSubmit={(e) => {
        e.preventDefault()
        if (!valid) return
        const minor = Math.round(Number(price) * 100)
        if (plan) action.mutate({ kind: 'update', plan, name: name.trim(), pricePerWarehouseMinor: minor, defaultMaxWarehouses: Number(max), active }, { onSuccess: onClose })
        else action.mutate({ kind: 'create', code: code.trim(), name: name.trim(), pricePerWarehouseMinor: minor, defaultMaxWarehouses: Number(max) }, { onSuccess: onClose })
      }}>
        {action.isError && <FormMessage tone="error">{errorMessage(action.error)}</FormMessage>}
        {!plan && <Field label={t('admin.planCode')}>{(p) => <Input {...p} autoFocus className="font-mono uppercase" maxLength={30} value={code} onChange={(e) => setCode(e.target.value)} />}</Field>}
        <Field label={t('admin.planName')}>{(p) => <Input {...p} maxLength={80} value={name} onChange={(e) => setName(e.target.value)} />}</Field>
        <div className="grid grid-cols-2 gap-2">
          <Field label={t('admin.pricePerWarehouse')}>{(p) => <Input {...p} inputMode="decimal" placeholder="5000.00" value={price} onChange={(e) => setPrice(e.target.value)} />}</Field>
          <Field label={t('admin.col.included')}>{(p) => <Input {...p} inputMode="numeric" value={max} onChange={(e) => setMax(e.target.value)} />}</Field>
        </div>
        {plan && <label className="flex items-center gap-2 text-sm"><input type="checkbox" checked={active} onChange={(e) => setActive(e.target.checked)} /> {t('admin.planActive')}</label>}
        <div className="flex justify-end gap-2">
          <Button type="button" variant="outline" onClick={onClose}>{t('common.cancel')}</Button>
          <Button type="submit" disabled={!valid || action.isPending}>{t('common.save')}</Button>
        </div>
      </form>
    </Dialog>
  )
}
