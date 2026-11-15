import { useState } from 'react'
import { useQuery } from '@tanstack/react-query'
import { Field, FormMessage, Input } from '@/components/ui/input'
import { useStockRows } from '@/features/inventory/api'
import { useWarehouses } from '@/features/warehouses/api'
import { api, ApiError } from '@/lib/api'
import { errorMessage } from '@/lib/errors'
import { t } from '@/lib/i18n'
import { ScanButton } from './Scanner'

interface ScanView { product: { id: string; sku: string; name: string; baseUom: string }; uom: string; factor: number }

const num = (n: number) => Number(Number(n).toFixed(3)).toLocaleString('en')

/** /worker/find: scan a product or a bin label and see what is where. */
export function FindPage() {
  const warehouses = useWarehouses()
  const warehouseId = warehouses.data?.items[0]?.id
  const [text, setText] = useState('')
  const [code, setCode] = useState('')
  const product = useQuery({
    queryKey: ['find-product', code],
    queryFn: () => api<ScanView>(`/products/by-barcode/${encodeURIComponent(code)}`),
    enabled: code !== '',
    retry: false,
  })
  const notProduct = product.error instanceof ApiError && product.error.status === 404
  const productStock = useStockRows(product.data ? warehouseId : undefined, { product: product.data?.product.id }, 0)
  const binStock = useStockRows(notProduct ? warehouseId : undefined, { binCode: code.toUpperCase() }, 0)
  const binRows = binStock.data?.items.filter((s) => s.binCode === code.toUpperCase()) ?? []

  const look = (c: string) => {
    setText(c)
    setCode(c.trim())
  }
  return (
    <div className="grid gap-4">
      <h1 className="text-xl font-semibold">{t('worker.find')}</h1>
      <form className="flex items-end gap-2" onSubmit={(e) => {
        e.preventDefault()
        look(text)
      }}>
        <div className="flex-1">
          <Field label={t('find.code')}>
            {(p) => <Input {...p} autoFocus autoComplete="off" className="h-12 font-mono text-lg" maxLength={64} value={text}
              placeholder={t('find.hint')} onChange={(e) => setText(e.target.value)} />}
          </Field>
        </div>
        <ScanButton label={t('scan.open')} onCode={look} />
      </form>
      {product.data && (
        <section className="grid gap-2">
          <p><span className="font-mono font-semibold">{product.data.product.sku}</span> {product.data.product.name}</p>
          {product.data.factor > 1 && <p className="text-sm text-muted-foreground">{t('find.unit', { uom: product.data.uom, n: product.data.factor, base: product.data.product.baseUom })}</p>}
          <ul className="divide-y rounded-lg border">
            {productStock.data?.items.map((s, i) => (
              <li key={i} className="flex justify-between px-3 py-2">
                <span className="font-mono text-sm">{s.binCode === 'RECEIVING' ? t('move.receiving') : s.binCode}</span>
                <span className="tabular-nums">{num(s.qtyOnHand)} {s.baseUom}</span>
              </li>
            ))}
          </ul>
          {productStock.data?.items.length === 0 && <p className="text-sm text-muted-foreground">{t('find.noStock')}</p>}
        </section>
      )}
      {notProduct && binStock.data && (
        binRows.length > 0 ? (
          <section className="grid gap-2">
            <p className="font-mono font-semibold">{code.toUpperCase()}</p>
            <ul className="divide-y rounded-lg border">
              {binRows.map((s, i) => (
                <li key={i} className="flex justify-between px-3 py-2">
                  <span className="min-w-0 truncate"><span className="font-mono text-sm">{s.sku}</span> {s.name}</span>
                  <span className="tabular-nums">{num(s.qtyOnHand)} {s.baseUom}</span>
                </li>
              ))}
            </ul>
          </section>
        ) : <FormMessage tone="error">{t('find.nothing', { code })}</FormMessage>
      )}
      {product.isError && !notProduct && <FormMessage tone="error">{errorMessage(product.error)}</FormMessage>}
    </div>
  )
}
