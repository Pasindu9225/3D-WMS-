import { zodResolver } from '@hookform/resolvers/zod'
import { useQuery } from '@tanstack/react-query'
import { ArrowLeft, PackageCheck, Trash2, X } from 'lucide-react'
import { useState } from 'react'
import { useFieldArray, useForm, useWatch } from 'react-hook-form'
import { Link, useNavigate, useParams } from 'react-router'
import { z } from 'zod'
import { Button } from '@/components/ui/button'
import { Dialog } from '@/components/ui/dialog'
import { Field, FormMessage, Input } from '@/components/ui/input'
import { Table, Th } from '@/components/ui/table'
import { can, useMe } from '@/features/auth/session'
import type { Product, Supplier } from '@/features/catalog/api'
import { Fact, ProductPicker } from '@/features/inbound/PurchaseOrderPage'
import type { Page } from '@/features/users/api'
import { useWarehouses } from '@/features/warehouses/api'
import { api, ApiError } from '@/lib/api'
import { errorMessage, fieldErrors } from '@/lib/errors'
import { t } from '@/lib/i18n'
import { useSalesOrder, useSoAction, type SalesOrder } from './api'
import { SoStatusBadge } from './SalesOrdersPage'

const SELECT = 'h-10 w-full rounded-md border border-input bg-background px-3 text-sm'
const QTY = /^\d{1,7}(\.\d{1,3})?$/

/** One sales order: a form while it is a draft (and the user may manage orders), otherwise a read-only document. */
export function SalesOrderPage() {
  const { id } = useParams()
  const isNew = id === 'new'
  const me = useMe()
  const manage = can(me.data, 'outbound:manage')
  const so = useSalesOrder(isNew ? undefined : id)

  if (!isNew && so.isPending) return <p className="text-sm text-muted-foreground">{t('app.loading')}</p>
  if (!isNew && so.isError) return <FormMessage tone="error">{errorMessage(so.error)}</FormMessage>
  const order = isNew ? null : so.data ?? null
  const editable = manage && (order === null || order.status === 'DRAFT')

  return (
    <div className="grid max-w-5xl gap-4">
      <Link to="/app/sales-orders" className="inline-flex items-center gap-1 text-sm text-muted-foreground hover:text-foreground">
        <ArrowLeft className="size-4" /> {t('so.back')}
      </Link>
      <div className="flex flex-wrap items-center gap-3">
        <h1 className="text-2xl font-semibold">{order ? order.number : t('so.new')}</h1>
        {order && <SoStatusBadge status={order.status} />}
        {order && manage && (order.status === 'DRAFT' || order.status === 'RELEASED') && <Actions so={order} />}
      </div>
      {order?.status === 'CANCELLED' && <FormMessage tone="error">{t('po.cancelledBecause', { reason: order.cancelReason ?? '' })}</FormMessage>}
      {editable ? <SoForm key={order?.version ?? 'new'} so={order} /> : order && <SoView so={order} />}
    </div>
  )
}

/** A shortage on release names each short product; other errors use the usual messages. */
function releaseError(error: unknown): string {
  return error instanceof ApiError && error.code === 'NOT_ENOUGH_STOCK' ? t('so.short', { list: error.message }) : errorMessage(error)
}

