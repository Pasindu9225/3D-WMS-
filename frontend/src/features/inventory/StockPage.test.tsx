import { QueryClient, QueryClientProvider } from '@tanstack/react-query'
import { render, screen } from '@testing-library/react'
import userEvent from '@testing-library/user-event'
import { afterEach, beforeEach, expect, it, vi } from 'vitest'
import { StockPage } from './StockPage'

const page = <T,>(items: T[]) => ({ items, page: 0, size: 50, total: items.length })
let urls: string[]

beforeEach(() => {
  urls = []
  vi.stubGlobal('fetch', vi.fn(async (url: string) => {
    urls.push(url)
    if (url.startsWith('/api/v1/warehouses')) return Response.json(page([{ id: 'w1', code: 'MAIN', name: 'Main' }]))
    if (url.startsWith('/api/v1/stock/products')) {
      return Response.json(page([{ productId: 'p1', sku: 'OIL-1L', name: 'Coconut oil', baseUom: 'EA', onHand: 24, inReceiving: 14, bins: 1, reserved: 0, inDispatch: 0 }]))
    }
    if (url.startsWith('/api/v1/stock?')) {
      return Response.json(page([
        { productId: 'p1', sku: 'OIL-1L', name: 'Coconut oil', baseUom: 'EA', binId: 'b1', binCode: 'A-R01-01-01', batchNo: null, expiryDate: '2027-03-31', qtyOnHand: 10, qtyReserved: 0, receivedAt: '' },
        { productId: 'p1', sku: 'OIL-1L', name: 'Coconut oil', baseUom: 'EA', binId: null, binCode: 'RECEIVING', batchNo: null, expiryDate: '2027-03-31', qtyOnHand: 14, qtyReserved: 0, receivedAt: '' },
      ]))
    }
    if (url.startsWith('/api/v1/stock-movements')) {
      return Response.json(page([{ id: 2, type: 'PUTAWAY', sku: 'OIL-1L', name: 'Coconut oil', baseUom: 'EA', batchNo: null, expiryDate: null,
        from: 'RECEIVING', to: 'A-R01-01-01', qty: 10, reason: null, refType: null, user: 'Kamal', at: '2026-11-16T03:00:00Z' }]))
    }
    return new Response(null, { status: 404 })
  }))
})
afterEach(() => vi.unstubAllGlobals())

it('shows totals per product, where a product is, and the movement history', async () => {
  const user = userEvent.setup()
  render(
    <QueryClientProvider client={new QueryClient({ defaultOptions: { queries: { retry: false } } })}>
      <StockPage />
    </QueryClientProvider>,
  )
  await user.click(await screen.findByRole('button', { name: /OIL-1L/ }))
  expect(await screen.findByText('A-R01-01-01')).toBeInTheDocument()
  expect(screen.getByText('Receiving area')).toBeInTheDocument()
  expect(urls).toContain('/api/v1/stock?warehouse=w1&page=0&size=50&product=p1')

  await user.click(screen.getByRole('tab', { name: 'Movements' }))
  expect(await screen.findByRole('cell', { name: 'Put away' })).toBeInTheDocument()
  expect(screen.getByText('Receiving area → A-R01-01-01')).toBeInTheDocument()
})
