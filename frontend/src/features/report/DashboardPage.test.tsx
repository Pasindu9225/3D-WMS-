import { QueryClient, QueryClientProvider } from '@tanstack/react-query'
import { render, screen } from '@testing-library/react'
import { afterEach, beforeEach, expect, it, vi } from 'vitest'
import { DashboardPage } from './DashboardPage'

beforeEach(() => {
  vi.stubGlobal('fetch', vi.fn(async (url: string) => {
    if (url.startsWith('/api/v1/warehouses')) return Response.json({ items: [{ id: 'w1', code: 'MAIN', name: 'Main' }], page: 0, size: 100, total: 1 })
    if (url === '/api/v1/dashboard?warehouse=w1') {
      return Response.json({
        stockValueMinor: 2_575_000, productsWithoutCost: 1,
        lowStock: [{ productId: 'p2', sku: 'RICE-5KG', name: 'Samba rice', baseUom: 'BAG', onHand: 8, reorderPoint: 10 }],
        today: { receipts: 1, unitsReceived: 48, shipments: 1, unitsShipped: 14, ordersReleased: 2, ordersShipped: 1, adjustments: 0 },
        bins: 18, binsInUse: 3, binUsePercent: 17, averageFillPercent: 58, dockToStockMinutes: 200, linesPerHour: 12.5,
        expiry: { expired: 1, within30: 1, within60: 0, within90: 0, lots: [
          { productId: 'p1', sku: 'OIL-1L', name: 'Coconut oil', baseUom: 'EA', place: 'A-02-01-02', batchNo: null, expiry: '2026-11-14', qty: 4, daysLeft: -3 },
          { productId: 'p1', sku: 'OIL-1L', name: 'Coconut oil', baseUom: 'EA', place: 'RECEIVING', batchNo: 'B7', expiry: '2026-12-07', qty: 12, daysLeft: 20 },
        ] },
      })
    }
    return new Response(null, { status: 404 })
  }))
})
afterEach(() => vi.unstubAllGlobals())

it('shows stock value, bin use, floor speed, today and low stock', async () => {
  render(
    <QueryClientProvider client={new QueryClient({ defaultOptions: { queries: { retry: false } } })}>
      <DashboardPage />
    </QueryClientProvider>,
  )
  expect(await screen.findByText('25,750.00')).toBeInTheDocument()
  expect(screen.getByText('1 products have no cost and are not counted')).toBeInTheDocument()
  expect(screen.getByText('17%')).toBeInTheDocument()
  expect(screen.getByText('3 of 18 bins; those in use are 58% full on average')).toBeInTheDocument()
  expect(screen.getByText('3 h 20 min')).toBeInTheDocument()
  expect(screen.getByText('12.5')).toBeInTheDocument()
  expect(screen.getByText('48 units')).toBeInTheDocument()
  expect(screen.getByText('Low stock (1)')).toBeInTheDocument()
  expect(screen.getByText('RICE-5KG')).toBeInTheDocument()
})

it('lists what expires soon with a count per band, expired ones marked', async () => {
  render(
    <QueryClientProvider client={new QueryClient({ defaultOptions: { queries: { retry: false } } })}>
      <DashboardPage />
    </QueryClientProvider>,
  )
  expect(await screen.findByText('Expiring soon (2)')).toBeInTheDocument()
  expect(screen.getByText('Expired 3 days ago')).toBeInTheDocument()
  expect(screen.getByText('20 days')).toBeInTheDocument()
  expect(screen.getByText('Receiving area')).toBeInTheDocument()
  expect(screen.getByText('B7 · 2026-12-07')).toBeInTheDocument()
})
