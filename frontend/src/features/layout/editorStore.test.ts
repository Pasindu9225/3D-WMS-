import { beforeEach, describe, expect, it } from 'vitest'
import type { LayoutView } from '@/features/warehouses/api'
import { useEditor } from './editorStore'

const view: LayoutView = {
  status: 'DRAFT',
  revision: 4,
  layout: {
    floor: [
      { x: 0, y: 0 },
      { x: 20_000, y: 0 },
      { x: 20_000, y: 10_000 },
      { x: 0, y: 10_000 },
    ],
    openings: [],
    zones: [],
    racks: [],
    settings: { minAisleMm: 1200 },
  },
  templates: {},
  warnings: [],
  updatedAt: '2026-11-14T00:00:00Z',
}

describe('editor store', () => {
  beforeEach(() => useEditor.getState().load(view))

  it('loads clean and remembers the revision it started from', () => {
    expect(useEditor.getState()).toMatchObject({ baseRevision: 4, dirty: false })
  })

  it('moves corners on the 0.1 m grid and marks the layout changed', () => {
    useEditor.getState().moveFloorPoint(1, { x: 25_049, y: -30 })
    expect(useEditor.getState().doc?.floor[1]).toEqual({ x: 25_000, y: 0 })
    expect(useEditor.getState().dirty).toBe(true)
  })

  it('keeps at least three corners', () => {
    const s = useEditor.getState()
    s.removeFloorPoint(0)
    useEditor.getState().removeFloorPoint(0)
    expect(useEditor.getState().doc?.floor).toHaveLength(3)
  })

  it('adds doors on the wall, keeps them there when dragged, and selects the new one', () => {
    useEditor.getState().addOpening('DOCK')
    let opening = useEditor.getState().doc!.openings[0]!
    expect(opening).toMatchObject({ kind: 'DOCK', label: 'Dock 1', y: 0, widthMm: 3000 })
    expect(useEditor.getState().selection).toEqual({ kind: 'opening', index: 0 })

    useEditor.getState().moveOpening(0, { x: 19_000, y: 5000 }) // dragged into the room
    opening = useEditor.getState().doc!.openings[0]!
    expect(opening).toMatchObject({ x: 20_000, y: 5000 }) // snapped onto the right-hand wall
  })
})
