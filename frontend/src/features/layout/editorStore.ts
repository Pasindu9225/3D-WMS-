import { create } from 'zustand'
import type { LayoutDocument, LayoutView, Opening, Point, Rack, RackType, Zone } from '@/features/warehouses/api'
import {
  bounds, floorPreset, insertOnEdge, nearestOnOutline, nextCode, pointInPolygon, rackRect, selfIntersects, snap,
  type FloorShape,
} from './geometry'
import { UNITS, type Unit } from './units'

const UNIT_KEY = 'wms.editor.unit'
/** Remembered per browser (a display preference only, so localStorage is fine and may be unavailable). */
function savedUnit(): Unit {
  try {
    const u = localStorage.getItem(UNIT_KEY)
    return u && u in UNITS ? (u as Unit) : 'm'
  } catch {
    return 'm'
  }
}

export type Selection =
  | { kind: 'opening'; index: number }
  | { kind: 'zone'; index: number }
  | { kind: 'rack'; index: number }
  | null

type Footprint = Pick<RackType, 'widthMm' | 'depthMm'>

interface EditorState {
  doc: LayoutDocument | null
  /** Revision the editor started from; sent with the save so a newer save by someone else is detected. */
  baseRevision: number
  dirty: boolean
  selection: Selection
  /** Corners placed so far in a drawing mode; null when not drawing. */
  drawing: Point[] | null
  /** What the outline being drawn becomes: the floor, or a new zone. */
  drawTarget: 'floor' | 'zone'
  drawError: 'CROSSING' | 'TOO_FEW' | 'OUTSIDE' | null
  /** Where the pointer is while drawing (snapped); gives the direction for a typed length. */
  cursor: Point | null
  unit: Unit
  setUnit: (unit: Unit) => void
  setCursor: (p: Point | null) => void
  /** Next corner at an exact distance from the last one, towards the pointer (direction snapped to 15°). */
  addDrawLength: (mm: number) => void
  /** Shape tool: a sized preset becomes the floor, or a new zone centred on the floor. */
  placeShape: (shape: FloorShape, widthMm: number, lengthMm: number, target: 'floor' | 'zone') => void
  load: (view: LayoutView) => void
  /** exact: typed position, kept to the mm instead of snapping to the 0.1 m grid. */
  moveFloorPoint: (index: number, p: Point, exact?: boolean) => void
  insertFloorPoint: (p: Point) => void
  removeFloorPoint: (index: number) => void
  insertCornerAfter: (index: number) => void
  applyShape: (shape: FloorShape) => void
  startDrawing: (target?: 'floor' | 'zone') => void
  addDrawPoint: (p: Point) => void
  finishDrawing: () => void
  cancelDrawing: () => void
  addOpening: (kind: Opening['kind']) => void
  moveOpening: (index: number, p: Point) => void
  updateOpening: (index: number, patch: Partial<Pick<Opening, 'label' | 'widthMm' | 'kind'>>) => void
  removeOpening: (index: number) => void
  updateZone: (index: number, patch: Partial<Pick<Zone, 'code' | 'name' | 'type'>>) => void
  moveZonePoint: (zone: number, point: number, p: Point) => void
  removeZone: (index: number) => void
  addRack: (type: RackType) => void
  moveRack: (index: number, p: Point) => void
  rotateRack: (index: number) => void
  repeatRack: (index: number, count: number, fp: Footprint) => void
  updateRack: (index: number, patch: Partial<Pick<Rack, 'code' | 'templateId'>>) => void
  removeRack: (index: number) => void
  removeSelected: () => void
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
  const addZone = (polygon: Point[]) =>
    change((d) => {
      const code = nextCode('Z', d.zones.map((z) => z.code))
      set({ selection: { kind: 'zone', index: d.zones.length } })
      return { ...d, zones: [...d.zones, { code, type: 'STORAGE', name: `Zone ${code.slice(1)}`, polygon }] }
    })
  /** The zone a rack centre falls in; racks keep their zone when dropped outside every zone (flagged red). */
  const zoneAt = (d: LayoutDocument, p: Point, fallback: string) =>
    d.zones.find((z) => pointInPolygon(p, z.polygon))?.code ?? fallback

