import { QueryClient, QueryClientProvider } from '@tanstack/react-query'
import { render, screen } from '@testing-library/react'
import userEvent from '@testing-library/user-event'
import { createMemoryRouter, RouterProvider } from 'react-router'
import { afterEach, beforeEach, expect, it, vi } from 'vitest'
import { View3DPage } from './View3DPage'

vi.mock('./SceneCanvas', () => ({ SceneCanvas: () => <p>3D canvas</p> }))

let sceneReply: Response
const SCENE = { publishedAt: 'x', floor: [0, 0, 10000, 0, 10000, 10000, 0, 10000], openings: [], zones: [],
  racks: [{ code: 'R01', zone: 'A', x: 5000, y: 5000, rotation: 0, widthMm: 2000, depthMm: 1000, heightMm: 4000, binsPerLevel: 2, levels: [0, 2000] }],
  bins: { ids: ['a', 'b2'], codes: ['A-R01-01-01', 'A-R01-01-02'], rack: [0, 0], level: [0, 0], pos: [0, 1] }, flags: '02' }
beforeEach(() => {
  vi.stubGlobal('fetch', vi.fn(async (url: string) => {
    if (url === '/api/v1/auth/me') return Response.json({ id: 'u', email: 'a@b.lk', displayName: 'A', permissions: ['warehouses:read'] })
    if (url === '/api/v1/warehouses/W1') return Response.json({ id: 'W1', code: 'WH1', name: 'Main', layoutStatus: 'DRAFT' })
    if (url === '/api/v1/warehouses/W1/scene') return sceneReply.clone()
    if (url.startsWith('/api/v1/warehouses/W1/bins')) return Response.json({ items: [{ id: 'b2', code: 'A-R01-01-02', maxWeightG: 500_000, maxVolumeCm3: 1_000_000, customCapacity: false, pickFace: true, blocked: false }], page: 0, size: 50, total: 1 })
    return new Response(null, { status: 404 })
  }))
})
afterEach(() => vi.unstubAllGlobals())

function renderPage() {
  const router = createMemoryRouter([{ path: '/app/warehouses/:id/3d', element: <View3DPage /> }],
    { initialEntries: ['/app/warehouses/W1/3d'] })
  render(
    <QueryClientProvider client={new QueryClient({ defaultOptions: { queries: { retry: false } } })}>
      <RouterProvider router={router} />
    </QueryClientProvider>,
  )
}

it('explains when there is no published layout and does not load the 3D engine', async () => {
  sceneReply = Response.json({ code: 'NOT_PUBLISHED', title: 'Not found' }, { status: 404 })
  renderPage()
  expect(await screen.findByText(/no published layout yet/)).toBeInTheDocument()
  expect(screen.queryByText('3D canvas')).not.toBeInTheDocument()
  expect(screen.queryByRole('link', { name: /Edit layout/ })).not.toBeInTheDocument() // read-only user
})

it('shows the scene with its rack and bin count', async () => {
  sceneReply = Response.json(SCENE)
  renderPage()
  expect(await screen.findByText('3D canvas')).toBeInTheDocument()
  expect(screen.getByText('1 racks · 2 bins')).toBeInTheDocument()
})

it('finds a bin by its code and shows its details', async () => {
  sceneReply = Response.json(SCENE)
  renderPage()
  const box = await screen.findByRole('combobox', { name: 'Find bin' })
  await userEvent.type(box, 'a-r01-01-02{Enter}')
  expect(await screen.findByRole('complementary', { name: 'Bin A-R01-01-02' })).toBeInTheDocument()
  expect(await screen.findByText('500 kg')).toBeInTheDocument()
  expect(screen.getByText('Pick face')).toBeInTheDocument()
  expect(screen.getByText('2 of 2')).toBeInTheDocument() // slot

  await userEvent.clear(box)
  await userEvent.type(box, 'Z9{Enter}')
  expect(await screen.findByText('No bin starts with Z9.')).toBeInTheDocument()
})
