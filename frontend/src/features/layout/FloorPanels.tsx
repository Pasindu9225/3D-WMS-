import { Plus, Trash2 } from 'lucide-react'
import { Button } from '@/components/ui/button'
import { FormMessage } from '@/components/ui/input'
import { t, type MessageKey } from '@/lib/i18n'
import { useEditor } from './editorStore'

/** Shown while the planner clicks a new outline onto the grid. */
export function DrawingPanel() {
  const drawing = useEditor((s) => s.drawing)
  const drawError = useEditor((s) => s.drawError)
  const { finishDrawing, cancelDrawing } = useEditor.getState()
  if (!drawing) return null
  return (
    <section className="space-y-3 rounded-lg border border-primary/40 bg-primary/5 p-3">
      <h2 className="text-sm font-semibold">{t('layout.drawing.title')}</h2>
      <p className="text-sm text-muted-foreground">{t('layout.drawing.help')}</p>
      <p className="text-sm font-medium" aria-live="polite">{t('layout.drawing.count', { n: drawing.length })}</p>
      {drawError && <FormMessage tone="error">{t(`layout.drawing.${drawError}` as MessageKey)}</FormMessage>}
      <div className="flex gap-2">
        <Button size="sm" onClick={finishDrawing} disabled={drawing.length < 3}>{t('layout.drawing.finish')}</Button>
        <Button size="sm" variant="outline" onClick={cancelDrawing}>{t('layout.drawing.cancel')}</Button>
      </div>
    </section>
  )
}

/** Exact corner positions in metres: the precise way to copy a measured building. */
export function CornerTable() {
  // Select a stable reference: `?? []` inside the selector makes a new array per call and loops forever.
  const floor = useEditor((s) => s.doc?.floor) ?? []
  const { moveFloorPoint, insertCornerAfter, removeFloorPoint } = useEditor.getState()
  const set = (i: number, axis: 'x' | 'y', metres: string) => {
    const value = Number(metres)
    if (metres === '' || !Number.isFinite(value)) return
    const p = floor[i]!
    moveFloorPoint(i, { ...p, [axis]: Math.round(value * 1000) })
  }
  return (
    <section className="space-y-2">
      <h2 className="text-sm font-semibold">{t('layout.corners')}</h2>
      <p className="text-xs text-muted-foreground">{t('layout.corners.hint')}</p>
      <ol className="space-y-1.5">
        {floor.map((p, i) => (
          <li key={i} className="flex items-center gap-1.5">
            <span className="w-5 text-right text-xs tabular-nums text-muted-foreground">{i + 1}</span>
            <input aria-label={`X ${i + 1}`} type="number" step={0.1} min={0} value={p.x / 1000}
              onChange={(e) => set(i, 'x', e.target.value)}
              className="h-8 w-20 rounded-md border border-input bg-background px-2 text-sm tabular-nums" />
            <input aria-label={`Y ${i + 1}`} type="number" step={0.1} min={0} value={p.y / 1000}
              onChange={(e) => set(i, 'y', e.target.value)}
              className="h-8 w-20 rounded-md border border-input bg-background px-2 text-sm tabular-nums" />
            <Button variant="ghost" size="icon" className="size-8" aria-label={t('layout.corners.add', { n: i + 1 })}
              onClick={() => insertCornerAfter(i)}><Plus /></Button>
            <Button variant="ghost" size="icon" className="size-8" aria-label={t('layout.corners.remove', { n: i + 1 })}
              disabled={floor.length <= 3} onClick={() => removeFloorPoint(i)}><Trash2 /></Button>
          </li>
        ))}
      </ol>
    </section>
  )
}