  return {
    doc: null,
    baseRevision: 0,
    dirty: false,
    selection: null,
    drawing: null,
    drawTarget: 'floor',
    drawError: null,
    cursor: null,
    unit: savedUnit(),
    setUnit: (unit) => {
      try {
        localStorage.setItem(UNIT_KEY, unit)
      } catch {
        // private window: the choice lasts until reload
      }
      set({ unit })
    },
    setCursor: (cursor) => set({ cursor }),
    addDrawLength: (mm) => {
      const { drawing, cursor } = get()
      const last = drawing?.[drawing.length - 1]
      if (!drawing || !last || mm <= 0) return
      const raw = cursor ? Math.atan2(cursor.y - last.y, cursor.x - last.x) : 0
      const step = Math.PI / 12 // 15°: walls at 0/45/90° come out exact
      const angle = Math.round(raw / step) * step
      // Whole mm, not the 0.1 m grid: a typed 50 ft wall must be exactly 50 ft.
      const point = {
        x: Math.min(1_000_000, Math.max(0, Math.round(last.x + mm * Math.cos(angle)))),
        y: Math.min(1_000_000, Math.max(0, Math.round(last.y + mm * Math.sin(angle)))),
      }
      set({ drawing: [...drawing, point], drawError: null })
    },
    placeShape: (shape, widthMm, lengthMm, target) => {
      const doc = get().doc
      if (!doc) return
      const b = bounds(doc.floor)
      const origin = target === 'floor'
        ? { x: b.minX, y: b.minY }
        : { x: Math.max(0, Math.round((b.minX + b.maxX - widthMm) / 2)), y: Math.max(0, Math.round((b.minY + b.maxY - lengthMm) / 2)) }
      const polygon = floorPreset(shape, widthMm, lengthMm).map((p) => ({ x: p.x + origin.x, y: p.y + origin.y }))
      if (target === 'floor') setFloor(polygon)
      else addZone(polygon)
    },
    load: (view) =>
      set({ doc: view.layout, baseRevision: view.revision, dirty: false, selection: null, drawing: null, drawError: null }),
    moveFloorPoint: (index, p, exact) =>
      change((d) => ({
        ...d,
        floor: d.floor.map((pt, i) => (i !== index ? pt : exact ? { x: Math.round(p.x), y: Math.round(p.y) } : { x: snap(p.x), y: snap(p.y) })),
      })),
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
    startDrawing: (target = 'floor') => set({ drawing: [], drawTarget: target, drawError: null, selection: null }),
    addDrawPoint: (p) => {
      const drawing = get().drawing
      if (!drawing) return
      const point = { x: snap(p.x), y: snap(p.y) }
      const last = drawing[drawing.length - 1]
      if (last && last.x === point.x && last.y === point.y) return // double-click lands twice on the same spot
      set({ drawing: [...drawing, point], drawError: null })
    },
    finishDrawing: () => {
      const { drawing, drawTarget, doc } = get()
      if (!drawing || !doc) return
      if (drawing.length < 3) return set({ drawError: 'TOO_FEW' })
      if (selfIntersects(drawing)) return set({ drawError: 'CROSSING' })
      if (drawTarget === 'zone') {
        // Corners on the wall count as inside; the server's exact check runs on save.
        const onOrIn = (p: Point) => pointInPolygon(p, doc.floor) || nearestOnOutline(doc.floor, p).distance < 1
        if (!drawing.every(onOrIn)) return set({ drawError: 'OUTSIDE' })
        addZone(drawing)
      } else {
        setFloor(drawing)
      }
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
    updateZone: (index, patch) =>
      change((d) => {
        const old = d.zones[index]!.code
        const renamed = patch.code !== undefined && patch.code !== old
        return {
          ...d,
          zones: d.zones.map((z, i) => (i === index ? { ...z, ...patch } : z)),
          racks: renamed ? d.racks.map((r) => (r.zone === old ? { ...r, zone: patch.code! } : r)) : d.racks,
        }
      }),
    moveZonePoint: (zone, point, p) =>
      change((d) => ({
        ...d,
        zones: d.zones.map((z, i) => (i !== zone ? z : {
          ...z,
          polygon: z.polygon.map((pt, j) => (j === point ? { x: snap(p.x), y: snap(p.y) } : pt)),
        })),
      })),
    removeZone: (index) => {
      // Racks cannot exist outside a zone, so they go with it (the panel says how many first).
      change((d) => {
        const code = d.zones[index]!.code
        return { ...d, zones: d.zones.filter((_, i) => i !== index), racks: d.racks.filter((r) => r.zone !== code) }
      })
      set({ selection: null })
    },
    addRack: (type) =>
      change((d) => {
        const sel = get().selection
        const zone = sel?.kind === 'zone' ? d.zones[sel.index] : d.zones[0]
        if (!zone) return d
        const b = bounds(zone.polygon)
        const rack: Rack = {
          code: nextCode('R', d.racks.map((r) => r.code), 2),
          templateId: type.id,
          zone: zone.code,
          x: snap((b.minX + b.maxX) / 2),
          y: snap((b.minY + b.maxY) / 2),
          rotation: 0,
        }
        set({ selection: { kind: 'rack', index: d.racks.length } })
        return { ...d, racks: [...d.racks, rack] }
      }),
    moveRack: (index, p) =>
      change((d) => {
        const at = { x: snap(p.x), y: snap(p.y) }
        return {
          ...d,
          racks: d.racks.map((r, i) => (i === index ? { ...r, ...at, zone: zoneAt(d, at, r.zone) } : r)),
        }
      }),
    rotateRack: (index) =>
      change((d) => ({ ...d, racks: d.racks.map((r, i) => (i === index ? { ...r, rotation: (r.rotation + 90) % 360 } : r)) })),
    repeatRack: (index, count, fp) =>
      change((d) => {
        // Copies side by side along the rack's length (no gap): one action builds a whole row.
        const base = d.racks[index]!
        const rect = rackRect(base, fp.widthMm, fp.depthMm)
        const alongX = base.rotation === 0 || base.rotation === 180
        const used = d.racks.map((r) => r.code)
        const copies: Rack[] = []
        for (let n = 1; n <= count; n++) {
          const code = nextCode('R', used, 2)
          used.push(code)
          const at = alongX ? { x: base.x + n * rect.width, y: base.y } : { x: base.x, y: base.y + n * rect.height }
          if (at.x > 1_000_000 || at.y > 1_000_000) break
          copies.push({ ...base, ...at, code, zone: zoneAt(d, at, base.zone) })
        }
        return { ...d, racks: [...d.racks, ...copies] }
      }),
    updateRack: (index, patch) =>
      change((d) => ({ ...d, racks: d.racks.map((r, i) => (i === index ? { ...r, ...patch } : r)) })),
    removeRack: (index) => {
      change((d) => ({ ...d, racks: d.racks.filter((_, i) => i !== index) }))
      set({ selection: null })
    },
    removeSelected: () => {
      const sel = get().selection
      if (sel?.kind === 'rack') get().removeRack(sel.index)
      if (sel?.kind === 'opening') get().removeOpening(sel.index)
      // Zones are not removed by the Delete key: that would also remove their racks.
    },
    select: (selection) => set({ selection }),
  }
})
