import { QueryClient, QueryClientProvider } from '@tanstack/react-query'
import { render, screen, waitFor } from '@testing-library/react'
import userEvent from '@testing-library/user-event'
import { createMemoryRouter, RouterProvider } from 'react-router'
import { afterEach, beforeEach, expect, it, vi } from 'vitest'
import { bytes, lkr } from './api'
import { CompaniesPage } from './CompaniesPage'
import { CompanyPage } from './CompanyPage'
import { PlansPage } from './PlansPage'

const calls: { url: string; method: string; body: unknown }[] = []
let status = 'ACTIVE'

const company = () => ({ id: 'c1', name: 'Acme Traders', planCode: 'PRO', maxWarehouses: 3, status, createdAt: '2026-11-01T00:00:00Z',
  suspendedAt: status === 'SUSPENDED' ? '2026-11-18T01:00:00Z' : null, suspendedReason: status === 'SUSPENDED' ? 'Unpaid invoice' : null,
  usage: { warehouses: 2, activeUsers: 5, products: 120, movements30Days: 3400, storedBytes: 5_400_000, lastActive: '2026-11-17T10:00:00Z' }, version: 4 })

beforeEach(() => {
  calls.length = 0
  status = 'ACTIVE'
  document.cookie = 'XSRF-TOKEN=tok'
  vi.stubGlobal('fetch', vi.fn(async (url: string, init: RequestInit = {}) => {
    if (url === '/api/v1/auth/me') return Response.json({ id: 'sa', email: 'sa@platform.lk', displayName: 'SA', permissions: ['platform:read', 'platform:manage'] })
    if (init.method && init.method !== 'GET') {
      calls.push({ url, method: init.method, body: JSON.parse(init.body as string) })
      if (url.endsWith('/suspend')) status = 'SUSPENDED'
      return Response.json(company())
    }
    if (url.startsWith('/api/v1/admin/tenants/c1')) return Response.json(company())
    if (url.startsWith('/api/v1/admin/tenants')) {
      return Response.json({ page: 0, size: 25, total: 2, items: [
        { id: 'c1', name: 'Acme Traders', planCode: 'PRO', maxWarehouses: 3, status: 'ACTIVE', createdAt: '2026-11-01T00:00:00Z', warehouses: 2, users: 5, version: 4 },
        { id: 'c2', name: 'Kandy Foods', planCode: 'PILOT', maxWarehouses: 1, status: 'SUSPENDED', createdAt: '2026-11-10T00:00:00Z', warehouses: 1, users: 2, version: 1 },
      ] })
    }
    if (url === '/api/v1/admin/plans') {
      return Response.json([{ code: 'PILOT', name: 'Pilot (free)', pricePerWarehouseMinor: 0, defaultMaxWarehouses: 1, active: true, companies: 1, version: 0 },
        { code: 'PRO', name: 'Pro', pricePerWarehouseMinor: 500000, defaultMaxWarehouses: 3, active: true, companies: 1, version: 0 }])
    }
    return new Response(null, { status: 404 })
  }))
})
afterEach(() => vi.unstubAllGlobals())

function show(path: string, element: React.ReactNode, route = path) {
  const router = createMemoryRouter([{ path: route, element }], { initialEntries: [path] })
  render(<QueryClientProvider client={new QueryClient({ defaultOptions: { queries: { retry: false } } })}><RouterProvider router={router} /></QueryClientProvider>)
}

it('lists companies with plan, warehouses used of the limit, users and status', async () => {
  show('/app/admin/companies', <CompaniesPage />)
  expect(await screen.findByRole('link', { name: 'Acme Traders' })).toHaveAttribute('href', '/app/admin/companies/c1')
  expect(screen.getByText('2 of 3')).toBeInTheDocument()
  expect(screen.getByText('Suspended', { selector: 'span' })).toBeInTheDocument() // the badge, not the filter option
})

it('suspends a company with a reason, then shows why', async () => {
  const user = userEvent.setup()
  show('/app/admin/companies/c1', <CompanyPage />, '/app/admin/companies/:id')
  expect(await screen.findByText('3,400')).toBeInTheDocument() // movements, counts only
  expect(screen.getByText('5.1 MB')).toBeInTheDocument()
  await user.click(screen.getByRole('button', { name: 'Suspend' }))
  await user.type(screen.getByLabelText('Reason (shown in the audit log)'), 'Unpaid invoice')
  await user.click(screen.getAllByRole('button', { name: 'Suspend' }).at(-1)!)
  await waitFor(() => expect(calls[0]).toEqual({ url: '/api/v1/admin/tenants/c1/suspend', method: 'POST', body: { version: 4, reason: 'Unpaid invoice' } }))
  expect(await screen.findByText(/Unpaid invoice\. Nobody in this company can sign in\./)).toBeInTheDocument()
  expect(screen.getByRole('button', { name: 'Reactivate' })).toBeInTheDocument()
})

it('adds a plan with a price in rupees', async () => {
  const user = userEvent.setup()
  show('/app/admin/plans', <PlansPage />)
  expect(await screen.findByText('5,000.00')).toBeInTheDocument()
  await user.click(screen.getByRole('button', { name: 'New plan' }))
  await user.type(screen.getByLabelText('Code (letters, digits)'), 'BIG')
  await user.type(screen.getByLabelText('Name'), 'Big')
  await user.type(screen.getByLabelText('Price per warehouse / month (LKR)'), '4500.50')
  await user.clear(screen.getByLabelText('Warehouses included'))
  await user.type(screen.getByLabelText('Warehouses included'), '10')
  await user.click(screen.getByRole('button', { name: 'Save' }))
  await waitFor(() => expect(calls[0]).toEqual({ url: '/api/v1/admin/plans', method: 'POST',
    body: { code: 'BIG', name: 'Big', pricePerWarehouseMinor: 450050, defaultMaxWarehouses: 10 } }))
})

it('formats money and sizes', () => {
  expect(lkr(500000)).toBe('5,000.00')
  expect(bytes(512)).toBe('512 B')
  expect(bytes(5_400_000)).toBe('5.1 MB')
})
