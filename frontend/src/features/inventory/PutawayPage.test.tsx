import { QueryClient, QueryClientProvider } from '@tanstack/react-query'
import { render, screen, waitFor } from '@testing-library/react'
import userEvent from '@testing-library/user-event'
import { afterEach, beforeEach, expect, it, vi } from 'vitest'
import { PutawayPage } from './PutawayPage'

let posted: { body: Record<string, unknown>; key: string } | undefined
const waiting = { productId: 'p1', sku: 'OIL-1L', name: 'Coconut oil', baseUom: 'EA', batchNo: null, expiryDate: '2027-03-31', qty: 24, receivedAt: '' }

beforeEach(() => {
  posted = undefined
  document.cookie = 'XSRF-TOKEN=tok'
  vi.stubGlobal('fetch', vi.fn(async (url: string, init: RequestInit = {}) => {
    if (url.startsWith('/api/v1/warehouses')) return Response.json({ items: [{ id: 'w1', code: 'MAIN', name: 'Main' }], page: 0, size: 100, total: 1 })
    if (url.startsWith('/api/v1/putaway?')) return Response.json({ items: posted ? [] : [waiting], page: 0, size: 100, total: 1 })
    if (url === '/api/v1/putaway/suggestions') {
      return Response.json([{ binId: 'b1', code: 'A-R01-01-01', zoneType: 'STORAGE', level: 1, why: 'EMPTY_STORAGE', distanceM: 4.2 }])
    }
    if (url === '/api/v1/putaway' && init.method === 'POST') {
      posted = { body: JSON.parse(init.body as string), key: (init.headers as Record<string, string>)['Idempotency-Key'] ?? '' }
      return Response.json({ binCode: 'A-R01-01-02', qty: 10, leftInReceiving: 14, alreadyDone: false })
    }
    return new Response(null, { status: 404 })
  }))
})
afterEach(() => vi.unstubAllGlobals())

it('suggests a bin, accepts another scanned bin with a note, and puts away the chosen quantity', async () => {
  const user = userEvent.setup()
  render(
    <QueryClientProvider client={new QueryClient({ defaultOptions: { queries: { retry: false } } })}>
      <PutawayPage />
    </QueryClientProvider>,
  )
  await user.click(await screen.findByRole('button', { name: /OIL-1L/ }))
  const qty = screen.getByLabelText('Quantity to put away (EA)')
  await user.clear(qty)
  await user.type(qty, '10')
  expect(await screen.findByText('A-R01-01-01')).toBeInTheDocument()
  await user.type(screen.getByLabelText('Scan the bin label'), 'a-r01-01-02')
  expect(screen.getByText(/This is not a suggested bin/)).toBeInTheDocument()
  await user.click(screen.getByRole('button', { name: 'Put away here' }))
  expect(await screen.findByText('Put 10 EA in A-R01-01-02. 14 left in receiving.')).toBeInTheDocument()
  await waitFor(() => expect(posted?.body).toEqual({
    warehouseId: 'w1', productId: 'p1', batchNo: null, expiryDate: '2027-03-31', qty: 10, binCode: 'A-R01-01-02',
  }))
  expect(posted?.key).toMatch(/^[0-9a-f-]{36}$/)
  expect(screen.getByText('Nothing is waiting to be put away.')).toBeInTheDocument()
})
