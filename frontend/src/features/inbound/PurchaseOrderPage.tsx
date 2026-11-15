import { zodResolver } from '@hookform/resolvers/zod'
import { useQuery } from '@tanstack/react-query'
import { ArrowLeft, Check, PackageCheck, Plus, Trash2, X } from 'lucide-react'
import { useState } from 'react'
import { useFieldArray, useForm, useWatch, type Control, type UseFormRegisterReturn } from 'react-hook-form'
import { Link, useNavigate, useParams } from 'react-router'
import { z } from 'zod'
import { Button } from '@/components/ui/button'
import { Dialog } from '@/components/ui/dialog'
import { Field, FormMessage, Input } from '@/components/ui/input'
import { Table, Th } from '@/components/ui/table'
import { can, useMe } from '@/features/auth/session'
import { useProducts, type Product, type Supplier } from '@/features/catalog/api'
import { formatMoney, parseMoney } from '@/features/layout/units'
import type { Page } from '@/features/users/api'
import { useWarehouses } from '@/features/warehouses/api'
import { api } from '@/lib/api'
import { errorMessage, fieldErrors } from '@/lib/errors'
import { t } from '@/lib/i18n'
import { useDebounced } from '@/lib/useDebounced'
import { usePoAction, usePurchaseOrder, useReceiptAction, type PurchaseOrder } from './api'
import { PoStatusBadge } from './PurchaseOrdersPage'

const SELECT = 'h-10 w-full rounded-md border border-input bg-background px-3 text-sm'
const QTY = /^\d{1,7}(\.\d{1,3})?$/

/** One order: a form while it is a draft (and the user may manage orders), otherwise a read-only document. */
export function PurchaseOrderPage() {
  const { id } = useParams()
  const isNew = id === 'new'
  const me = useMe()
  const manage = can(me.data, 'inbound:manage')
  const po = usePurchaseOrder(isNew ? undefined : id)

  if (!isNew && po.isPending) return <p className="text-sm text-muted-foreground">{t('app.loading')}</p>
  if (!isNew && po.isError) return <FormMessage tone="error">{errorMessage(po.error)}</FormMessage>
  const order = isNew ? null : po.data ?? null
  const editable = manage && (order === null || order.status === 'DRAFT')

  return (
    <div className="grid max-w-5xl gap-4">
      <Link to="/app/purchase-orders" className="inline-flex items-center gap-1 text-sm text-muted-foreground hover:text-foreground">
        <ArrowLeft className="size-4" /> {t('po.back')}
      </Link>
      <div className="flex flex-wrap items-center gap-3">
        <h1 className="text-2xl font-semibold">{order ? order.number : t('po.new')}</h1>
        {order && <PoStatusBadge status={order.status} />}
        {order && can(me.data, 'inbound:receive') && (order.status === 'APPROVED' || order.status === 'PARTLY_RECEIVED')
          && <ReceiveButton poId={order.id} />}
        {order && manage && <Actions po={order} />}
      </div>
      {order?.status === 'CANCELLED' && <FormMessage tone="error">{t('po.cancelledBecause', { reason: order.cancelReason ?? '' })}</FormMessage>}
      {editable ? <PoForm key={order?.version ?? 'new'} po={order} /> : order && <PoView po={order} />}
    </div>
  )
}

function ReceiveButton({ poId }: { poId: string }) {
  const navigate = useNavigate()
  const open = useReceiptAction()
  return (
    <>
      <Button variant="outline" disabled={open.isPending}
        onClick={() => open.mutate({ kind: 'open', poId }, { onSuccess: (r) => void navigate(`/app/receive/${r.id}`) })}>
        <PackageCheck /> {t('po.receive')}
      </Button>
      {open.isError && <p role="alert" className="w-full text-sm text-destructive">{errorMessage(open.error)}</p>}
    </>
  )
}

