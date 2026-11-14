import { ArrowLeft, DoorOpen, Maximize, PenLine, Save, Shapes, Truck, Trash2 } from 'lucide-react'
import { lazy, Suspense, useEffect, useState } from 'react'
import { Link, useBlocker, useParams } from 'react-router'
import { Badge } from '@/components/ui/badge'
import { Button } from '@/components/ui/button'
import { DropdownMenu, DropdownMenuContent, DropdownMenuItem, DropdownMenuTrigger } from '@/components/ui/dropdown-menu'
import { Field, FormMessage, Input } from '@/components/ui/input'
import { can, useMe } from '@/features/auth/session'
import { useLayout, useSaveLayout, useWarehouse } from '@/features/warehouses/api'
import { ApiError, type Issue } from '@/lib/api'
import { errorMessage } from '@/lib/errors'
import { t, type MessageKey } from '@/lib/i18n'
import { useEditor } from './editorStore'
import { CornerTable, DrawingPanel } from './FloorPanels'
import { areaM2, type FloorShape } from './geometry'

// Konva only loads here, never in the main bundle.
const FloorCanvas = lazy(() => import('./FloorCanvas').then((m) => ({ default: m.FloorCanvas })))

export function LayoutEditorPage() {
  const { id = '' } = useParams()
  const me = useMe()
  const editable = can(me.data, 'warehouses:manage')
  const warehouse = useWarehouse(id)
  const layout = useLayout(id)
  const save = useSaveLayout(id)
  const doc = useEditor((s) => s.doc)
  const dirty = useEditor((s) => s.dirty)
  const baseRevision = useEditor((s) => s.baseRevision)
  const selection = useEditor((s) => s.selection)
  const drawing = useEditor((s) => s.drawing)
  const { load, addOpening, updateOpening, removeOpening, applyShape, startDrawing, finishDrawing, cancelDrawing } =
    useEditor.getState()
  const [fitSignal, setFitSignal] = useState(0)

  useEffect(() => {
    if (layout.data) load(layout.data)
  }, [layout.data, load])

  const reload = () => {
    save.reset() // the old error belongs to the version being replaced
    void layout.refetch()
  }

  // Leaving with unsaved changes: ask inside the app, and let the browser ask on tab close.
  const blocker = useBlocker(({ currentLocation, nextLocation }) => dirty && currentLocation.pathname !== nextLocation.pathname)
  useEffect(() => {
    const warn = (e: BeforeUnloadEvent) => {
      if (useEditor.getState().dirty) e.preventDefault()
    }
    window.addEventListener('beforeunload', warn)
    return () => window.removeEventListener('beforeunload', warn)
  }, [])

  // Keyboard while drawing an outline: Enter finishes, Esc cancels.
  useEffect(() => {
    if (!drawing) return
    const onKey = (e: KeyboardEvent) => {
      if (e.key === 'Enter') finishDrawing()
      if (e.key === 'Escape') cancelDrawing()
    }
    window.addEventListener('keydown', onKey)
    return () => window.removeEventListener('keydown', onKey)
  }, [drawing, finishDrawing, cancelDrawing])

  const onSave = () => {
    if (doc) save.mutate({ baseRevision, layout: doc }, { onSuccess: (view) => load(view) })
  }
  const errors: Issue[] = save.error instanceof ApiError ? save.error.issues : []
  const conflict = save.error instanceof ApiError && save.error.code === 'LAYOUT_CHANGED'
  const warnings = save.data?.warnings ?? layout.data?.warnings ?? []
  const opening = selection?.kind === 'opening' ? doc?.openings[selection.index] : undefined

  return (
    <div className="-m-4 flex h-[calc(100dvh-3.5rem)] flex-col sm:-m-6">
      <div className="flex flex-wrap items-center gap-2 border-b bg-card px-4 py-2">
        <Button asChild variant="ghost" size="icon" aria-label={t('layout.back')}>
          <Link to="/app/warehouses"><ArrowLeft /></Link>
        </Button>
        <div className="mr-auto min-w-0">
          <h1 className="truncate font-semibold">{warehouse.data ? `${warehouse.data.code} · ${warehouse.data.name}` : t('app.loading')}</h1>
          <p className="text-xs text-muted-foreground">
            {layout.data && t(`layout.status.${layout.data.status}`)}
            {baseRevision > 0 && ` · ${t('layout.revision', { n: baseRevision })}`}
            {doc && ` · ${t('layout.area', { m2: Math.round(areaM2(doc.floor)) })}`}
            {dirty && <span className="text-accent-foreground dark:text-accent"> · {t('layout.unsaved')}</span>}
          </p>
        </div>
        {editable && (
          <>
            <DropdownMenu>
              <DropdownMenuTrigger asChild>
                <Button variant="outline" size="sm" disabled={!doc || !!drawing}><Shapes /> {t('layout.shape')}</Button>
              </DropdownMenuTrigger>
              <DropdownMenuContent align="start">
                {(['RECT', 'L', 'U', 'T'] as FloorShape[]).map((shape) => (
                  <DropdownMenuItem key={shape} onSelect={() => applyShape(shape)}>{t(`layout.shape.${shape}` as MessageKey)}</DropdownMenuItem>
                ))}
              </DropdownMenuContent>
            </DropdownMenu>
            <Button variant={drawing ? 'default' : 'outline'} size="sm" onClick={() => (drawing ? cancelDrawing() : startDrawing())}
              disabled={!doc} aria-pressed={!!drawing}><PenLine /> {t('layout.draw')}</Button>
            <Button variant="outline" size="sm" onClick={() => addOpening('DOOR')} disabled={!doc}><DoorOpen /> {t('layout.addDoor')}</Button>
            <Button variant="outline" size="sm" onClick={() => addOpening('DOCK')} disabled={!doc}><Truck /> {t('layout.addDock')}</Button>
          </>
        )}
        <Button variant="outline" size="sm" onClick={() => setFitSignal((n) => n + 1)}><Maximize /> {t('layout.fit')}</Button>
        {editable && (
          <Button size="sm" onClick={onSave} disabled={!dirty || save.isPending}>
            <Save /> {save.isPending ? t('common.saving') : t('layout.save')}
          </Button>
        )}
      </div>

      <div className="flex min-h-0 flex-1 flex-col md:flex-row">
        <div className="min-h-[50vh] flex-1">
          <Suspense fallback={<p className="p-6 text-sm text-muted-foreground">{t('app.loading')}</p>}>
            <FloorCanvas templates={layout.data?.templates ?? {}} editable={editable} fitSignal={fitSignal} />
          </Suspense>
        </div>

        <aside className="w-full shrink-0 space-y-4 overflow-y-auto border-t bg-card p-4 md:w-80 md:border-t-0 md:border-l">
          {layout.isError && <FormMessage tone="error">{errorMessage(layout.error)}</FormMessage>}
          {conflict && (
            <FormMessage tone="error">
              {t('layout.conflict')}{' '}
              <button type="button" className="underline" onClick={reload}>{t('layout.reload')}</button>
            </FormMessage>
          )}
          {save.isError && !conflict && errors.length === 0 && <FormMessage tone="error">{errorMessage(save.error)}</FormMessage>}
          {save.isSuccess && !dirty && <FormMessage tone="success">{t('layout.saved')}</FormMessage>}

          {opening && selection?.kind === 'opening' && editable && (
            <section className="space-y-3 rounded-lg border p-3">
              <h2 className="text-sm font-semibold">{opening.kind === 'DOCK' ? t('layout.dock') : t('layout.door')}</h2>
              <Field label={t('layout.label')}>
                {(p) => <Input {...p} value={opening.label} maxLength={40}
                  onChange={(e) => updateOpening(selection.index, { label: e.target.value })} />}
              </Field>
              <Field label={t('layout.widthM')}>
                {(p) => <Input {...p} type="number" min={0.5} max={20} step={0.1} value={opening.widthMm / 1000}
                  onChange={(e) => updateOpening(selection.index, { widthMm: Math.round(Number(e.target.value) * 1000) })} />}
              </Field>
              <Button variant="outline" size="sm" className="text-destructive" onClick={() => removeOpening(selection.index)}>
                <Trash2 /> {t('layout.remove')}
              </Button>
            </section>
          )}

          <DrawingPanel />
          <IssueList title={t('layout.errors')} issues={errors} tone="bad" />
          <IssueList title={t('layout.warnings')} issues={warnings} tone="warn" />
          {editable && !drawing && !opening && <CornerTable />}
          {editable && <p className="text-xs leading-relaxed text-muted-foreground">{t('layout.help')}</p>}
        </aside>
      </div>

      {blocker.state === 'blocked' && (
        <div role="alertdialog" aria-modal="true" className="fixed inset-0 z-50 grid place-items-center bg-black/50 p-4">
          <div className="max-w-sm space-y-4 rounded-lg border bg-card p-6">
            <p>{t('layout.leaveConfirm')}</p>
            <div className="flex justify-end gap-2">
              <Button variant="outline" onClick={() => blocker.reset()}>{t('layout.stay')}</Button>
              <Button variant="destructive" onClick={() => blocker.proceed()}>{t('layout.leave')}</Button>
            </div>
          </div>
        </div>
      )}
    </div>
  )
}

function IssueList({ title, issues, tone }: { title: string; issues: Issue[]; tone: 'bad' | 'warn' }) {
  if (issues.length === 0) return null
  return (
    <section className="space-y-2">
      <h2 className="flex items-center gap-2 text-sm font-semibold">{title} <Badge tone={tone}>{issues.length}</Badge></h2>
      <ul className="space-y-1.5 text-sm">
        {issues.map((i, n) => (
          <li key={n} className="rounded-md bg-secondary/60 px-2 py-1.5">{i.message}</li>
        ))}
      </ul>
    </section>
  )
}
