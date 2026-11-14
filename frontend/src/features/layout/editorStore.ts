import { create } from 'zustand'
import type { LayoutDocument, LayoutView, Opening, Point } from '@/features/warehouses/api'
import { bounds, floorPreset, insertOnEdge, nearestOnOutline, selfIntersects, snap, type FloorShape } from './geometry'

export type Selection = { kind: 'opening'; index: number } | null

interface EditorState {
  doc: LayoutDocument | null
  /** Revision the editor started from; sent with the save so a newer save by someone else is detected. */
  baseRevision: number
  dirty: boolean
  selection: Selection
  /** Corners placed so far in "draw outline" mode; null when not drawing. */
  drawing: Point[] | null
  drawError: 'CROSSING' | 'TOO_FEW' | null
  load: (view: LayoutView) => void
  moveFloorPoint: (index: number, p: Point) => void
  insertFloorPoint: (p: Point) => void
  removeFloorPoint: (index: number) => void
  insertCornerAfter: (index: number) => void
  applyShape: (shape: FloorShape) => void
  startDrawing: () => void
  addDrawPoint: (p: Point) => void
  finishDrawing: () => void
  cancelDrawing: () => void
  addOpening: (kind: Opening['kind']) => void
  moveOpening: (index: number, p: Point) => void
  updateOpening: (index: number, patch: Partial<Pick<Opening, 'label' | 'widthMm' | 'kind'>>) => void
  removeOpening: (index: number) => void
  select: (selection: Selection) => void
}

/** Client-side copy of the layout being edited. Only this store changes it; the canvas just draws it. */
export const useEditor = create<EditorState>((set, get) => {
  const change = (fn: (doc: LayoutDocument) => LayoutDocument) => {
    const doc = get().doc
    if (doc) set({ doc: fn(doc), dirty: true })
  }
  /** New outline: doors and docks move to the nearest point of the new walls. */
  const setFloor = (floor: Point[]) =>
    change((d) => ({
      ...d,
      floor,
      openings: d.openings.map((o) => {
        const at = nearestOnOutline(floor, o)
        return { ...o, x: at.x, y: at.y }
      }),
    }))
  return {
    doc: null,
    baseRevision: 0,
    dirty: false,
    selection: null,
    drawing: null,
    drawError: null,
    load: (view) =>
      set({ doc: view.layout, baseRevision: view.revision, dirty: false, selection: null, drawing: null, drawError: null }),
    moveFloorPoint: (index, p) =>
      change((d) => ({ ...d, floor: d.floor.map((pt, i) => (i === index ? { x: snap(p.x), y: snap(p.y) } : pt)) })),
    insertFloorPoint: (p) => change((d) => ({ ...d, floor: insertOnEdge(d.floor, p) })),
    removeFloorPoint: (index) =>
      change((d) => (d.floor.length <= 3 ? d : { ...d, floor: d.floor.filter((_, i) => i !== index) })),
    insertCornerAfter: (index) =>
      change((d) => {
        const a = d.floor[index]!
        const b = d.floor[(index + 1) % d.floor.length]!
        const mid = { x: snap((a.x + b.x) / 2), y: snap((a.y + b.y) / 2) }
        return { ...d, floor: [...d.floor.slice(0, index + 1), mid, ...d.floor.slice(index + 1)] }
      }),
    applyShape: (shape) => {
      const doc = get().doc
      if (!doc) return
      const b = bounds(doc.floor) // same overall size as now, new outline
      setFloor(floorPreset(shape, b.maxX - b.minX, b.maxY - b.minY).map((p) => ({ x: p.x + b.minX, y: p.y + b.minY })))
    },
    startDrawing: () => set({ drawing: [], drawError: null, selection: null }),
    addDrawPoint: (p) => {
      const drawing = get().drawing
      if (!drawing) return
      const point = { x: snap(p.x), y: snap(p.y) }
      const last = drawing[drawing.length - 1]
      if (last && last.x === point.x && last.y === point.y) return // double-click lands twice on the same spot
      set({ drawing: [...drawing, point], drawError: null })
    },
    finishDrawing: () => {
      const drawing = get().drawing
      if (!drawing) return
      if (drawing.length < 3) return set({ drawError: 'TOO_FEW' })
      if (selfIntersects(drawing)) return set({ drawError: 'CROSSING' })
      setFloor(drawing)
      set({ drawing: null, drawError: null })
    },
    cancelDrawing: () => set({ drawing: null, drawError: null }),
    addOpening: (kind) =>
      change((d) => {
        const n = d.openings.filter((o) => o.kind === kind).length + 1
        const first = d.floor[0]!
        const second = d.floor[1]!
        const at = nearestOnOutline(d.floor, { x: (first.x + second.x) / 2, y: (first.y + second.y) / 2 })
        const opening: Opening = {
          kind,
          label: `${kind === 'DOCK' ? 'Dock' : 'Door'} ${n}`,
          x: at.x,
          y: at.y,
          widthMm: kind === 'DOCK' ? 3000 : 1200,
        }
        set({ selection: { kind: 'opening', index: d.openings.length } })
        return { ...d, openings: [...d.openings, opening] }
      }),
    moveOpening: (index, p) =>
      change((d) => {
        const at = nearestOnOutline(d.floor, p) // doors and docks always sit on the outer wall
        return { ...d, openings: d.openings.map((o, i) => (i === index ? { ...o, x: at.x, y: at.y } : o)) }
      }),
    updateOpening: (index, patch) =>
      change((d) => ({ ...d, openings: d.openings.map((o, i) => (i === index ? { ...o, ...patch } : o)) })),
    removeOpening: (index) => {
      change((d) => ({ ...d, openings: d.openings.filter((_, i) => i !== index) }))
      set({ selection: null })
    },
    select: (selection) => set({ selection }),
  }
})
