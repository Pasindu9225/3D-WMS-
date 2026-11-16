import { QueryClient, QueryClientProvider } from '@tanstack/react-query'
import { render, screen, waitFor } from '@testing-library/react'
import userEvent from '@testing-library/user-event'
import { afterEach, beforeEach, expect, it, vi } from 'vitest'
import { DispatchPage } from './DispatchPage'

const page = <T,>(items: T[]) => ({ items, page: 0, size: 100, total: items.length })
const order = (id: string, number: string, customer: string) => ({
  id, number, status: 'PACKED', customerCode: 'C', customerName: customer, customerRef: null, warehouseCode: 'MAIN',
  requestedDate: null, lineCount: 1, createdAt: '',
})
let posted: unknown

beforeEach(() => {
  posted = undefined
  document.cookie = 'XSRF-TOKEN=tok'
  vi.stubGlobal('fetch', vi.fn(async (url: string, init: RequestInit = {}) => {
    if (url.startsWith('/api/v1/warehouses')) return Response.json(page([{ id: 'w1', code: 'MAIN', name: 'Main' }]))
    if (url.startsWith('/api/v1/sales-orders/to-dispatch')) {
      return Response.json(page(posted ? [] : [order('so1', 'SO-000001', 'Kandy Super'), order('so2', 'SO-000002', 'Galle Stores')]))
    }
    if (url === '/api/v1/shipments' && init.method === 'POST') {
      posted = JSON.parse(init.body as string)
      return Response.json({ id: 'sh1', number: 'SH-000001', warehouseCode: 'MAIN', vehicleNo: 'WP CAB-1234', driverName: null, note: null,
        createdAt: '2026-11-16T10:00:00Z', orders: [{ id: 'so2', number: 'SO-000002', customerName: 'Galle Stores', deliveryNoteNo: 'DN-000002', cartons: 1 }] },
      { status: 201 })
    }
    if (url.startsWith('/api/v1/shipments')) return Response.json(page([]))
    return new Response(null, { status: 404 })
  }))
})
afterEach(() => vi.unstubAllGlobals())

it('sends the ticked packed orders on one vehicle and says which shipment they left on', async () => {
  const user = userEvent.setup()
  render(
    <QueryClientProvider client={new QueryClient({ defaultOptions: { queries: { retry: false } } })}>
      <DispatchPage />
    </QueryClientProvider>,
  )
  const send = await screen.findByRole('button', { name: 'Dispatch selected (0)' })
  expect(send).toBeDisabled()
  await user.click(screen.getByRole('checkbox', { name: /SO-000002/ }))
  await user.type(screen.getByLabelText('Vehicle number (optional)'), 'WP CAB-1234')
  await user.click(screen.getByRole('button', { name: 'Dispatch selected (1)' }))
  await user.click(screen.getByRole('button', { name: 'Dispatch' }))
  await waitFor(() => expect(posted).toEqual({ warehouseId: 'w1', orderIds: ['so2'], vehicleNo: 'WP CAB-1234', driverName: null, note: null }))
  expect(await screen.findByText('Shipment SH-000001 has left. Orders on it: 1.')).toBeInTheDocument()
})
