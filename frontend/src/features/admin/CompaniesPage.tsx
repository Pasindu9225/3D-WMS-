import { Plus } from 'lucide-react'
import { useState } from 'react'
import { Link } from 'react-router'
import { Badge } from '@/components/ui/badge'
import { Button } from '@/components/ui/button'
import { Dialog } from '@/components/ui/dialog'
import { Field, FormMessage, Input } from '@/components/ui/input'
import { Pager, Table, Th, Tr } from '@/components/ui/table'
import { can, useMe } from '@/features/auth/session'
import { errorMessage } from '@/lib/errors'
import { t } from '@/lib/i18n'
import { useCompanies, useCreateCompany, usePlans } from './api'

/** /app/admin/companies: every customer company with its plan and how much of it is used (Super Admin). */
export function CompaniesPage() {
  const me = useMe()
  const manage = can(me.data, 'platform:manage')
  const [q, setQ] = useState('')
  const [status, setStatus] = useState('')
  const [page, setPage] = useState(0)
  const [adding, setAdding] = useState(false)
  const companies = useCompanies(q, status, page)
  const data = companies.data
  return (
    <div className="grid gap-4">
      <div className="flex flex-wrap items-end justify-between gap-3">
        <div>
          <h1 className="text-2xl font-semibold">{t('admin.companies')}</h1>
          <p className="text-sm text-muted-foreground">{t('admin.companiesHint')}</p>
        </div>
        {manage && <Button onClick={() => setAdding(true)}><Plus /> {t('admin.newCompany')}</Button>}
      </div>
      <div className="flex flex-wrap gap-2">
        <Input aria-label={t('admin.search')} placeholder={t('admin.search')} className="max-w-xs" maxLength={100} value={q}
          onChange={(e) => { setQ(e.target.value); setPage(0) }} />
        <select aria-label={t('admin.col.status')} className="h-10 rounded-md border border-input bg-background px-3" value={status}
          onChange={(e) => { setStatus(e.target.value); setPage(0) }}>
          <option value="">{t('admin.allStatuses')}</option>
          <option value="ACTIVE">{t('admin.status.ACTIVE')}</option>
          <option value="SUSPENDED">{t('admin.status.SUSPENDED')}</option>
        </select>
      </div>
      {companies.isError && <FormMessage tone="error">{errorMessage(companies.error)}</FormMessage>}
      <Table>
        <thead>
          <tr><Th>{t('admin.col.company')}</Th><Th>{t('admin.col.plan')}</Th><Th>{t('admin.col.warehouses')}</Th>
            <Th className="text-right">{t('admin.col.users')}</Th><Th>{t('admin.col.status')}</Th><Th className="hidden md:table-cell">{t('admin.col.since')}</Th></tr>
        </thead>
        <tbody>
          {data?.items.map((c) => (
            <Tr key={c.id}>
              <td><Link className="font-medium underline-offset-2 hover:underline" to={`/app/admin/companies/${c.id}`}>{c.name}</Link></td>
              <td className="font-mono text-sm">{c.planCode}</td>
              <td>
                <div className="flex items-center gap-2">
                  <div className="h-2 w-20 overflow-hidden rounded bg-secondary" aria-hidden>
                    <div className={`h-full ${c.warehouses >= c.maxWarehouses ? 'bg-destructive' : 'bg-primary'}`}
                      style={{ width: `${Math.min(100, (100 * c.warehouses) / Math.max(1, c.maxWarehouses))}%` }} />
                  </div>
                  <span className="text-sm tabular-nums">{t('admin.ofLimit', { n: c.warehouses, max: c.maxWarehouses })}</span>
                </div>
              </td>
              <td className="text-right tabular-nums">{c.users}</td>
              <td><Badge tone={c.status === 'ACTIVE' ? 'good' : 'bad'}>{t(`admin.status.${c.status}`)}</Badge></td>
              <td className="hidden text-sm text-muted-foreground md:table-cell">{new Date(c.createdAt).toLocaleDateString('en-GB')}</td>
            </Tr>
          ))}
          {data && data.items.length === 0 && <tr><td colSpan={6} className="py-8 text-center text-muted-foreground">{t('admin.noCompanies')}</td></tr>}
        </tbody>
      </Table>
      {data && data.total > data.size && (
        <Pager page={data.page} size={data.size} total={data.total} onPage={setPage}
          labels={{ previous: t('common.previous'), next: t('common.next'),
            range: t('common.pageOf', { from: data.page * data.size + 1, to: data.page * data.size + data.items.length, total: data.total }) }} />
      )}
      {adding && <NewCompanyDialog onClose={() => setAdding(false)} />}
    </div>
  )
}

function NewCompanyDialog({ onClose }: { onClose: () => void }) {
  const plans = usePlans()
  const create = useCreateCompany()
  const [name, setName] = useState('')
  const [plan, setPlan] = useState('')
  const [email, setEmail] = useState('')
  const [admin, setAdmin] = useState('')
  const active = plans.data?.filter((p) => p.active) ?? []
  const planCode = plan || active[0]?.code || ''
  const valid = name.trim() !== '' && planCode !== '' && /^\S+@\S+\.\S+$/.test(email.trim()) && admin.trim() !== ''
  const done = create.data
  return (
    <Dialog open onOpenChange={(o) => !o && onClose()} title={t('admin.newCompany')} description={t('admin.newCompanyHint')}>
      {done ? (
        <div className="grid gap-3">
          <FormMessage tone="success">{t('admin.created', { name: done.company.name })}</FormMessage>
          <p className="text-sm">{t('admin.inviteOnce')}</p>
          <Input aria-label={t('admin.inviteLink')} readOnly value={done.inviteUrl} onFocus={(e) => e.currentTarget.select()} className="font-mono text-xs" />
          <div className="flex justify-end"><Button onClick={onClose}>{t('common.done')}</Button></div>
        </div>
      ) : (
        <form className="grid gap-3" onSubmit={(e) => {
          e.preventDefault()
          if (valid) create.mutate({ name: name.trim(), planCode, adminEmail: email.trim(), adminName: admin.trim() })
        }}>
          {create.isError && <FormMessage tone="error">{errorMessage(create.error)}</FormMessage>}
          <Field label={t('admin.companyName')}>{(p) => <Input {...p} autoFocus maxLength={150} value={name} onChange={(e) => setName(e.target.value)} />}</Field>
          <Field label={t('admin.col.plan')}>{(p) => (
            <select {...p} className="h-10 rounded-md border border-input bg-background px-3" value={planCode} onChange={(e) => setPlan(e.target.value)}>
              {active.map((x) => <option key={x.code} value={x.code}>{x.name} ({x.code})</option>)}
            </select>
          )}</Field>
          <Field label={t('admin.adminEmail')}>{(p) => <Input {...p} type="email" maxLength={190} value={email} onChange={(e) => setEmail(e.target.value)} />}</Field>
          <Field label={t('admin.adminName')}>{(p) => <Input {...p} maxLength={120} value={admin} onChange={(e) => setAdmin(e.target.value)} />}</Field>
          <div className="flex justify-end gap-2">
            <Button type="button" variant="outline" onClick={onClose}>{t('common.cancel')}</Button>
            <Button type="submit" disabled={!valid || create.isPending}>{t('admin.create')}</Button>
          </div>
        </form>
      )}
    </Dialog>
  )
}
