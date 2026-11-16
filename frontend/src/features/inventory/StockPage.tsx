import { useState } from 'react'
import { FormMessage, Input } from '@/components/ui/input'
import { Pager, Table, Th, Tr } from '@/components/ui/table'
import type { Page } from '@/features/users/api'
import { useWarehouses } from '@/features/warehouses/api'
import { errorMessage } from '@/lib/errors'
import { t, type MessageKey } from '@/lib/i18n'
import { cn } from '@/lib/utils'
import { useDebounced } from '@/lib/useDebounced'
import { ExportLinks } from '@/features/report/ExportLinks'
import { useMovements, useProductTotals, useStockRows, type ProductTotal } from './api'

type Tab = 'products' | 'bins' | 'movements'
const TYPES = ['RECEIVE', 'PUTAWAY', 'TRANSFER', 'ADJUST'] as const
const num = (n: number) => Number(Number(n).toFixed(3)).toLocaleString('en')
const place = (code: string | null) => (code === 'RECEIVING' ? t('move.receiving') : code === 'DISPATCH' ? t('stock.dispatch') : code ?? '—')

/** /app/stock: stock by product, by bin, and the movement history of one warehouse. */
export function StockPage() {
  const warehouses = useWarehouses()
  const [chosen, setChosen] = useState('')
  const warehouseId = chosen || warehouses.data?.items[0]?.id
  const [tab, setTab] = useState<Tab>('products')
  const [product, setProduct] = useState<ProductTotal | null>(null)

  return (
    <div className="grid gap-4">
      <div className="flex flex-wrap items-end justify-between gap-3">
        <div>
          <h1 className="text-2xl font-semibold">{t('stock.title')}</h1>
          <p className="text-sm text-muted-foreground">{t('stock.hint')}</p>
        </div>
        <span className="flex flex-wrap items-center gap-3">
          {warehouseId && <ExportLinks what="stock" params={{ warehouse: warehouseId }} />}
        {(warehouses.data?.items.length ?? 0) > 1 && (
          <select aria-label={t('putaway.warehouse')} className="h-10 rounded-md border border-input bg-background px-3 text-sm"
            value={warehouseId ?? ''} onChange={(e) => {
              setChosen(e.target.value)
              setProduct(null)
            }}>
            {warehouses.data?.items.map((w) => <option key={w.id} value={w.id}>{w.code} · {w.name}</option>)}
          </select>
        )}
        </span>
      </div>
      <div role="tablist" className="flex gap-1 border-b">
        {(['products', 'bins', 'movements'] as const).map((k) => (
          <button key={k} role="tab" aria-selected={tab === k} type="button"
            className={cn('-mb-px border-b-2 px-3 py-2 text-sm', tab === k ? 'border-primary font-medium' : 'border-transparent text-muted-foreground')}
            onClick={() => {
              setTab(k)
              setProduct(null)
            }}>{t(`stock.tab.${k}` as MessageKey)}</button>
        ))}
      </div>
      {tab === 'products' && !product && <ByProduct warehouseId={warehouseId} onOpen={setProduct} />}
      {tab === 'products' && product && <Locations warehouseId={warehouseId} product={product} onBack={() => setProduct(null)} />}
      {tab === 'bins' && <ByBin warehouseId={warehouseId} />}
      {tab === 'movements' && <Movements warehouseId={warehouseId} />}
    </div>
  )
}

function Paging<T>({ data, onPage }: { data: Page<T> | undefined; onPage: (p: number) => void }) {
  if (!data || data.total <= data.size) return null
  return (
    <Pager page={data.page} size={data.size} total={data.total} onPage={onPage}
      labels={{
        previous: t('common.previous'), next: t('common.next'),
        range: t('common.pageOf', { from: data.page * data.size + 1, to: data.page * data.size + data.items.length, total: data.total }),
      }} />
  )
}