function Actions({ so }: { so: SalesOrder }) {
  const action = useSoAction()
  const release = useSoAction()
  const [open, setOpen] = useState(false)
  const [reason, setReason] = useState('')
  return (
    <div className="ml-auto flex flex-wrap justify-end gap-2">
      {so.status === 'DRAFT' && (
        <Button disabled={release.isPending} onClick={() => release.mutate({ kind: 'release', id: so.id, version: so.version })}>
          <PackageCheck /> {release.isPending ? t('so.releasing') : t('so.release')}
        </Button>
      )}
      <Button variant="outline" onClick={() => setOpen(true)}><X /> {t('po.cancelOrder')}</Button>
      {release.isError && <p role="alert" className="w-full text-right text-sm text-destructive">{releaseError(release.error)}</p>}
      {open && (
        <Dialog open onOpenChange={(o) => !o && setOpen(false)} title={t('po.cancelTitle', { number: so.number })}
          description={so.status === 'RELEASED' ? t('so.cancelReleasedHint') : t('po.cancelHint')}>
          <form className="grid gap-3" onSubmit={(e) => {
            e.preventDefault()
            if (reason.trim()) action.mutate({ kind: 'cancel', id: so.id, version: so.version, reason: reason.trim() },
              { onSuccess: () => setOpen(false) })
          }}>
            {action.isError && <FormMessage tone="error">{errorMessage(action.error)}</FormMessage>}
            <Field label={t('po.cancelReason')}>
              {(p) => <Input {...p} required maxLength={200} value={reason} onChange={(e) => setReason(e.target.value)} />}
            </Field>
            <div className="flex justify-end gap-2">
              <Button type="button" variant="outline" onClick={() => setOpen(false)}>{t('po.keep')}</Button>
              <Button type="submit" variant="destructive" disabled={!reason.trim() || action.isPending}>{t('po.cancelOrder')}</Button>
            </div>
          </form>
        </Dialog>
      )}
    </div>
  )
}

function SoView({ so }: { so: SalesOrder }) {
  return (
    <div className="grid gap-4">
      <dl className="grid grid-cols-2 gap-3 text-sm sm:grid-cols-4">
        <Fact label={t('so.col.customer')} value={`${so.customerName} (${so.customerCode})`} />
        <Fact label={t('so.col.ref')} value={so.customerRef ?? '—'} />
        <Fact label={t('po.col.warehouse')} value={so.warehouseCode} />
        <Fact label={t('so.col.requested')} value={so.requestedDate ?? '—'} />
      </dl>
      {so.shipTo && <p className="text-sm"><span className="font-medium">{t('so.shipTo')}:</span> {so.shipTo}</p>}
      {so.note && <p className="text-sm"><span className="font-medium">{t('po.note')}:</span> {so.note}</p>}
      <Table>
        <thead>
          <tr><Th>{t('po.line.product')}</Th><Th>{t('po.line.unit')}</Th><Th className="text-right">{t('po.line.qty')}</Th></tr>
        </thead>
        <tbody>
          {so.lines.map((l) => (
            <tr key={l.lineNo} className="border-t">
              <td className="px-3 py-2"><span className="font-mono text-xs">{l.sku}</span> {l.productName}</td>
              <td className="px-3 py-2 font-mono text-xs">{l.uom}{l.factor > 1 && ` × ${l.factor}`}</td>
              <td className="px-3 py-2 text-right tabular-nums">{l.qty}</td>
            </tr>
          ))}
        </tbody>
      </Table>
      {so.reserved.length > 0 && (
        <section className="grid gap-2">
          <h2 className="text-sm font-medium">{t('so.reservedTitle')}</h2>
          <Table>
            <thead>
              <tr><Th>{t('po.line.product')}</Th><Th>{t('stock.col.place')}</Th><Th className="hidden sm:table-cell">{t('stock.col.lot')}</Th>
                <Th className="text-right">{t('stock.col.qty')}</Th></tr>
            </thead>
            <tbody>
              {so.reserved.map((r, i) => (
                <tr key={i} className="border-t">
                  <td className="px-3 py-2 font-mono text-xs">{r.sku}</td>
                  <td className="px-3 py-2 font-mono text-sm">{r.binCode ?? '—'}</td>
                  <td className="hidden px-3 py-2 text-sm text-muted-foreground sm:table-cell">{[r.batchNo, r.expiryDate].filter(Boolean).join(' · ') || '—'}</td>
                  <td className="px-3 py-2 text-right tabular-nums">{Number(r.qty)}</td>
                </tr>
              ))}
            </tbody>
          </Table>
        </section>
      )}
    </div>
  )
}

