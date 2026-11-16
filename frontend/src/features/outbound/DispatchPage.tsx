import { Truck } from 'lucide-react'
import { useState } from 'react'
import { Button } from '@/components/ui/button'
import { Dialog } from '@/components/ui/dialog'
import { Field, FormMessage, Input } from '@/components/ui/input'
import { useWarehouses } from '@/features/warehouses/api'
import { errorMessage } from '@/lib/errors'
import { t } from '@/lib/i18n'
import { useDispatch, useShipments, useToDispatch, type Shipment } from './api'

/** /app/dispatch (and /worker/dispatch): tick the packed orders going on one vehicle and send them. */
export function DispatchPage() {
  const warehouses = useWarehouses()
  const [chosen, setChosen] = useState('')
  const warehouse = warehouses.data?.items.find((w) => w.id === chosen) ?? warehouses.data?.items[0]
  const toDispatch = useToDispatch()
  const shipments = useShipments()
  const [ticked, setTicked] = useState<string[]>([])
  const [vehicle, setVehicle] = useState('')
  const [driver, setDriver] = useState('')
  const [note, setNote] = useState('')
  const [confirming, setConfirming] = useState(false)
  const [sent, setSent] = useState<Shipment | null>(null)
  const orders = (toDispatch.data?.items ?? []).filter((o) => !warehouse || o.warehouseCode === warehouse.code)

  return (
    <div className="mx-auto grid max-w-3xl gap-4">
      <div>
        <h1 className="text-2xl font-semibold">{t('dispatch.title')}</h1>
        <p className="text-sm text-muted-foreground">{t('dispatch.hint')}</p>
      </div>
      {(warehouses.data?.items.length ?? 0) > 1 && (
        <select aria-label={t('po.col.warehouse')} className="h-12 rounded-md border border-input bg-background px-3" value={warehouse?.id ?? ''}
          onChange={(e) => {
            setChosen(e.target.value)
            setTicked([])
          }}>
          {warehouses.data?.items.map((w) => <option key={w.id} value={w.id}>{w.code} · {w.name}</option>)}
        </select>
      )}
      {sent && <FormMessage tone="success">{t('dispatch.sent', { number: sent.number, n: sent.orders.length })}</FormMessage>}
      {toDispatch.isError && <FormMessage tone="error">{errorMessage(toDispatch.error)}</FormMessage>}
      <ul className="divide-y rounded-lg border">
        {orders.map((o) => (
          <li key={o.id}>
            <label className="flex cursor-pointer items-center gap-3 px-3 py-3 hover:bg-secondary">
              <input type="checkbox" className="size-5" checked={ticked.includes(o.id)}
                onChange={(e) => setTicked((x) => (e.target.checked ? [...x, o.id] : x.filter((id) => id !== o.id)))} />
              <span className="min-w-0 flex-1">
                <span className="font-mono font-semibold">{o.number}</span>{o.customerRef && <span className="text-muted-foreground"> · {o.customerRef}</span>}
                <span className="block truncate text-sm text-muted-foreground">{o.customerName}</span>
              </span>
            </label>
          </li>
        ))}
        {toDispatch.data && orders.length === 0 && <li className="px-3 py-6 text-center text-sm text-muted-foreground">{t('dispatch.none')}</li>}
      </ul>
      {orders.length > 0 && (
        <div className="grid gap-3 rounded-xl border bg-card p-4">
          <div className="grid gap-3 sm:grid-cols-2">
            <Field label={t('dispatch.vehicle')}>
              {(p) => <Input {...p} maxLength={20} value={vehicle} onChange={(e) => setVehicle(e.target.value)} />}
            </Field>
            <Field label={t('dispatch.driver')}>
              {(p) => <Input {...p} maxLength={120} value={driver} onChange={(e) => setDriver(e.target.value)} />}
            </Field>
          </div>
          <Field label={t('po.note')}>
            {(p) => <Input {...p} maxLength={500} value={note} onChange={(e) => setNote(e.target.value)} />}
          </Field>
          <Button className="h-12" disabled={ticked.length === 0} onClick={() => setConfirming(true)}>
            <Truck /> {t('dispatch.send', { n: ticked.length })}
          </Button>
        </div>
      )}
      {confirming && warehouse && (
        <ConfirmDialog n={ticked.length} onClose={() => setConfirming(false)}
          body={{ warehouseId: warehouse.id, orderIds: ticked, vehicleNo: vehicle.trim() || null, driverName: driver.trim() || null, note: note.trim() || null }}
          onSent={(s) => {
            setSent(s)
            setTicked([])
            setVehicle('')
            setDriver('')
            setNote('')
            setConfirming(false)
          }} />
      )}
      <section className="grid gap-2">
        <h2 className="text-sm font-medium text-muted-foreground">{t('dispatch.recent')}</h2>
        <ul className="divide-y rounded-lg border text-sm">
          {shipments.data?.items.map((s) => (
            <li key={s.id} className="px-3 py-2">
              <span className="font-mono font-semibold">{s.number}</span> · {new Date(s.createdAt).toLocaleString()}
              {(s.vehicleNo || s.driverName) && <span className="text-muted-foreground"> · {[s.vehicleNo, s.driverName].filter(Boolean).join(' · ')}</span>}
              <span className="block text-muted-foreground">{s.orders.map((o) => `${o.number} (${o.customerName})`).join(', ')}</span>
            </li>
          ))}
          {shipments.data && shipments.data.items.length === 0 && <li className="px-3 py-4 text-muted-foreground">{t('dispatch.noShipments')}</li>}
        </ul>
      </section>
    </div>
  )
}

function ConfirmDialog({ n, body, onClose, onSent }: {
  n: number
  body: Parameters<ReturnType<typeof useDispatch>['mutate']>[0]
  onClose: () => void
  onSent: (s: Shipment) => void
}) {
  const dispatch = useDispatch()
  return (
    <Dialog open onOpenChange={(o) => !o && onClose()} title={t('dispatch.confirmTitle', { n })} description={t('dispatch.confirmHint')}>
      <div className="grid gap-3">
        {dispatch.isError && <FormMessage tone="error">{errorMessage(dispatch.error)}</FormMessage>}
        <div className="flex justify-end gap-2">
          <Button variant="outline" onClick={onClose}>{t('common.cancel')}</Button>
          <Button disabled={dispatch.isPending} onClick={() => dispatch.mutate(body, { onSuccess: onSent })}>{t('dispatch.confirm')}</Button>
        </div>
      </div>
    </Dialog>
  )
}