function Actions({ po }: { po: PurchaseOrder }) {
  const action = usePoAction()
  const [cancelling, setCancelling] = useState(false)
  const [reason, setReason] = useState('')
  const received = po.lines.some((l) => l.qtyReceived > 0)
  const canCancel = (po.status === 'DRAFT' || po.status === 'APPROVED') && !received
  return (
    <div className="ml-auto flex flex-wrap gap-2">
      {po.status === 'DRAFT' && (
        <Button disabled={action.isPending} onClick={() => action.mutate({ kind: 'approve', id: po.id, version: po.version })}>
          <Check /> {t('po.approve')}
        </Button>
      )}
      {canCancel && <Button variant="outline" onClick={() => setCancelling(true)}><X /> {t('po.cancelOrder')}</Button>}
      {action.isError && !cancelling && <p role="alert" className="w-full text-sm text-destructive">{errorMessage(action.error)}</p>}
      {cancelling && (
        <Dialog open onOpenChange={(o) => !o && setCancelling(false)} title={t('po.cancelTitle', { number: po.number })}
          description={t('po.cancelHint')}>
          <form className="grid gap-3" onSubmit={(e) => {
            e.preventDefault()
            if (reason.trim()) action.mutate({ kind: 'cancel', id: po.id, version: po.version, reason: reason.trim() },
              { onSuccess: () => setCancelling(false) })
          }}>
            {action.isError && <FormMessage tone="error">{errorMessage(action.error)}</FormMessage>}
            <Field label={t('po.cancelReason')}>
              {(p) => <Input {...p} required maxLength={200} value={reason} onChange={(e) => setReason(e.target.value)} />}
            </Field>
            <div className="flex justify-end gap-2">
              <Button type="button" variant="outline" onClick={() => setCancelling(false)}>{t('po.keep')}</Button>
              <Button type="submit" variant="destructive" disabled={!reason.trim() || action.isPending}>{t('po.cancelOrder')}</Button>
            </div>
          </form>
        </Dialog>
      )}
    </div>
  )
}

function PoView({ po }: { po: PurchaseOrder }) {
  return (
    <div className="grid gap-4">
      <dl className="grid grid-cols-2 gap-3 text-sm sm:grid-cols-4">
        <Fact label={t('po.col.supplier')} value={`${po.supplierName} (${po.supplierCode})`} />
        <Fact label={t('po.col.warehouse')} value={po.warehouseCode} />
        <Fact label={t('po.col.expected')} value={po.expectedDate ?? '—'} />
        <Fact label={t('po.col.total')} value={formatMoney(po.totalMinor)} />
      </dl>
      {po.note && <p className="text-sm"><span className="font-medium">{t('po.note')}:</span> {po.note}</p>}
      <Table>
        <thead>
          <tr>
            <Th>{t('po.line.product')}</Th><Th>{t('po.line.unit')}</Th><Th className="text-right">{t('po.line.qty')}</Th>
            <Th className="text-right">{t('po.line.received')}</Th>
            <Th className="text-right">{t('po.line.cost')}</Th><Th className="text-right">{t('po.line.total')}</Th>
          </tr>
        </thead>
        <tbody>
          {po.lines.map((l) => (
            <tr key={l.lineNo} className="border-t">
              <td className="px-3 py-2"><span className="font-mono text-xs">{l.sku}</span> {l.productName}</td>
              <td className="px-3 py-2 font-mono text-xs">{l.uom}{l.factor > 1 && ` × ${l.factor}`}</td>
              <td className="px-3 py-2 text-right tabular-nums">{l.qty}</td>
              <td className="px-3 py-2 text-right tabular-nums">{Number((l.qtyReceived / l.factor).toFixed(3))}</td>
              <td className="px-3 py-2 text-right tabular-nums">{formatMoney(l.unitCostMinor)}</td>
              <td className="px-3 py-2 text-right tabular-nums">{formatMoney(l.totalMinor)}</td>
            </tr>
          ))}
        </tbody>
      </Table>
    </div>
  )
}

function Fact({ label, value }: { label: string; value: string }) {
  return <div><dt className="text-muted-foreground">{label}</dt><dd className="font-medium">{value}</dd></div>
}

const schema = z.object({
  supplierCode: z.string().min(1, t('common.required')),
  warehouseId: z.string().min(1, t('common.required')),
  expectedDate: z.string(),
  note: z.string().trim().max(500, t('common.tooLong')),
  lines: z.array(z.object({
    sku: z.string(),
    name: z.string(),
    uom: z.string(),
    /** Units this line can switch between, with base units in each; cost per base unit for the default price. */
    options: z.array(z.object({ uom: z.string(), factor: z.number() })),
    baseCostMinor: z.number().nullable(),
    qty: z.string().trim().refine((v) => QTY.test(v) && Number(v) > 0 && Number(v) <= 1_000_000, t('po.qtyRule')),
    cost: z.string().trim().refine((v) => v === '' || parseMoney(v) !== null, t('products.costRule')),
  })).min(1, t('po.noLines')).max(200),
})
type Values = z.infer<typeof schema>
type LineValues = Values['lines'][number]