const schema = z.object({
  customerCode: z.string().min(1, t('common.required')),
  customerRef: z.string().trim().max(40, t('common.tooLong')),
  warehouseId: z.string().min(1, t('common.required')),
  requestedDate: z.string(),
  shipTo: z.string().trim().max(500, t('common.tooLong')),
  note: z.string().trim().max(500, t('common.tooLong')),
  lines: z.array(z.object({
    sku: z.string(),
    name: z.string(),
    uom: z.string(),
    options: z.array(z.object({ uom: z.string(), factor: z.number() })),
    qty: z.string().trim().refine((v) => QTY.test(v) && Number(v) > 0 && Number(v) <= 1_000_000, t('po.qtyRule')),
  })).min(1, t('po.noLines')).max(200),
})
type Values = z.infer<typeof schema>

/** The pick lists load first: a select mounted before its options exist would show "Choose…" instead of the saved value. */
function SoForm({ so }: { so: SalesOrder | null }) {
  const customers = useQuery({ queryKey: ['customers', 'all'], queryFn: () => api<Page<Supplier>>('/customers?size=100&sort=name') })
  const warehouses = useWarehouses()
  if (customers.isError || warehouses.isError) return <FormMessage tone="error">{errorMessage(customers.error ?? warehouses.error)}</FormMessage>
  if (!customers.data || !warehouses.data) return <p className="text-sm text-muted-foreground">{t('app.loading')}</p>
  return <SoFields so={so} customers={customers.data.items} warehouses={warehouses.data.items} />
}

