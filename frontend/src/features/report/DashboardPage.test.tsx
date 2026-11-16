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
