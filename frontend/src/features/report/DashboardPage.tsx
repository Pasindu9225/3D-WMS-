import { AlertTriangle } from 'lucide-react'
import { useState } from 'react'
import { FormMessage } from '@/components/ui/input'
import { Table, Th, Tr } from '@/components/ui/table'
import { formatMoney } from '@/features/layout/units'
import { useWarehouses } from '@/features/warehouses/api'
import { errorMessage } from '@/lib/errors'
import { t, type MessageKey } from '@/lib/i18n'
import { useDashboard } from './api'

const num = (n: number) => Number(Number(n).toFixed(3)).toLocaleString('en')

/** Minutes as "45 min" or "3 h 20 min". */
function duration(minutes: number) {
  if (minutes < 60) return t('dash.min', { n: minutes })
  const h = Math.floor(minutes / 60)
  const m = minutes % 60
  return m ? t('dash.hMin', { h, m }) : t('dash.h', { h })
}

/** A headline figure: value in normal ink, label and note muted (identity is never colour alone). */
function Tile({ label, value, note }: { label: MessageKey; value: string; note?: string }) {
  return (
    <div className="rounded-lg border bg-card p-4">
      <div className="text-sm text-muted-foreground">{t(label)}</div>
      <div className="mt-1 text-2xl font-semibold tabular-nums">{value}</div>
      {note && <div className="mt-1 text-xs text-muted-foreground">{note}</div>}
    </div>
  )
}

/** /app (for report readers) and /app/dashboard: stock value, low stock, today, bin use, floor speed. */
export function DashboardPage() {
  const warehouses = useWarehouses()
  const [chosen, setChosen] = useState('')
  const warehouseId = chosen || warehouses.data?.items[0]?.id
  const dash = useDashboard(warehouseId)
  const d = dash.data

  return (
    <div className="grid gap-5">
      <div className="flex flex-wrap items-end justify-between gap-3">
        <div>
          <h1 className="text-2xl font-semibold">{t('dash.title')}</h1>
          <p className="text-sm text-muted-foreground">{t('dash.hint')}</p>
        </div>
        {(warehouses.data?.items.length ?? 0) > 1 && (
          <select aria-label={t('po.col.warehouse')} className="h-10 rounded-md border border-input bg-background px-3" value={warehouseId ?? ''}
            onChange={(e) => setChosen(e.target.value)}>
            {warehouses.data?.items.map((w) => <option key={w.id} value={w.id}>{w.code} · {w.name}</option>)}
          </select>
        )}
      </div>
      {warehouses.data && warehouses.data.items.length === 0 && <p className="text-muted-foreground">{t('home.body')}</p>}
      {dash.isError && <FormMessage tone="error">{errorMessage(dash.error)}</FormMessage>}
      {d && (
        <>
          <div className="grid grid-cols-2 gap-3 lg:grid-cols-4">
            <Tile label="dash.value" value={formatMoney(d.stockValueMinor)}
              note={d.productsWithoutCost ? t('dash.noCost', { n: d.productsWithoutCost }) : t('dash.valueNote')} />
            <Tile label="dash.bins" value={`${d.binUsePercent}%`} note={t('dash.binsNote', { used: d.binsInUse, all: d.bins, fill: d.averageFillPercent })} />
            <Tile label="dash.dockToStock" value={d.dockToStockMinutes === null ? '–' : duration(d.dockToStockMinutes)} note={t('dash.dockNote')} />
            <Tile label="dash.linesPerHour" value={d.linesPerHour === null ? '–' : num(d.linesPerHour)} note={t('dash.linesNote')} />
          </div>
          <section className="grid gap-2">
            <h2 className="text-sm font-medium text-muted-foreground">{t('dash.today')}</h2>
            <div className="grid grid-cols-2 gap-3 sm:grid-cols-3 lg:grid-cols-6">
              <Tile label="dash.receipts" value={num(d.today.receipts)} note={t('dash.units', { n: num(d.today.unitsReceived) })} />
              <Tile label="dash.shipments" value={num(d.today.shipments)} note={t('dash.units', { n: num(d.today.unitsShipped) })} />
              <Tile label="dash.released" value={num(d.today.ordersReleased)} />
              <Tile label="dash.shipped" value={num(d.today.ordersShipped)} />
              <Tile label="dash.adjustments" value={num(d.today.adjustments)} />
            </div>
          </section>
          <section className="grid gap-2">
            <h2 className="flex items-center gap-2 text-sm font-medium">
              {d.lowStock.length > 0 && <AlertTriangle className="size-4 text-amber-600 dark:text-amber-400" aria-hidden />}
              {t('dash.lowStock', { n: d.lowStock.length })}
            </h2>
            {d.lowStock.length === 0 ? <p className="text-sm text-muted-foreground">{t('dash.noLowStock')}</p> : (
              <Table>
                <thead>
                  <tr><Th>{t('stock.col.product')}</Th><Th className="text-right">{t('stock.col.onHand')}</Th><Th className="text-right">{t('dash.reorderAt')}</Th></tr>
                </thead>
                <tbody>
                  {d.lowStock.map((l) => (
                    <Tr key={l.productId}>
                      <td><span className="font-mono text-sm font-semibold">{l.sku}</span> {l.name}</td>
                      <td className="text-right tabular-nums">{num(l.onHand)} <span className="text-xs text-muted-foreground">{l.baseUom}</span></td>
                      <td className="text-right tabular-nums">{num(l.reorderPoint)}</td>
                    </Tr>
                  ))}
                </tbody>
              </Table>
            )}
          </section>
        </>
      )}
    </div>
  )
}
