import { QueryClient, QueryClientProvider } from '@tanstack/react-query'
import { render, screen, waitFor } from '@testing-library/react'
import userEvent from '@testing-library/user-event'
import { createMemoryRouter, RouterProvider } from 'react-router'
import { afterEach, beforeEach, expect, it, vi } from 'vitest'
import { ReceiptPage } from './ReceivePage'

const line = { lineNo: 1, sku: 'OIL-1L', productName: 'Coconut oil', baseUom: 'EA', ordered: 120, receivedBefore: 0, damagedThisReceipt: 0 }
const receipt = (thisReceipt: number, scans: unknown[]) => ({
  id: 'r1', number: 'GRN-000001', status: 'OPEN', poId: 'po1', poNumber: 'PO-000001', supplierName: 'Lanka Oils',
  warehouseCode: 'MAIN', note: null, createdAt: '2026-11-16T00:00:00Z', closedAt: null,
  lines: [{ ...line, thisReceipt }], scans, version: 0,
})
let keys: string[]
let failNext: boolean

beforeEach(() => {
  keys = []
  failNext = true
  document.cookie = 'XSRF-TOKEN=tok'
  vi.stubGlobal('fetch', vi.fn(async (url: string, init: RequestInit = {}) => {
    if (url === '/api/v1/auth/me') return Response.json({ id: 'u', email: 'a@b.lk', fullName: 'A', roles: [], permissions: ['inbound:read', 'inbound:receive'] })
    if (url === '/api/v1/receipts/r1') return Response.json(receipt(0, []))
    if (url === '/api/v1/receipts/r1/lines' && init.method === 'POST') {
      keys.push((init.headers as Record<string, string>)['Idempotency-Key'] ?? '')
      if (failNext) {
        failNext = false
        throw new TypeError('network down')
      }
      return Response.json(receipt(48, [{ id: 's1', sku: 'OIL-1L', uom: 'CASE', factor: 12, qty: 4, qtyBase: 48, damagedBase: 0, damageNote: null, createdAt: '' }]))
    }
    return new Response(null, { status: 404 })
  }))
})
afterEach(() => vi.unstubAllGlobals())

it('a scan retried after a network failure reuses its key, then the form clears for the next scan', async () => {
  const user = userEvent.setup()
  const router = createMemoryRouter([{ path: '/app/receive/:id', Component: ReceiptPage }], { initialEntries: ['/app/receive/r1'] })
  render(
    <QueryClientProvider client={new QueryClient({ defaultOptions: { queries: { retry: false } } })}>
      <RouterProvider router={router} />
    </QueryClientProvider>,
  )
  await user.type(await screen.findByLabelText('Barcode or SKU'), '14791')
  await user.click(screen.getByRole('button', { name: 'One more' }))
  await user.click(screen.getByRole('button', { name: 'One more' }))
  await user.click(screen.getByRole('button', { name: 'One more' })) // 1 -> 4
  await user.click(screen.getByRole('button', { name: 'Add' }))
  expect(await screen.findByText('Cannot reach the server. Check your connection and try again.')).toBeInTheDocument()

  await user.click(screen.getByRole('button', { name: 'Add' }))
  expect(await screen.findByText('Added 4 of 14791.')).toBeInTheDocument()
  expect(keys).toHaveLength(2)
  expect(keys[1]).toBe(keys[0]) // the server counts it once
  expect(screen.getByLabelText('Barcode or SKU')).toHaveValue('')
  expect(screen.getByText('48 now · 72 still expected of 120 EA')).toBeInTheDocument()
  await waitFor(() => expect(screen.getByLabelText('Quantity')).toHaveValue('1'))
})
