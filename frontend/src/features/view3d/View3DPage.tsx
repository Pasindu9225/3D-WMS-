import { ArrowLeft, Map, RotateCcw, Scan, ZoomIn, ZoomOut } from 'lucide-react'
import { lazy, Suspense, useCallback, useEffect, useRef } from 'react'
import { Link, Navigate, useParams } from 'react-router'
import { Button } from '@/components/ui/button'
import { FormMessage } from '@/components/ui/input'
import { can, useMe } from '@/features/auth/session'
import { useWarehouse, useWarehouses } from '@/features/warehouses/api'
import { ApiError } from '@/lib/api'
import { errorMessage } from '@/lib/errors'
import { t } from '@/lib/i18n'
import { useScene } from './api'
import { shortcut, type CameraApi } from './camera'
import { floorBox } from './sceneLayout'

// three.js and React Three Fiber load only here, never in the main bundle (SPEC v0.3).
const SceneCanvas = lazy(() => import('./SceneCanvas').then((m) => ({ default: m.SceneCanvas })))

/** /app/3d: open the only published warehouse straight away, otherwise let the user pick one. */
export function View3DIndexPage() {
  const list = useWarehouses()
  const published = list.data?.items.filter((w) => w.layoutStatus === 'PUBLISHED') ?? []
  if (published.length === 1) return <Navigate to={`/app/warehouses/${published[0]!.id}/3d`} replace />
  return (
    <div className="grid gap-4">
      <h1 className="text-2xl font-semibold">{t('view3d.title')}</h1>
      {list.isError && <FormMessage tone="error">{errorMessage(list.error)}</FormMessage>}
      {list.data && published.length === 0 && <p className="text-muted-foreground">{t('view3d.nonePublished')}</p>}
      <ul className="grid gap-2 sm:grid-cols-2">
        {published.map((w) => (
          <li key={w.id}>
            <Link to={`/app/warehouses/${w.id}/3d`} className="block rounded-lg border bg-card p-4 hover:bg-secondary/60">
              <span className="font-mono font-medium">{w.code}</span> · {w.name}
            </Link>
          </li>
        ))}
      </ul>
    </div>
  )
}

export function View3DPage() {
  const { id = '' } = useParams()
  const me = useMe()
  const warehouse = useWarehouse(id)
  const scene = useScene(id)
  const notPublished = scene.error instanceof ApiError && scene.error.code === 'NOT_PUBLISHED'
  const camera = useRef<CameraApi | null>(null)
  const onCamera = useCallback((api: CameraApi) => { camera.current = api }, [])
  const floor = scene.data?.floor

  // Keyboard: + / - zoom, T top view, R or Home reset, arrows pan (Shift: faster). Not while typing.
  useEffect(() => {
    if (!floor) return
    const step = floorBox(floor).size * 0.05
    const onKey = (e: KeyboardEvent) => {
      if (e.target instanceof HTMLInputElement || e.target instanceof HTMLSelectElement || e.ctrlKey || e.metaKey || e.altKey) return
      const action = camera.current && shortcut(e.key, e.shiftKey, camera.current, step)
      if (action) {
        e.preventDefault() // arrows would scroll the page
        action()
      }
    }
    window.addEventListener('keydown', onKey)
    return () => window.removeEventListener('keydown', onKey)
  }, [floor])
  return (
    <div className="-m-4 flex h-[calc(100dvh-3.5rem)] flex-col sm:-m-6">
      <div className="flex flex-wrap items-center gap-2 border-b bg-card px-4 py-2">
        <Button asChild variant="ghost" size="icon" aria-label={t('layout.back')}>
          <Link to="/app/warehouses"><ArrowLeft /></Link>
        </Button>
        <div className="mr-auto min-w-0">
          <h1 className="truncate font-semibold">{warehouse.data ? `${warehouse.data.code} · ${warehouse.data.name}` : t('app.loading')}</h1>
          {scene.data && (
            <p className="text-xs text-muted-foreground">
              {t('view3d.summary', { racks: scene.data.racks.length, bins: scene.data.bins.ids.length })}
            </p>
          )}
        </div>
        {can(me.data, 'warehouses:manage') && (
          <Button asChild variant="outline" size="sm"><Link to={`/app/warehouses/${id}/layout`}><Map /> {t('view3d.editLayout')}</Link></Button>
        )}
      </div>
      <div className="relative min-h-0 flex-1">
        {notPublished && <p className="p-6 text-muted-foreground">{t('view3d.notPublished')}</p>}
        {scene.isError && !notPublished && <div className="p-6"><FormMessage tone="error">{errorMessage(scene.error)}</FormMessage></div>}
        {scene.data && (
          <Suspense fallback={<p className="p-6 text-sm text-muted-foreground">{t('view3d.loading3d')}</p>}>
            <SceneCanvas scene={scene.data} onCamera={onCamera} />
          </Suspense>
        )}
        {scene.data && (
          <div className="absolute right-3 bottom-3 flex flex-col items-end gap-2">
            <div className="flex gap-1 rounded-lg border bg-card/90 p-1 shadow-sm">
              <Button variant="ghost" size="icon" aria-label={t('view3d.zoomIn')} title={t('view3d.zoomIn')} onClick={() => camera.current?.zoom(1)}><ZoomIn /></Button>
              <Button variant="ghost" size="icon" aria-label={t('view3d.zoomOut')} title={t('view3d.zoomOut')} onClick={() => camera.current?.zoom(-1)}><ZoomOut /></Button>
              <Button variant="ghost" size="icon" aria-label={t('view3d.top')} title={t('view3d.top')} onClick={() => camera.current?.top()}><Scan /></Button>
              <Button variant="ghost" size="icon" aria-label={t('view3d.reset')} title={t('view3d.reset')} onClick={() => camera.current?.reset()}><RotateCcw /></Button>
            </div>
            <p className="hidden rounded bg-card/80 px-2 py-1 text-xs text-muted-foreground sm:block">{t('view3d.keys')}</p>
          </div>
        )}
      </div>
    </div>
  )
}
