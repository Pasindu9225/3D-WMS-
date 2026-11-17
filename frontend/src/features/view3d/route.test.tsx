import { render, screen } from '@testing-library/react'
import { expect, it } from 'vitest'
import type { Scene } from './api'
import { LiteMap } from './LiteMap'
import { parseRoute, routePoints, type PickRoute } from './route'

const route: PickRoute = { warehouseId: 'w1', startX: 2000, startY: 10000, metres: 96.4, stops: [
  { step: 1, x: 10000, y: 9000, binCodes: ['A-R02-01-01'] },
  { step: 2, x: 30000, y: 9000, binCodes: ['A-R03-01-01'] },
] }

it('reads only list or wave routes from the address', () => {
  expect(parseRoute('list:01ABC')).toEqual({ kind: 'list', id: '01ABC' })
  expect(parseRoute('wave:01XYZ')).toEqual({ kind: 'wave', id: '01XYZ' })
  expect(parseRoute('order:1')).toBeNull()
  expect(parseRoute('list:../../users')).toBeNull()
  expect(parseRoute(null)).toBeNull()
})

it('walks from the start through every stop and back', () => {
  expect(routePoints(route)).toEqual([{ x: 2000, y: 10000 }, { x: 10000, y: 9000 }, { x: 30000, y: 9000 }, { x: 2000, y: 10000 }])
})

it('draws the route on the Lite map with numbered stops', () => {
  const scene = { floor: [0, 0, 40000, 0, 40000, 20000, 0, 20000], zones: [], openings: [], racks: [], bins: { rack: [] }, flags: '' } as unknown as Scene
  const { container } = render(<LiteMap scene={scene} colors={[]} selectedBin={null} selectedRack={null} onRack={() => {}} route={route} />)
  expect(screen.getByRole('img', { name: 'Route: 2 stops · about 96 m' })).toBeInTheDocument()
  expect(container.querySelector('polyline')?.getAttribute('points')).toBe('2000,10000 10000,9000 30000,9000 2000,10000')
  expect(screen.getByText('1')).toBeInTheDocument()
  expect(screen.getByText('2')).toBeInTheDocument()
})
