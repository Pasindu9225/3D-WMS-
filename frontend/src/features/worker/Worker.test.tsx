import { QueryClient, QueryClientProvider } from '@tanstack/react-query'
import { render, screen } from '@testing-library/react'
import userEvent from '@testing-library/user-event'
import { createMemoryRouter, RouterProvider } from 'react-router'
import { afterEach, beforeEach, expect, it, vi } from 'vitest'
import { ScanButton } from './Scanner'
import { WorkerHome } from './WorkerHome'

const page = <T,>(items: T[], total = items.length) => ({ items, page: 0, size: 25, total })

beforeEach(() => {
  vi.stubGlobal('fetch', vi.fn(async (url: string) => {
    if (url === '/api/v1/auth/me') {
      return Response.json({ id: 'u', email: 'r@b.lk', fullName: 'Kamal', roles: ['RECEIVER'],
        permissions: ['catalog:read', 'inbound:read', 'inbound:receive', 'putaway:execute', 'inventory:read'] })
    }
    if (url.startsWith('/api/v1/warehouses')) return Response.json(page([{ id: 'w1', code: 'MAIN', name: 'Main' }]))
    if (url.startsWith('/api/v1/purchase-orders?receivable=true')) return Response.json(page([], 2))
    if (url.startsWith('/api/v1/putaway?')) return Response.json(page([], 3))
    if (url.startsWith('/api/v1/adjustments')) return Response.json(page([], 5))
    return new Response(null, { status: 404 })
  }))
})
afterEach(() => vi.unstubAllGlobals())

function renderAt(element: React.ReactNode) {
  const router = createMemoryRouter([{ path: '/worker', element }], { initialEntries: ['/worker'] })
  render(
    <QueryClientProvider client={new QueryClient({ defaultOptions: { queries: { retry: false } } })}>
      <RouterProvider router={router} />
    </QueryClientProvider>,
  )
}

it('shows what is waiting and only the jobs this person may do', async () => {
  renderAt(<WorkerHome />)
  expect(await screen.findByText('Orders to receive: 2')).toBeInTheDocument()
  expect(await screen.findByText('Items to put away: 3')).toBeInTheDocument()
  expect(screen.queryByText(/adjustments to approve/)).not.toBeInTheDocument() // a receiver does not approve
  expect(screen.getByRole('link', { name: 'Receive' })).toHaveAttribute('href', '/worker/receive')
  expect(screen.getByRole('link', { name: 'Put away' })).toBeInTheDocument()
  expect(screen.queryByRole('link', { name: 'Move stock' })).not.toBeInTheDocument()
})

it('reads a code with the browser barcode detector and hands it back, then stops the camera', async () => {
  const stop = vi.fn()
  const stream = { getTracks: () => [{ stop }] } as unknown as MediaStream
  Object.defineProperty(navigator, 'mediaDevices', { configurable: true, value: { getUserMedia: vi.fn(async () => stream) } })
  vi.spyOn(HTMLMediaElement.prototype, 'play').mockResolvedValue(undefined)
  vi.stubGlobal('BarcodeDetector', class {
    async detect() {
      return [{ rawValue: 'A-02-01-01' }]
    }
  })
  const onCode = vi.fn()
  const user = userEvent.setup()
  render(<ScanButton label="Scan with the camera" onCode={onCode} />)
  await user.click(screen.getByRole('button', { name: 'Scan with the camera' }))
  await vi.waitFor(() => expect(onCode).toHaveBeenCalledWith('A-02-01-01'))
  expect(screen.queryByRole('dialog')).not.toBeInTheDocument()
  expect(stop).toHaveBeenCalled()
})
