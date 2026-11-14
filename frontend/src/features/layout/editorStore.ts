import { create } from 'zustand'
import type { LayoutDocument, LayoutView, Opening, Point } from '@/features/warehouses/api'
import { insertOnEdge, nearestOnOutline, snap } from './geometry'

export type Selection = { kind: 'opening'; index: number } | null

interface EditorState {
  doc: LayoutDocument | null
  /** Revision the editor started from; sent with the save so a newer save by someone else is detected. */
  baseRevision: number
  dirty: boolean
  selection: Selection
  load: (view: LayoutView) => void
  moveFloorPoint: (index: number, p: Point) => void
  insertFloorPoint: (p: Point) => void
  removeFloorPoint: (index: number) => void
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
  return {
    doc: null,
    baseRevision: 0,
    dirty: false,
    selection: null,
    load: (view) => set({ doc: view.layout, baseRevision: view.revision, dirty: false, selection: null }),
    moveFloorPoint: (index, p) =>
      change((d) => ({ ...d, floor: d.floor.map((pt, i) => (i === index ? { x: snap(p.x), y: snap(p.y) } : pt)) })),
    insertFloorPoint: (p) => change((d) => ({ ...d, floor: insertOnEdge(d.floor, p) })),
    removeFloorPoint: (index) =>
      change((d) => (d.floor.length <= 3 ? d : { ...d, floor: d.floor.filter((_, i) => i !== index) })),
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
