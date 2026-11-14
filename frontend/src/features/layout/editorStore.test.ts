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

describe('floor shapes in the editor', () => {
  beforeEach(() => useEditor.getState().load(view))

  it('applies an L shape at the same size and moves doors onto the new walls', () => {
    useEditor.getState().addOpening('DOOR') // on the top wall, 10 m from the left
    useEditor.getState().moveOpening(0, { x: 18_000, y: 0 }) // near the top-right corner, which the L removes
    useEditor.getState().applyShape('L')
    const doc = useEditor.getState().doc!
    expect(doc.floor).toHaveLength(6)
    expect(doc.floor).toContainEqual({ x: 12_000, y: 0 })
    expect(doc.openings[0]).toMatchObject({ x: 18_000, y: 5000 }) // slid onto the new inner wall, 5 m away
  })

  it('inserts a corner in the middle of an edge', () => {
    useEditor.getState().insertCornerAfter(0)
    expect(useEditor.getState().doc!.floor[1]).toEqual({ x: 10_000, y: 0 })
  })

  it('draws a new outline corner by corner and refuses a crossing one', () => {
    const s = useEditor.getState()
    s.startDrawing()
    for (const p of [{ x: 0, y: 0 }, { x: 30_040, y: 0 }, { x: 30_000, y: 15_000 }]) useEditor.getState().addDrawPoint(p)
    useEditor.getState().addDrawPoint({ x: 30_000, y: 15_000 }) // double-click: same point twice
    useEditor.getState().finishDrawing()
    expect(useEditor.getState().drawing).toBeNull()
    expect(useEditor.getState().doc!.floor).toEqual([{ x: 0, y: 0 }, { x: 30_000, y: 0 }, { x: 30_000, y: 15_000 }])

    useEditor.getState().startDrawing()
    for (const p of [{ x: 0, y: 0 }, { x: 10_000, y: 10_000 }, { x: 10_000, y: 0 }, { x: 0, y: 10_000 }]) {
      useEditor.getState().addDrawPoint(p)
    }
    useEditor.getState().finishDrawing()
    expect(useEditor.getState().drawError).toBe('CROSSING')
    expect(useEditor.getState().drawing).toHaveLength(4) // kept, so the planner can fix it
    useEditor.getState().cancelDrawing()
    expect(useEditor.getState().doc!.floor).toHaveLength(3) // the earlier outline is untouched
  })
})
