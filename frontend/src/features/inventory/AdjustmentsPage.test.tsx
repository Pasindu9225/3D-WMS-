import { QueryClient, QueryClientProvider } from '@tanstack/react-query'
import { render, screen, waitFor } from '@testing-library/react'
import userEvent from '@testing-library/user-event'
import { afterEach, beforeEach, expect, it, vi } from 'vitest'
import { AdjustmentsPage } from './AdjustmentsPage'

const pending = {
  id: 'a1', number: 'ADJ-000002', status: 'PENDING', warehouseCode: 'MAIN', binCode: null, sku: 'OIL-1L', productName: 'Oil',
  baseUom: 'EA', batchNo: null, expiryDate: null, direction: 'OUT', qty: 23, reason: 'LOST', note: 'shelf check', valueMinor: 1035000,
  requestedBy: 'Kamal', requestedAt: '', decidedBy: null, decidedAt: null, decisionNote: null, version: 0,
}
let requests: { url: string; body?: unknown; key?: string }[]

beforeEach(() => {
  requests = []
  document.cookie = 'XSRF-TOKEN=tok'
  vi.stubGlobal('fetch', vi.fn(async (url: string, init: RequestInit = {}) => {
    const body = init.body ? JSON.parse(init.body as string) : undefined
    if (init.method === 'POST') requests.push({ url, body, key: (init.headers as Record<string, string>)['Idempotency-Key'] })
    if (url === '/api/v1/auth/me') {
      return Response.json({ id: 'u', email: 'a@b.lk', fullName: 'A', roles: [], permissions: ['inventory:read', 'inventory:adjust', 'inventory:approve'] })
    }
    if (url.startsWith('/api/v1/adjustments?')) return Response.json({ items: [pending], page: 0, size: 25, total: 1 })
    if (url === '/api/v1/adjustments/a1/approve') return Response.json({ ...pending, status: 'APPLIED', version: 1 })
    if (url.startsWith('/api/v1/warehouses')) return Response.json({ items: [{ id: 'w1', code: 'MAIN', name: 'Main' }], page: 0, size: 100, total: 1 })
    if (url.startsWith('/api/v1/products')) {
      return Response.json({ items: [{ id: 'p1', sku: 'OIL-1L', name: 'Oil', baseUom: 'EA', units: [] }], page: 0, size: 25, total: 1 })
    }
    if (url === '/api/v1/adjustments') return Response.json({ ...pending, id: 'a2', number: 'ADJ-000003', status: 'APPLIED' })
    return new Response(null, { status: 404 })
  }))
})
afterEach(() => vi.unstubAllGlobals())

it('lists a pending adjustment for approval and asks for a new one with a reason', async () => {
  const user = userEvent.setup()
  render(
    <QueryClientProvider client={new QueryClient({ defaultOptions: { queries: { retry: false } } })}>
      <AdjustmentsPage />
    </QueryClientProvider>,
  )
  expect(await screen.findByText('10,350.00')).toBeInTheDocument()
  await user.click(screen.getByRole('button', { name: 'Approve ADJ-000002' }))
  await waitFor(() => expect(requests[0]).toMatchObject({ url: '/api/v1/adjustments/a1/approve', body: { version: 0 } }))

  await user.click(screen.getByRole('button', { name: /New adjustment/ }))
  await user.type(screen.getByLabelText('Product'), 'oil')
  await user.click(await screen.findByRole('button', { name: /OIL-1L/ }))
  await user.type(screen.getByLabelText('Quantity (EA)'), '2')
  await user.selectOptions(screen.getByLabelText('Reason'), 'EXPIRED')
  await user.click(screen.getByRole('button', { name: 'Save adjustment' }))
  expect(await screen.findByText('Applied. Stock is updated.')).toBeInTheDocument()
  expect(requests[1]).toMatchObject({
    url: '/api/v1/adjustments',
    body: { warehouseId: 'w1', productId: 'p1', direction: 'OUT', qty: 2, reason: 'EXPIRED' },
  })
  expect(requests[1]?.key).toMatch(/^[0-9a-f-]{36}$/)
})