function ByProduct({ warehouseId, onOpen }: { warehouseId: string | undefined; onOpen: (p: ProductTotal) => void }) {
  const [search, setSearch] = useState('')
  const q = useDebounced(search.trim())
  const [page, setPage] = useState(0)
  const totals = useProductTotals(warehouseId, q, page)
  return (
    <>
      <Input aria-label={t('stock.searchProduct')} placeholder={t('stock.searchProduct')} className="max-w-sm" value={search} maxLength={200}
        onChange={(e) => {
          setSearch(e.target.value)
          setPage(0)
        }} />
      {totals.isError && <FormMessage tone="error">{errorMessage(totals.error)}</FormMessage>}
      <Table>
        <thead>
          <tr>
            <Th>{t('stock.col.product')}</Th>
            <Th className="text-right">{t('stock.col.onHand')}</Th>
            <Th className="hidden text-right sm:table-cell">{t('stock.col.reserved')}</Th>
            <Th className="hidden text-right sm:table-cell">{t('stock.col.inReceiving')}</Th>
            <Th className="hidden text-right md:table-cell">{t('stock.col.inDispatch')}</Th>
            <Th className="hidden text-right sm:table-cell">{t('stock.col.bins')}</Th>
          </tr>
        </thead>
        <tbody>
          {totals.data?.items.map((p) => (
            <Tr key={p.productId}>
              <td>
                <button type="button" className="text-left underline-offset-2 hover:underline" onClick={() => onOpen(p)}>
                  <span className="font-mono text-sm font-semibold">{p.sku}</span> {p.name}
                </button>
              </td>
              <td className="text-right tabular-nums">{num(p.onHand)} <span className="text-xs text-muted-foreground">{p.baseUom}</span></td>
              <td className="hidden text-right tabular-nums sm:table-cell">{num(p.reserved)}</td>
              <td className="hidden text-right tabular-nums sm:table-cell">{num(p.inReceiving)}</td>
              <td className="hidden text-right tabular-nums md:table-cell">{num(p.inDispatch)}</td>
              <td className="hidden text-right tabular-nums sm:table-cell">{p.bins}</td>
            </Tr>
          ))}
          {totals.data && totals.data.items.length === 0 && (
            <tr><td colSpan={4} className="py-8 text-center text-muted-foreground">{t('stock.empty')}</td></tr>
          )}
        </tbody>
      </Table>
      <Paging data={totals.data} onPage={setPage} />
    </>
  )
}

function Locations({ warehouseId, product, onBack }: { warehouseId: string | undefined; product: ProductTotal; onBack: () => void }) {
  const [page, setPage] = useState(0)
  const rows = useStockRows(warehouseId, { product: product.productId }, page)
  return (
    <>
      <button type="button" className="justify-self-start text-sm text-muted-foreground hover:text-foreground" onClick={onBack}>
        ← {t('stock.allProducts')}
      </button>
      <p><span className="font-mono font-semibold">{product.sku}</span> {product.name} · {num(product.onHand)} {product.baseUom}</p>
      <StockRows data={rows.data} error={rows.error} showProduct={false} />
      <Paging data={rows.data} onPage={setPage} />
    </>
  )
}

function ByBin({ warehouseId }: { warehouseId: string | undefined }) {
  const [search, setSearch] = useState('')
  const binCode = useDebounced(search.trim().toUpperCase())
  const [receiving, setReceiving] = useState(false)
  const [page, setPage] = useState(0)
  const rows = useStockRows(warehouseId, { binCode: receiving ? undefined : binCode, receiving }, page)
  return (
    <>
      <div className="flex flex-wrap items-center gap-3">
        <Input aria-label={t('bins.search')} placeholder={t('bins.search')} className="max-w-xs font-mono uppercase" value={search}
          maxLength={40} disabled={receiving} onChange={(e) => {
            setSearch(e.target.value)
            setPage(0)
          }} />
        <label className="flex items-center gap-2 text-sm">
          <input type="checkbox" checked={receiving} onChange={(e) => {
            setReceiving(e.target.checked)
            setPage(0)
          }} /> {t('stock.onlyReceiving')}
        </label>
      </div>
      <StockRows data={rows.data} error={rows.error} showProduct />
      <Paging data={rows.data} onPage={setPage} />
    </>
  )
}

