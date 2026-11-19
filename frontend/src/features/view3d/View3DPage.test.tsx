import { QueryClient, QueryClientProvider } from '@tanstack/react-query'
import { render, screen } from '@testing-library/react'
import userEvent from '@testing-library/user-event'
import { createMemoryRouter, RouterProvider } from 'react-router'
import { afterEach, beforeEach, expect, it, vi } from 'vitest'
import { View3DPage } from './View3DPage'

vi.mock('./live', () => ({ useLiveBins: () => 'live' })) // no sockets in unit tests
// The fake canvas reports "slow" when the page passes onSlow, so the Lite suggestion can be tested.
vi.mock('./SceneCanvas', () => ({
  SceneCanvas: ({ onSlow, drone, walk }: { onSlow?: () => void; drone?: { playing: boolean; speed: number; tour: unknown[]; onStop: () => void } | null;
    walk?: { onMove: (h: { x: number; y: number; angle: number }) => void } | null }) => (
    <p>3D canvas<button type="button" onClick={onSlow}>simulate slow</button>
      {walk && <span>walking<button type="button" onClick={() => walk.onMove({ x: 2000, y: 3000, angle: -90 })}>simulate step</button></span>}
      {drone && <span>drone {drone.playing ? 'flying' : 'paused'} at {drone.speed}x over {drone.tour.length} points<button type="button" onClick={drone.onStop}>simulate landing</button></span>}</p>
  ),
}))

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
  expect(screen.queryByText(/^3D canvas/)).not.toBeInTheDocument()
  expect(screen.queryByRole('link', { name: /Edit layout/ })).not.toBeInTheDocument() // read-only user
})

it('shows the scene with its rack and bin count', async () => {
  sceneReply = Response.json(SCENE)
  renderPage()
  expect(await screen.findByText(/^3D canvas/)).toBeInTheDocument()
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

it('Lite map: never loads the 3D canvas; a rack click shows its bins, a bin click its details', async () => {
  localStorage.setItem('wms.3d.view', 'lite')
  sceneReply = Response.json(SCENE)
  renderPage()
  const map = await screen.findByRole('img', { name: 'Warehouse plan' })
  expect(screen.queryByText(/^3D canvas/)).not.toBeInTheDocument()
  await userEvent.click(map.querySelector('polygon[class*="cursor-pointer"]')!)
  expect(screen.getByRole('region', { name: 'Rack R01' })).toBeInTheDocument()
  await userEvent.click(screen.getByRole('button', { name: 'A-R01-01-02' }))
  expect(await screen.findByRole('complementary', { name: 'Bin A-R01-01-02' })).toBeInTheDocument()
  localStorage.removeItem('wms.3d.view')
})

it('offers the Lite map when 3D is slow, and switching remembers the choice', async () => {
  sceneReply = Response.json(SCENE)
  renderPage()
  await userEvent.click(await screen.findByRole('button', { name: 'simulate slow' }))
  expect(screen.getByRole('alert')).toHaveTextContent('3D looks slow on this computer.')
  await userEvent.click(screen.getByRole('button', { name: 'Use the Lite map' }))
  expect(await screen.findByRole('img', { name: 'Warehouse plan' })).toBeInTheDocument()
  expect(localStorage.getItem('wms.3d.view')).toBe('lite')
  localStorage.removeItem('wms.3d.view')
})

it('drone view: flies, pauses, changes speed, and stops with the button, Esc or at the end of the flight', async () => {
  sceneReply = Response.json(SCENE)
  const user = userEvent.setup()
  renderPage()
  await user.click(await screen.findByRole('button', { name: 'Drone view (fly around)' }))
  expect(screen.getByText(/drone flying at 1x over 16 points/)).toBeInTheDocument() // 13 round the floor, 2 along the rack, the finish
  await user.click(screen.getByRole('button', { name: 'Pause' }))
  expect(screen.getByText(/drone paused/)).toBeInTheDocument()
  await user.click(screen.getByRole('button', { name: 'Play' }))
  await user.click(screen.getByRole('button', { name: '2×' }))
  expect(screen.getByText(/drone flying at 2x/)).toBeInTheDocument()
  await user.click(screen.getByRole('button', { name: 'Stop the drone' }))
  expect(screen.queryByText(/^drone/)).not.toBeInTheDocument()
  await user.click(screen.getByRole('button', { name: 'Drone view (fly around)' }))
  await user.keyboard('{Escape}')
  expect(screen.queryByText(/^drone/)).not.toBeInTheDocument()
  await user.click(screen.getByRole('button', { name: 'Drone view (fly around)' }))
  await user.click(screen.getByRole('button', { name: 'simulate landing' }))
  expect(screen.queryByRole('group', { name: 'Drone view' })).not.toBeInTheDocument()
})

it('walks through the warehouse: keys hint, a "you are here" map, and Esc stops it', async () => {
  sceneReply = Response.json(SCENE)
  renderPage()
  await userEvent.click(await screen.findByRole('button', { name: 'Walk through (first person)' }))
  expect(screen.getByText(/^walking/)).toBeInTheDocument()
  expect(screen.getByText(/W A S D or arrows to walk/)).toBeInTheDocument()
  await userEvent.click(screen.getByRole('button', { name: 'simulate step' }))
  expect(screen.getByRole('img', { name: 'You are here' })).toBeInTheDocument()
  expect(screen.getAllByText('You are here').length).toBeGreaterThan(0) // the dot on the small map
  await userEvent.keyboard('{Escape}')
  expect(screen.queryByText(/^walking/)).not.toBeInTheDocument()
  // the Lite map keeps the last place
  await userEvent.click(screen.getByRole('radio', { name: /Lite/ }))
  expect(screen.getByText('You are here')).toBeInTheDocument()
})
