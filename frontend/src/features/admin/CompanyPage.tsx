import { ArrowLeft } from 'lucide-react'
import { useState } from 'react'
import { Link, useParams } from 'react-router'
import { Badge } from '@/components/ui/badge'
import { Button } from '@/components/ui/button'
import { Dialog } from '@/components/ui/dialog'
import { Field, FormMessage, Input } from '@/components/ui/input'
import { can, useMe } from '@/features/auth/session'
import { errorMessage } from '@/lib/errors'
import { t } from '@/lib/i18n'
import { bytes, useCompany, useCompanyAction, usePlans, type Company } from './api'

/** /app/admin/companies/:id: one company's usage, plan and limit, suspend / reactivate (Super Admin). Counts only. */
export function CompanyPage() {
  const { id } = useParams()
  const me = useMe()
  const manage = can(me.data, 'platform:manage')
  const company = useCompany(id)
  const [dialog, setDialog] = useState<'plan' | 'suspend' | null>(null)
  const action = useCompanyAction(id ?? '')
  if (company.isPending) return <p className="text-sm text-muted-foreground">{t('app.loading')}</p>
  if (company.isError) return <FormMessage tone="error">{errorMessage(company.error)}</FormMessage>
  const c = company.data
  const u = c.usage
  const cards: [string, string][] = [
    [t('admin.usage.warehouses'), t('admin.ofLimit', { n: u.warehouses, max: c.maxWarehouses })],
    [t('admin.usage.users'), String(u.activeUsers)],
    [t('admin.usage.products'), u.products.toLocaleString('en')],
    [t('admin.usage.movements'), u.movements30Days.toLocaleString('en')],
    [t('admin.usage.files'), bytes(u.storedBytes)],
    [t('admin.usage.lastActive'), u.lastActive ? new Date(u.lastActive).toLocaleString('en-GB') : t('admin.never')],
  ]
  return (
    <div className="grid max-w-4xl gap-4">
      <Link to="/app/admin/companies" className="inline-flex items-center gap-1 text-sm text-muted-foreground hover:text-foreground">
        <ArrowLeft className="size-4" /> {t('admin.companies')}
      </Link>
      <div className="flex flex-wrap items-start justify-between gap-3">
        <div>
          <h1 className="text-2xl font-semibold">{c.name}</h1>
          <p className="text-sm text-muted-foreground">
            {t('admin.planLine', { plan: c.planCode, max: c.maxWarehouses })} · {t('admin.since', { date: new Date(c.createdAt).toLocaleDateString('en-GB') })}
          </p>
        </div>
        <Badge tone={c.status === 'ACTIVE' ? 'good' : 'bad'}>{t(`admin.status.${c.status}`)}</Badge>
      </div>
      {c.status === 'SUSPENDED' && (
        <FormMessage tone="error">{t('admin.suspendedLine', { date: c.suspendedAt ? new Date(c.suspendedAt).toLocaleString('en-GB') : '', reason: c.suspendedReason ?? '' })}</FormMessage>
      )}
      {action.isError && !dialog && <FormMessage tone="error">{errorMessage(action.error)}</FormMessage>}
      <div className="grid grid-cols-2 gap-3 sm:grid-cols-3">
        {cards.map(([label, value]) => (
          <div key={label} className="rounded-lg border bg-card p-4">
            <p className="text-sm text-muted-foreground">{label}</p>
            <p className="mt-1 text-xl font-semibold tabular-nums">{value}</p>
          </div>
        ))}
      </div>
      <p className="text-xs text-muted-foreground">{t('admin.privacy')}</p>
      {manage && (
        <div className="flex flex-wrap gap-2">
          <Button variant="outline" onClick={() => setDialog('plan')}>{t('admin.changePlan')}</Button>
          {c.status === 'ACTIVE'
            ? <Button variant="outline" className="text-destructive" onClick={() => setDialog('suspend')}>{t('admin.suspend')}</Button>
            : <Button disabled={action.isPending} onClick={() => action.mutate({ kind: 'reactivate', version: c.version })}>{t('admin.reactivate')}</Button>}
        </div>
      )}
      {dialog === 'plan' && <PlanDialog company={c} onClose={() => setDialog(null)} />}
      {dialog === 'suspend' && <SuspendDialog company={c} onClose={() => setDialog(null)} />}
    </div>
  )
}

function PlanDialog({ company, onClose }: { company: Company; onClose: () => void }) {
  const plans = usePlans()
  const action = useCompanyAction(company.id)
  const [plan, setPlan] = useState(company.planCode)
  const [max, setMax] = useState(String(company.maxWarehouses))
  const n = Number(max)
  const valid = /^\d{1,4}$/.test(max) && n >= 1 && n <= 1000
  const options = (plans.data ?? []).filter((p) => p.active || p.code === company.planCode)
  return (
    <Dialog open onOpenChange={(o) => !o && onClose()} title={t('admin.changePlan')} description={t('admin.changePlanHint', { n: company.usage.warehouses })}>
      <form className="grid gap-3" onSubmit={(e) => {
        e.preventDefault()
        if (valid) action.mutate({ kind: 'plan', version: company.version, planCode: plan, maxWarehouses: n }, { onSuccess: onClose })
      }}>
        {action.isError && <FormMessage tone="error">{errorMessage(action.error)}</FormMessage>}
        <Field label={t('admin.col.plan')}>{(p) => (
          <select {...p} className="h-10 rounded-md border border-input bg-background px-3" value={plan} onChange={(e) => {
            setPlan(e.target.value)
            const chosen = plans.data?.find((x) => x.code === e.target.value)
            if (chosen) setMax(String(Math.max(chosen.defaultMaxWarehouses, company.usage.warehouses)))
          }}>
            {!options.some((p) => p.code === company.planCode) && <option value={company.planCode}>{company.planCode}</option>}
            {options.map((x) => <option key={x.code} value={x.code}>{x.name} ({x.code})</option>)}
          </select>
        )}</Field>
        <Field label={t('admin.maxWarehouses')}>{(p) => <Input {...p} inputMode="numeric" value={max} onChange={(e) => setMax(e.target.value)} />}</Field>
        <div className="flex justify-end gap-2">
          <Button type="button" variant="outline" onClick={onClose}>{t('common.cancel')}</Button>
          <Button type="submit" disabled={!valid || action.isPending}>{t('common.save')}</Button>
        </div>
      </form>
    </Dialog>
  )
}

function SuspendDialog({ company, onClose }: { company: Company; onClose: () => void }) {
  const action = useCompanyAction(company.id)
  const [reason, setReason] = useState('')
  return (
    <Dialog open onOpenChange={(o) => !o && onClose()} title={t('admin.suspendTitle', { name: company.name })} description={t('admin.suspendHint')}>
      <form className="grid gap-3" onSubmit={(e) => {
        e.preventDefault()
        if (reason.trim()) action.mutate({ kind: 'suspend', version: company.version, reason: reason.trim() }, { onSuccess: onClose })
      }}>
        {action.isError && <FormMessage tone="error">{errorMessage(action.error)}</FormMessage>}
        <Field label={t('admin.reason')}>{(p) => <Input {...p} autoFocus maxLength={200} value={reason} onChange={(e) => setReason(e.target.value)} />}</Field>
        <div className="flex justify-end gap-2">
          <Button type="button" variant="outline" onClick={onClose}>{t('common.cancel')}</Button>
          <Button type="submit" variant="destructive" disabled={!reason.trim() || action.isPending}>{t('admin.suspend')}</Button>
        </div>
      </form>
    </Dialog>
  )
}