function SoFields({ so, customers, warehouses }: { so: SalesOrder | null; customers: Supplier[]; warehouses: { id: string; code: string; name: string }[] }) {
  const navigate = useNavigate()
  const save = useSoAction()
  const form = useForm<Values>({
    resolver: zodResolver(schema),
    defaultValues: {
      customerCode: so?.customerCode ?? '',
      customerRef: so?.customerRef ?? '',
      warehouseId: so?.warehouseId ?? '',
      requestedDate: so?.requestedDate ?? '',
      shipTo: so?.shipTo ?? '',
      note: so?.note ?? '',
      lines: so?.lines.map((l) => ({ sku: l.sku, name: l.productName, uom: l.uom, options: [{ uom: l.uom, factor: l.factor }], qty: String(l.qty) })) ?? [],
    },
  })
  const lines = useFieldArray({ control: form.control, name: 'lines' })
  const code = useWatch({ control: form.control, name: 'customerCode' })
  const address = customers.find((c) => c.code === code)?.address
  const { errors } = form.formState
  const server = fieldErrors(save.error)
  const submit = form.handleSubmit((v) => save.mutate({
    kind: 'save',
    id: so?.id,
    body: {
      customerCode: v.customerCode, customerRef: v.customerRef || null, warehouseId: v.warehouseId,
      requestedDate: v.requestedDate || null, shipTo: v.shipTo || null, note: v.note || null,
      lines: v.lines.map((l) => ({ sku: l.sku, uom: l.uom, qty: Number(l.qty) })),
      version: so?.version,
    },
  }, { onSuccess: (saved) => { if (!so) void navigate(`/app/sales-orders/${saved.id}`, { replace: true }) } }))

  const add = (p: Product) => lines.append({
    sku: p.sku, name: p.name, uom: p.baseUom, qty: '1',
    options: [{ uom: p.baseUom, factor: 1 }, ...p.units.map((u) => ({ uom: u.uom, factor: u.factor }))],
  })

  return (
    <form noValidate className="grid gap-4" onSubmit={submit}>
      {save.isError && Object.keys(server).length === 0 && <FormMessage tone="error">{errorMessage(save.error)}</FormMessage>}
      <div className="grid gap-3 sm:grid-cols-2 lg:grid-cols-4">
        <Field label={t('so.col.customer')} error={errors.customerCode?.message}>
          {(p) => (
            <select {...p} className={SELECT} {...form.register('customerCode')}>
              <option value="">{t('po.pick')}</option>
              {so && !customers.some((c) => c.code === so.customerCode) && (
                <option value={so.customerCode}>{so.customerName}</option>
              )}
              {customers.map((c) => <option key={c.id} value={c.code}>{c.name} ({c.code})</option>)}
            </select>
          )}
        </Field>
        <Field label={t('so.col.ref')} error={errors.customerRef?.message ?? server.customerRef}>
          {(p) => <Input {...p} maxLength={40} {...form.register('customerRef')} />}
        </Field>
        <Field label={t('po.col.warehouse')} error={errors.warehouseId?.message}>
          {(p) => (
            <select {...p} className={SELECT} {...form.register('warehouseId')}>
              <option value="">{t('po.pick')}</option>
              {warehouses.map((w) => <option key={w.id} value={w.id}>{w.code} · {w.name}</option>)}
            </select>
          )}
        </Field>
        <Field label={t('so.col.requested')}>
          {(p) => <Input {...p} type="date" {...form.register('requestedDate')} />}
        </Field>
      </div>
      <Field label={t('so.shipTo')} error={errors.shipTo?.message}>
        {(p) => <Input {...p} maxLength={500} placeholder={address ?? t('so.shipToHint')} {...form.register('shipTo')} />}
      </Field>
      <Field label={t('po.note')} error={errors.note?.message}>
        {(p) => <Input {...p} maxLength={500} {...form.register('note')} />}
      </Field>

      <fieldset className="grid gap-2">
        <legend className="text-sm font-medium">{t('po.lines')}</legend>
        {lines.fields.length > 0 && (
          <div className="overflow-x-auto rounded-md border">
            <table className="w-full min-w-[28rem] text-sm">
              <thead className="bg-secondary/50 text-left text-xs text-muted-foreground">
                <tr><th className="px-3 py-2">{t('po.line.product')}</th><th className="px-2 py-2">{t('po.line.unit')}</th>
                  <th className="px-2 py-2">{t('po.line.qty')}</th><th /></tr>
              </thead>
              <tbody>
                {lines.fields.map((f, i) => (
                  <tr key={f.id} className="border-t align-top">
                    <td className="px-3 py-2"><span className="font-mono text-xs">{f.sku}</span> {f.name}</td>
                    <td className="w-28 px-2 py-1.5">
                      <select aria-label={t('po.line.unitOf', { sku: f.sku })} className={SELECT} {...form.register(`lines.${i}.uom`)}>
                        {f.options.map((o) => <option key={o.uom} value={o.uom}>{o.factor > 1 ? `${o.uom} × ${o.factor}` : o.uom}</option>)}
                      </select>
                    </td>
                    <td className="w-28 px-2 py-1.5">
                      <Input aria-label={t('po.line.qtyOf', { sku: f.sku })} inputMode="decimal" aria-invalid={errors.lines?.[i]?.qty ? true : undefined}
                        {...form.register(`lines.${i}.qty`)} />
                      {errors.lines?.[i]?.qty && <p className="text-xs text-destructive">{errors.lines[i].qty.message}</p>}
                    </td>
                    <td className="px-1 py-1.5">
                      <Button type="button" variant="ghost" size="icon" aria-label={t('po.line.remove', { sku: f.sku })}
                        onClick={() => lines.remove(i)}><Trash2 /></Button>
                    </td>
                  </tr>
                ))}
              </tbody>
            </table>
          </div>
        )}
        {errors.lines?.message && <p className="text-sm text-destructive">{errors.lines.message}</p>}
        {errors.lines?.root?.message && <p className="text-sm text-destructive">{errors.lines.root.message}</p>}
        {lines.fields.length < 200 && <ProductPicker onPick={add} taken={lines.fields.map((f) => f.sku)} />}
      </fieldset>

      <div className="flex justify-end">
        <Button type="submit" disabled={save.isPending}>{save.isPending ? t('common.saving') : so ? t('common.save') : t('po.saveDraft')}</Button>
      </div>
    </form>
  )
}
