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

describe('zones and racks in the editor', () => {
  const type = { id: '01ARZ3NDEKTSV4RRFFQ69G5FAV', code: 'P1', name: 'Pallet', widthMm: 2700, depthMm: 1100,
    heightMm: 4000, levels: 4, binsPerLevel: 3, maxWeightPerLevelG: 1_000_000 }
  const drawZone = (pts: { x: number; y: number }[]) => {
    useEditor.getState().startDrawing('zone')
    for (const p of pts) useEditor.getState().addDrawPoint(p)
    useEditor.getState().finishDrawing()
  }
  beforeEach(() => useEditor.getState().load(view))

  it('draws a zone inside the floor and refuses one that leaves it', () => {
    drawZone([{ x: 0, y: 0 }, { x: 10_000, y: 0 }, { x: 10_000, y: 10_000 }, { x: 0, y: 10_000 }])
    expect(useEditor.getState().doc!.zones).toEqual([
      { code: 'Z1', type: 'STORAGE', name: 'Zone 1', polygon: [{ x: 0, y: 0 }, { x: 10_000, y: 0 }, { x: 10_000, y: 10_000 }, { x: 0, y: 10_000 }] },
    ])
    expect(useEditor.getState().selection).toEqual({ kind: 'zone', index: 0 })

    drawZone([{ x: 15_000, y: 0 }, { x: 25_000, y: 0 }, { x: 25_000, y: 5000 }])
    expect(useEditor.getState().drawError).toBe('OUTSIDE')
    expect(useEditor.getState().doc!.zones).toHaveLength(1)
  })

  it('places racks in the selected zone, moves them between zones and renames follow the zone code', () => {
    drawZone([{ x: 0, y: 0 }, { x: 10_000, y: 0 }, { x: 10_000, y: 10_000 }, { x: 0, y: 10_000 }])
    drawZone([{ x: 10_000, y: 0 }, { x: 20_000, y: 0 }, { x: 20_000, y: 10_000 }, { x: 10_000, y: 10_000 }])
    useEditor.getState().addRack(type) // zone Z2 is selected after drawing it
    let rack = useEditor.getState().doc!.racks[0]!
    expect(rack).toMatchObject({ code: 'R01', zone: 'Z2', x: 15_000, y: 5000, rotation: 0 })

    useEditor.getState().moveRack(0, { x: 4_049, y: 3_020 })
    rack = useEditor.getState().doc!.racks[0]!
    expect(rack).toMatchObject({ x: 4000, y: 3000, zone: 'Z1' })

    useEditor.getState().updateZone(0, { code: 'A' })
    expect(useEditor.getState().doc!.racks[0]!.zone).toBe('A')
  })

  it('builds a row by repeating a rack along its length, turned racks along y', () => {
    drawZone([{ x: 0, y: 0 }, { x: 20_000, y: 0 }, { x: 20_000, y: 10_000 }, { x: 0, y: 10_000 }])
    useEditor.getState().addRack(type)
    useEditor.getState().moveRack(0, { x: 2000, y: 2000 })
    useEditor.getState().repeatRack(0, 3, type)
    expect(useEditor.getState().doc!.racks.map((r) => [r.code, r.x, r.y])).toEqual([
      ['R01', 2000, 2000], ['R02', 4700, 2000], ['R03', 7400, 2000], ['R04', 10_100, 2000],
    ])
    useEditor.getState().rotateRack(3)
    useEditor.getState().repeatRack(3, 1, type)
    expect(useEditor.getState().doc!.racks[4]).toMatchObject({ code: 'R05', x: 10_100, y: 4700, rotation: 90 })
  })

  it('removing a zone removes its racks; Delete removes the selected rack', () => {
    drawZone([{ x: 0, y: 0 }, { x: 10_000, y: 0 }, { x: 10_000, y: 10_000 }, { x: 0, y: 10_000 }])
    useEditor.getState().addRack(type)
    useEditor.getState().addRack(type)
    useEditor.getState().removeSelected()
    expect(useEditor.getState().doc!.racks).toHaveLength(1)
    useEditor.getState().removeZone(0)
    expect(useEditor.getState().doc!).toMatchObject({ zones: [], racks: [] })
  })
})

describe('measurements', () => {
  beforeEach(() => useEditor.getState().load(view))

  it('types exact wall lengths towards the pointer, in 15° steps', () => {
    const s = useEditor.getState()
    s.startDrawing('floor')
    s.addDrawPoint({ x: 0, y: 0 })
    useEditor.getState().setCursor({ x: 50_000, y: 900 }) // almost straight right
    useEditor.getState().addDrawLength(121_920) // 400 ft
    useEditor.getState().setCursor({ x: 121_920, y: 90_000 }) // straight down
    useEditor.getState().addDrawLength(15_240) // 50 ft
    expect(useEditor.getState().drawing).toEqual([{ x: 0, y: 0 }, { x: 121_920, y: 0 }, { x: 121_920, y: 15_240 }])
  })

  it('places a sized shape as an area centred on the floor, exact to the mm', () => {
    useEditor.getState().placeShape('RECT', 15_240, 4_000, 'zone')
    const zone = useEditor.getState().doc!.zones[0]!
    expect(zone.polygon).toEqual([{ x: 2380, y: 3000 }, { x: 17_620, y: 3000 }, { x: 17_620, y: 7000 }, { x: 2380, y: 7000 }])
    useEditor.getState().placeShape('OVAL', 10_000, 10_000, 'floor')
    expect(useEditor.getState().doc!.floor).toHaveLength(32)
  })
})

