import { QueryClient, QueryClientProvider } from '@tanstack/react-query'
import { render, screen, waitFor } from '@testing-library/react'
import userEvent from '@testing-library/user-event'
import { createMemoryRouter, RouterProvider } from 'react-router'
import { afterEach, beforeEach, expect, it, vi } from 'vitest'
import { PickPage } from './PickPage'

const line = (seq: number, bin: string, toPick: number, picked = 0, status = 'OPEN') => ({
  seq, soLineNo: 1, sku: 'OIL-1L', productName: 'Coconut oil', baseUom: 'EA', binCode: bin, batchNo: null, expiryDate: null,
  qtyToPick: toPick, qtyPicked: picked, status, shortReason: status === 'SHORT' ? 'NOT_THERE' : null,
})
let list: Record<string, unknown>
const calls: { url: string; body: unknown; key?: string }[] = []

beforeEach(() => {
  calls.length = 0
  list = { id: 'pl1', number: 'PL-000001', status: 'OPEN', orderId: 'so1', orderNumber: 'SO-000001', customerName: 'Kandy Super',
    warehouseCode: 'MAIN', lines: [line(1, 'A-01-01-01', 14), line(2, 'A-01-01-03', 10)], openLines: 2 }
  document.cookie = 'XSRF-TOKEN=tok'
  vi.stubGlobal('fetch', vi.fn(async (url: string, init: RequestInit = {}) => {
    if (url === '/api/v1/pick-lists/pl1') return Response.json(list)
    if (init.method === 'POST') {
      calls.push({ url, body: JSON.parse(init.body as string), key: (init.headers as Record<string, string>)['Idempotency-Key'] })
      if (url.endsWith('/1/confirm')) list = { ...list, status: 'IN_PROGRESS', openLines: 1, lines: [line(1, 'A-01-01-01', 14, 14, 'PICKED'), line(2, 'A-01-01-03', 10)] }
      if (url.endsWith('/2/confirm')) list = { ...list, lines: [line(1, 'A-01-01-01', 14, 14, 'PICKED'), line(2, 'A-01-01-03', 10, 4)] }
      if (url.endsWith('/2/short')) list = { ...list, status: 'DONE', openLines: 0, lines: [line(1, 'A-01-01-01', 14, 14, 'PICKED'), line(2, 'A-01-01-03', 10, 4, 'SHORT')] }
      return Response.json(list)
    }
    return new Response(null, { status: 404 })
  }))
})
afterEach(() => vi.unstubAllGlobals())

it('walks the list: scan the bin and product, confirm, then mark the next line short after taking what was there', async () => {
  const user = userEvent.setup()
  const router = createMemoryRouter([{ path: '/worker/pick/:id', Component: PickPage }], { initialEntries: ['/worker/pick/pl1'] })
  render(
    <QueryClientProvider client={new QueryClient({ defaultOptions: { queries: { retry: false } } })}>
      <RouterProvider router={router} />
    </QueryClientProvider>,
  )
  expect(await screen.findByText('A-01-01-01', { selector: 'span.text-2xl' })).toBeInTheDocument()
  const confirm = screen.getByRole('button', { name: 'Picked' })
  await user.type(screen.getByLabelText('Scan the bin label'), 'a-01-01-02')
  expect(screen.getByText('Wrong bin: go to A-01-01-01')).toBeInTheDocument()
  expect(confirm).toBeDisabled()
  await user.clear(screen.getByLabelText('Scan the bin label'))
  await user.type(screen.getByLabelText('Scan the bin label'), 'a-01-01-01')
  await user.type(screen.getByLabelText('Scan the product'), '4791')
  expect(screen.getByLabelText('Quantity')).toHaveValue('14') // what is left, by default
  await user.click(confirm)
  await waitFor(() => expect(calls[0]).toMatchObject({ url: '/api/v1/pick-lists/pl1/lines/1/confirm', body: { binCode: 'a-01-01-01', code: '4791', qty: 14 } }))
  expect(calls[0]?.key).toMatch(/^[0-9a-f-]{36}$/)

  expect(await screen.findByText('A-01-01-03', { selector: 'span.text-2xl' })).toBeInTheDocument()
  await user.click(screen.getByRole('button', { name: 'Fewer here' }))
  const found = screen.getByLabelText('How many did you find? (EA)')
  await user.clear(found)
  await user.type(found, '4')
  await user.selectOptions(screen.getByLabelText('Why'), 'DAMAGED')
  await user.click(screen.getByRole('button', { name: 'Mark short' }))
  await waitFor(() => expect(calls.map((c) => c.url)).toEqual([
    '/api/v1/pick-lists/pl1/lines/1/confirm', '/api/v1/pick-lists/pl1/lines/2/confirm', '/api/v1/pick-lists/pl1/lines/2/short']))
  expect(calls[1]?.body).toEqual({ binCode: 'A-01-01-03', code: 'OIL-1L', qty: 4 })
  expect(calls[2]?.body).toEqual({ reason: 'DAMAGED' })
  expect(await screen.findByText(/Everything on this list is done/)).toBeInTheDocument()
})