function StockRows({ data, error, showProduct }: {
  data: ReturnType<typeof useStockRows>['data']; error: unknown; showProduct: boolean
}) {
  return (
    <>
      {error ? <FormMessage tone="error">{errorMessage(error)}</FormMessage> : null}
      <Table>
        <thead>
          <tr>
            <Th>{t('stock.col.place')}</Th>
            {showProduct && <Th>{t('stock.col.product')}</Th>}
            <Th className="hidden md:table-cell">{t('stock.col.lot')}</Th>
            <Th className="hidden text-right sm:table-cell">{t('stock.col.reserved')}</Th>
            <Th className="text-right">{t('stock.col.qty')}</Th>
          </tr>
        </thead>
        <tbody>
          {data?.items.map((s, i) => (
            <Tr key={i}>
              <td className="font-mono text-sm">{place(s.binCode)}</td>
              {showProduct && <td><span className="font-mono text-xs">{s.sku}</span> {s.name}</td>}
              <td className="hidden text-sm text-muted-foreground md:table-cell">{[s.batchNo, s.expiryDate].filter(Boolean).join(' · ') || '—'}</td>
              <td className="hidden text-right tabular-nums sm:table-cell">{num(s.qtyReserved)}</td>
              <td className="text-right tabular-nums">{num(s.qtyOnHand)} <span className="text-xs text-muted-foreground">{s.baseUom}</span></td>
            </Tr>
          ))}
          {data && data.items.length === 0 && (
            <tr><td colSpan={showProduct ? 5 : 4} className="py-8 text-center text-muted-foreground">{t('stock.empty')}</td></tr>
          )}
        </tbody>
      </Table>
    </>
  )
}

function Movements({ warehouseId }: { warehouseId: string | undefined }) {
  const [type, setType] = useState('')
  const [from, setFrom] = useState('')
  const [to, setTo] = useState('')
  const [page, setPage] = useState(0)
  const list = useMovements(warehouseId, { type: type || undefined, from: from || undefined, to: to || undefined }, page)
  return (
    <>
      <div className="flex flex-wrap items-end gap-3 text-sm">
        {warehouseId && <span className="order-last ml-auto"><ExportLinks what="movements" params={{ warehouse: warehouseId, type: type || undefined, from: from || undefined, to: to || undefined }} /></span>}
        <label className="grid gap-1">{t('stock.type')}
          <select className="h-10 rounded-md border border-input bg-background px-3" value={type} onChange={(e) => {
            setType(e.target.value)
            setPage(0)
          }}>
            <option value="">{t('audit.filter.all')}</option>
            {TYPES.map((k) => <option key={k} value={k}>{t(`stock.move.${k}` as MessageKey)}</option>)}
          </select>
        </label>
        <label className="grid gap-1">{t('audit.filter.from')}
          <Input type="date" value={from} onChange={(e) => {
            setFrom(e.target.value)
            setPage(0)
          }} />
        </label>
        <label className="grid gap-1">{t('audit.filter.to')}
          <Input type="date" value={to} onChange={(e) => {
            setTo(e.target.value)
            setPage(0)
          }} />
        </label>
      </div>
      {list.isError && <FormMessage tone="error">{errorMessage(list.error)}</FormMessage>}
      <Table>
        <thead>
          <tr>
            <Th>{t('stock.col.when')}</Th>
            <Th>{t('stock.type')}</Th>
            <Th>{t('stock.col.product')}</Th>
            <Th className="hidden md:table-cell">{t('stock.col.fromTo')}</Th>
            <Th className="text-right">{t('stock.col.qty')}</Th>
            <Th className="hidden lg:table-cell">{t('audit.col.user')}</Th>
          </tr>
        </thead>
        <tbody>
          {list.data?.items.map((m) => (
            <Tr key={m.id}>
              <td className="whitespace-nowrap text-xs">{new Date(m.at).toLocaleString()}</td>
              <td className="text-sm">{t(`stock.move.${m.type}` as MessageKey)}</td>
              <td className="text-sm"><span className="font-mono text-xs">{m.sku}</span> {m.name}
                {m.reason && <div className="text-xs text-muted-foreground">{m.reason}</div>}
              </td>
              <td className="hidden font-mono text-xs md:table-cell">{place(m.from)} → {place(m.to)}</td>
              <td className="text-right tabular-nums">{num(m.qty)} <span className="text-xs text-muted-foreground">{m.baseUom}</span></td>
              <td className="hidden text-sm lg:table-cell">{m.user ?? '—'}</td>
            </Tr>
          ))}
          {list.data && list.data.items.length === 0 && (
            <tr><td colSpan={6} className="py-8 text-center text-muted-foreground">{t('stock.noMovements')}</td></tr>
          )}
        </tbody>
      </Table>
      <Paging data={list.data} onPage={setPage} />
    </>
  )
}
