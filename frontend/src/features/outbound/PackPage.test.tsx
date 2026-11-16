import { QueryClient, QueryClientProvider } from '@tanstack/react-query'
import { render, screen, waitFor } from '@testing-library/react'
import userEvent from '@testing-library/user-event'
import { createMemoryRouter, RouterProvider } from 'react-router'
import { afterEach, beforeEach, expect, it, vi } from 'vitest'
import { PackPage } from './PackPage'

let view: Record<string, unknown>
let posted: unknown

beforeEach(() => {
  posted = undefined
  view = {
    id: 'so1', number: 'SO-000006', status: 'PICKED', customerName: 'Kandy Super', customerRef: 'KSM-7000', warehouseCode: 'CMB', version: 4,
    items: [
      { sku: 'OIL-1L', name: 'Coconut oil', baseUom: 'EA', ordered: 24, picked: 24,
        codes: [{ code: 'OIL-1L', uom: 'EA', factor: 1 }, { code: '4791', uom: 'EA', factor: 1 }, { code: '14791', uom: 'CASE', factor: 12 }] },
      { sku: 'RICE', name: 'Samba rice', baseUom: 'BAG', ordered: 3, picked: 2, codes: [{ code: 'RICE', uom: 'BAG', factor: 1 }] },
    ],
  }
  document.cookie = 'XSRF-TOKEN=tok'
  vi.stubGlobal('fetch', vi.fn(async (url: string, init: RequestInit = {}) => {
    if (url === '/api/v1/sales-orders/so1/pack' && init.method === 'POST') {
      posted = JSON.parse(init.body as string)
      view = { ...view, status: 'PACKED', version: 5 }
      return Response.json(view)
    }
    if (url === '/api/v1/sales-orders/so1/pack') return Response.json(view)
    return new Response(null, { status: 404 })
  }))
})
afterEach(() => vi.unstubAllGlobals())

it('counts case scans, refuses too many and strangers, and finishes only when everything matches', async () => {
  const user = userEvent.setup()
  const router = createMemoryRouter([{ path: '/worker/pack/:id', Component: PackPage }], { initialEntries: ['/worker/pack/so1'] })
  render(
    <QueryClientProvider client={new QueryClient({ defaultOptions: { queries: { retry: false } } })}>
      <RouterProvider router={router} />
    </QueryClientProvider>,
  )
  const scan = await screen.findByLabelText('Scan each product as it goes in the box')
  const finish = screen.getByRole('button', { name: 'Finish packing' })
  await user.type(scan, '14791{Enter}')
  expect(screen.getByText('12 / 24 EA')).toBeInTheDocument()
  await user.type(scan, 'MILK{Enter}')
  expect(screen.getByText('MILK is not on this order. Put it aside.')).toBeInTheDocument()
  await user.type(scan, 'rice{Enter}')
  await user.type(scan, 'RICE{Enter}')
  await user.type(scan, 'RICE{Enter}')
  expect(screen.getByText(/more RICE than was picked/)).toBeInTheDocument()
  expect(finish).toBeDisabled()
  await user.type(scan, '14791{Enter}')
  expect(screen.getByText('24 / 24 EA')).toBeInTheDocument()
  expect(finish).toBeEnabled()

  await user.click(finish)
  const cartons = screen.getByLabelText('Cartons')
  await user.clear(cartons)
  await user.type(cartons, '2')
  await user.type(screen.getByLabelText('Total weight in kg (optional)'), '32.5')
  await user.click(screen.getByRole('button', { name: 'Finish and get delivery note' }))
  await waitFor(() => expect(posted).toEqual({ version: 4, cartons: 2, weightG: 32500, counts: [{ sku: 'OIL-1L', qty: 24 }, { sku: 'RICE', qty: 2 }] }))
  expect(await screen.findByRole('link', { name: 'Delivery note' })).toHaveAttribute('href', '/api/v1/sales-orders/so1/delivery-note.pdf')
  expect(screen.getByRole('link', { name: 'Packing slip' })).toHaveAttribute('href', '/api/v1/sales-orders/so1/packing-slip.pdf')
})