/** Price of one ordered unit: what was typed, else the product's cost x unit factor. */
function unitCost(l: LineValues): number | null {
  if (l.cost.trim()) return parseMoney(l.cost)
  const f = l.options.find((o) => o.uom === l.uom)?.factor ?? 1
  return l.baseCostMinor === null ? null : l.baseCostMinor * f
}

function PoForm({ po }: { po: PurchaseOrder | null }) {
  const navigate = useNavigate()
  const save = usePoAction()
  const suppliers = useQuery({ queryKey: ['suppliers', 'all'], queryFn: () => api<Page<Supplier>>('/suppliers?size=100&sort=name') })
  const warehouses = useWarehouses()
  const form = useForm<Values>({
    resolver: zodResolver(schema),
    defaultValues: {
      supplierCode: po?.supplierCode ?? '',
      warehouseId: po?.warehouseId ?? '',
      expectedDate: po?.expectedDate ?? '',
      note: po?.note ?? '',
      lines: po?.lines.map((l) => ({
        sku: l.sku, name: l.productName, uom: l.uom, options: [{ uom: l.uom, factor: l.factor }], baseCostMinor: null,
        qty: String(l.qty), cost: (l.unitCostMinor / 100).toFixed(2),
      })) ?? [],
    },
  })
  const lines = useFieldArray({ control: form.control, name: 'lines' })
  const { errors } = form.formState
  const server = fieldErrors(save.error)
  const submit = form.handleSubmit((v) => save.mutate({
    kind: 'save',
    id: po?.id,
    body: {
      supplierCode: v.supplierCode, warehouseId: v.warehouseId, expectedDate: v.expectedDate || null, note: v.note || null,
      lines: v.lines.map((l) => ({ sku: l.sku, uom: l.uom, qty: Number(l.qty), unitCostMinor: l.cost ? parseMoney(l.cost) : null })),
      version: po?.version,
    },
  }, { onSuccess: (saved) => { if (!po) void navigate(`/app/purchase-orders/${saved.id}`, { replace: true }) } }))

  const add = (p: Product) => lines.append({
    sku: p.sku, name: p.name, uom: p.baseUom, baseCostMinor: p.costMinor, qty: '1', cost: '',
    options: [{ uom: p.baseUom, factor: 1 }, ...p.units.map((u) => ({ uom: u.uom, factor: u.factor }))],
  })

  return (
    <form noValidate className="grid gap-4" onSubmit={submit}>
      {save.isError && Object.keys(server).length === 0 && <FormMessage tone="error">{errorMessage(save.error)}</FormMessage>}
      <div className="grid gap-3 sm:grid-cols-3">
        <Field label={t('po.col.supplier')} error={errors.supplierCode?.message}>
          {(p) => (
            <select {...p} className={SELECT} {...form.register('supplierCode')}>
              <option value="">{t('po.pick')}</option>
              {po && !suppliers.data?.items.some((s) => s.code === po.supplierCode) && (
                <option value={po.supplierCode}>{po.supplierName}</option>
              )}
              {suppliers.data?.items.map((s) => <option key={s.id} value={s.code}>{s.name} ({s.code})</option>)}
            </select>
          )}
        </Field>
        <Field label={t('po.col.warehouse')} error={errors.warehouseId?.message}>
          {(p) => (
            <select {...p} className={SELECT} {...form.register('warehouseId')}>
              <option value="">{t('po.pick')}</option>
              {warehouses.data?.items.map((w) => <option key={w.id} value={w.id}>{w.code} · {w.name}</option>)}
            </select>
          )}
        </Field>
        <Field label={t('po.col.expected')}>
          {(p) => <Input {...p} type="date" {...form.register('expectedDate')} />}
        </Field>
      </div>
      <Field label={t('po.note')} error={errors.note?.message}>
        {(p) => <Input {...p} maxLength={500} {...form.register('note')} />}
      </Field>

      <fieldset className="grid gap-2">
        <legend className="text-sm font-medium">{t('po.lines')}</legend>
        {lines.fields.length > 0 && (
          <div className="overflow-x-auto rounded-md border">
            <table className="w-full min-w-[40rem] text-sm">
              <thead className="bg-secondary/50 text-left text-xs text-muted-foreground">
                <tr>
                  <th className="px-3 py-2">{t('po.line.product')}</th><th className="px-2 py-2">{t('po.line.unit')}</th>
                  <th className="px-2 py-2">{t('po.line.qty')}</th><th className="px-2 py-2">{t('po.line.cost')}</th>
                  <th className="px-2 py-2 text-right">{t('po.line.total')}</th><th />
                </tr>
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
                    <td className="w-32 px-2 py-1.5">
                      <CostInput control={form.control} index={i} sku={f.sku} register={form.register(`lines.${i}.cost`)} />
                      {errors.lines?.[i]?.cost && <p className="text-xs text-destructive">{errors.lines[i].cost.message}</p>}
                    </td>
                    <td className="px-2 py-2 text-right tabular-nums"><LineTotal control={form.control} index={i} /></td>
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

      <div className="flex items-center justify-end gap-3">
        <span className="text-sm">{t('po.col.total')}: <strong className="tabular-nums"><OrderTotal control={form.control} /></strong></span>
        <Button type="submit" disabled={save.isPending}>{save.isPending ? t('common.saving') : po ? t('common.save') : t('po.saveDraft')}</Button>
      </div>
    </form>
  )
}

function CostInput({ control, index, sku, register }: {
  control: Control<Values>; index: number; sku: string; register: UseFormRegisterReturn
}) {
  const line = useWatch({ control, name: `lines.${index}` })
  const fallback = unitCost({ ...line, cost: '' })
  return (
    <Input aria-label={t('po.line.costOf', { sku })} inputMode="decimal"
      placeholder={fallback === null ? '0.00' : formatMoney(fallback)} {...register} />
  )
}

function LineTotal({ control, index }: { control: Control<Values>; index: number }) {
  const line = useWatch({ control, name: `lines.${index}` })
  const cost = unitCost(line)
  const qty = Number(line.qty)
  return <>{cost === null || !QTY.test(line.qty.trim()) ? '—' : formatMoney(Math.round(qty * cost))}</>
}

function OrderTotal({ control }: { control: Control<Values> }) {
  const all = useWatch({ control, name: 'lines' })
  const total = all.reduce((sum, l) => sum + (QTY.test(l.qty.trim()) ? Math.round(Number(l.qty) * (unitCost(l) ?? 0)) : 0), 0)
  return <>{formatMoney(total)}</>
}

/** Type a SKU, barcode or name; pick from the matches to add a line. */
function ProductPicker({ onPick, taken }: { onPick: (p: Product) => void; taken: string[] }) {
  const [text, setText] = useState('')
  const q = useDebounced(text.trim())
  const found = useProducts(q, 0)
  // Placeholder data is the previous search's result (the list keeps it while paging); never offer it here.
  const matches = q && !found.isPlaceholderData ? (found.data?.items ?? []).slice(0, 8) : []
  return (
    <div className="grid max-w-md gap-1">
      <Input aria-label={t('po.addProduct')} placeholder={t('po.addProduct')} value={text} maxLength={200}
        onChange={(e) => setText(e.target.value)} />
      {matches.length > 0 && (
        <ul className="rounded-md border bg-card text-sm shadow-sm">
          {matches.map((p) => (
            <li key={p.id}>
              <button type="button" className="flex w-full items-center gap-2 px-3 py-2 text-left hover:bg-secondary"
                onClick={() => {
                  onPick(p)
                  setText('')
                }}>
                <Plus className="size-4 shrink-0" />
                <span className="font-mono text-xs">{p.sku}</span> {p.name}
                {taken.includes(p.sku) && <span className="ml-auto text-xs text-muted-foreground">{t('po.alreadyOn')}</span>}
              </button>
            </li>
          ))}
        </ul>
      )}
      {q && found.data && !found.isPlaceholderData && matches.length === 0 &&<p className="text-xs text-muted-foreground">{t('po.noProduct')}</p>}
    </div>
  )
}