describe('moving, resizing and copying areas', () => {
  const type = { id: '01ARZ3NDEKTSV4RRFFQ69G5FAV', code: 'P1', name: 'Pallet', widthMm: 2000, depthMm: 1000,
    heightMm: 4000, levels: 4, binsPerLevel: 3, maxWeightPerLevelG: 1_000_000 }
  beforeEach(() => {
    useEditor.getState().load(view) // floor 20 m x 10 m
    useEditor.getState().placeShape('RECT', 6000, 4000, 'zone') // x 7..13 m, y 3..7 m
    useEditor.getState().addRack(type) // centre 10 m, 5 m
  })

  it('moves an area with its racks and keeps it on the plan', () => {
    useEditor.getState().moveZone(0, -2000, 1000)
    const d = useEditor.getState().doc!
    expect(d.zones[0]!.polygon[0]).toEqual({ x: 5000, y: 4000 })
    expect(d.racks[0]).toMatchObject({ x: 8000, y: 6000 })
    useEditor.getState().moveZone(0, -50_000, 0) // past the left edge: stops at 0
    expect(useEditor.getState().doc!.zones[0]!.polygon[0]!.x).toBe(0)
  })

  it('resizes an area to a typed width and length from its top-left corner', () => {
    useEditor.getState().resizeZone(0, 15_240, 2000)
    expect(useEditor.getState().doc!.zones[0]!.polygon).toEqual([
      { x: 7000, y: 3000 }, { x: 22_240, y: 3000 }, { x: 22_240, y: 5000 }, { x: 7000, y: 5000 },
    ])
  })

  it('duplicates an area with its racks under new codes, and pastes copies 1 m apart', () => {
    useEditor.getState().duplicateZone(0)
    let d = useEditor.getState().doc!
    expect(d.zones[1]).toMatchObject({ code: 'Z2', name: 'Zone 1 copy' })
    expect(d.zones[1]!.polygon[0]).toEqual({ x: 13_000, y: 3000 }) // right beside it
    expect(d.racks[1]).toMatchObject({ code: 'R02', zone: 'Z2', x: 16_000 })

    useEditor.getState().select({ kind: 'rack', index: 0 })
    useEditor.getState().copySelection()
    useEditor.getState().paste()
    useEditor.getState().paste()
    d = useEditor.getState().doc!
    expect(d.racks.slice(2).map((r) => [r.code, r.x, r.y])).toEqual([['R03', 11_000, 6000], ['R04', 12_000, 7000]])
  })
})

describe('angled and resized racks in the editor', () => {
  const type = { id: '01ARZ3NDEKTSV4RRFFQ69G5FAV', code: 'P1', name: 'Pallet', widthMm: 2000, depthMm: 1000,
    heightMm: 4000, levels: 4, binsPerLevel: 3, maxWeightPerLevelG: 1_000_000 }
  beforeEach(() => {
    useEditor.getState().load(view)
    useEditor.getState().placeShape('RECT', 20_000, 10_000, 'zone')
    useEditor.getState().addRack(type) // centre 10 m, 5 m
  })

  it('turns by any step and keeps the angle in 0..359', () => {
    useEditor.getState().rotateRack(0, 15)
    useEditor.getState().rotateRack(0, -30)
    expect(useEditor.getState().doc!.racks[0]!.rotation).toBe(345)
  })

  it('builds a row along an angled rack, using its own width when resized', () => {
    useEditor.getState().updateRack(0, { rotation: 60, widthMm: 3000 })
    useEditor.getState().repeatRack(0, 1, type)
    expect(useEditor.getState().doc!.racks[1]).toMatchObject({ x: 11_500, y: 7598, rotation: 60, widthMm: 3000 })
  })
})

describe('building a whole block of racks', () => {
  const type = { id: '01ARZ3NDEKTSV4RRFFQ69G5FAV', code: 'P1', name: 'Pallet', widthMm: 2700, depthMm: 1100,
    heightMm: 6000, levels: 5, binsPerLevel: 10, maxWeightPerLevelG: 1_000_000 }

  it('makes 100 racks (5,000 bins) in one action: 10 along x 10 rows with a 3 m aisle', () => {
    useEditor.getState().load({ ...view, layout: { ...view.layout, floor: [{ x: 0, y: 0 }, { x: 60_000, y: 0 }, { x: 60_000, y: 50_000 }, { x: 0, y: 50_000 }] } })
    useEditor.getState().placeShape('RECT', 56_000, 46_000, 'zone')
    useEditor.getState().addRack(type)
    useEditor.getState().moveRack(0, { x: 5350, y: 4550 }, true)
    useEditor.getState().repeatRack(0, 9, type, 10, 3000)
    const racks = useEditor.getState().doc!.racks
    expect(racks).toHaveLength(100)
    expect(racks[99]).toMatchObject({ code: 'R100', x: 5350 + 9 * 2700, y: 4550 + 9 * 4100 })
    expect(new Set(racks.map((r) => r.code)).size).toBe(100)
  })
})
