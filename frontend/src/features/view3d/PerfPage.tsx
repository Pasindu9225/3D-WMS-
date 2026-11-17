import { lazy, Suspense, useMemo, useRef, useState } from 'react'
import { Button } from '@/components/ui/button'
import { t } from '@/lib/i18n'
import type { Scene } from './api'
import { binColors } from './colors'
import { buildInstances } from './sceneLayout'

const SceneCanvas = lazy(() => import('./SceneCanvas').then((m) => ({ default: m.SceneCanvas })))

/**
 * Fake warehouse for the v0.3 exit check: racks of 5 levels x 10 bins in rows with 3 m aisles, coloured by
 * status with a few blocked and pick-face bins. Nothing is sent to the server.
 */
export function fakeScene(bins: number): Scene {
  const perRack = 50
  const racks = Math.ceil(bins / perRack)
  const perRow = 20
  const rows = Math.ceil(racks / perRow)
  const width = perRow * 2700 + 4000
  const length = rows * 4100 + 4000
  const scene: Scene = {
    publishedAt: 'fake', floor: [0, 0, width, 0, width, length, 0, length], openings: [],
    zones: [{ code: 'Z1', type: 'STORAGE', name: 'Fake', polygon: [1000, 1000, width - 1000, 1000, width - 1000, length - 1000, 1000, length - 1000] }],
    racks: [], bins: { ids: [], codes: [], rack: [], level: [], pos: [] }, flags: '',
  }
  let flags = ''
  const fill: number[] = [] // about two in three places hold a pallet (v2.1 real look: pallets and cartons are drawn)
  for (let r = 0; r < racks; r++) {
    const code = `R${String(r + 1).padStart(3, '0')}`
    scene.racks.push({ code, zone: 'Z1', x: 2000 + (r % perRow) * 2700 + 1350, y: 2000 + Math.floor(r / perRow) * 4100 + 550,
      rotation: 0, widthMm: 2700, depthMm: 1100, heightMm: 6000, binsPerLevel: 10, levels: [0, 1200, 2400, 3600, 4800] })
    for (let i = 0; i < perRack && scene.bins.ids.length < bins; i++) {
      scene.bins.ids.push(`${r}-${i}`)
      scene.bins.codes.push(`Z1-${code}-0${Math.floor(i / 10) + 1}-${String((i % 10) + 1).padStart(2, '0')}`)
      scene.bins.rack.push(r)
      scene.bins.level.push(Math.floor(i / 10))
      scene.bins.pos.push(i % 10)
      flags += i % 37 === 0 ? '1' : i % 11 === 0 ? '2' : '0'
      fill.push(i % 3 === 2 ? 0 : 20 + ((i * 37) % 80))
    }
  }
  scene.flags = flags
  scene.fill = fill
  return scene
}

/** /app/3d-perf: spins the camera over N fake bins for 10 seconds and reports the frame rate. */
export function PerfPage() {
  const [count, setCount] = useState(10_000)
  const scene = useMemo(() => fakeScene(count), [count])
  const parts = useMemo(() => buildInstances(scene), [scene])
  const colors = useMemo(() => binColors(scene, 'real', scene.flags), [scene])
  const frames = useRef<number[]>([])
  const [running, setRunning] = useState(false)
  const [result, setResult] = useState<{ median: number; p10: number } | null>(null)
  const start = () => {
    frames.current = []
    setResult(null)
    setRunning(true)
    setTimeout(() => {
      setRunning(false)
      const fps = frames.current.slice(10).map((d) => 1 / d).sort((a, b) => a - b) // skip warm-up frames
      setResult(fps.length ? { median: Math.round(fps[Math.floor(fps.length / 2)]!), p10: Math.round(fps[Math.floor(fps.length * 0.1)]!) } : null)
    }, 10_000)
  }
  return (
    <div className="-m-4 flex h-[calc(100dvh-3.5rem)] flex-col sm:-m-6">
      <div className="flex flex-wrap items-center gap-3 border-b bg-card px-4 py-2 text-sm">
        <h1 className="mr-auto font-semibold">{t('view3d.perf.title')}</h1>
        {[1000, 5000, 10_000, 20_000].map((n) => (
          <Button key={n} size="sm" variant={n === count ? 'default' : 'outline'} disabled={running} onClick={() => setCount(n)}>
            {t('view3d.perf.bins', { n: n.toLocaleString('en') })}
          </Button>
        ))}
        <Button size="sm" onClick={start} disabled={running}>{running ? t('view3d.perf.running') : t('view3d.perf.start')}</Button>
        <output aria-live="polite" data-testid="perf-result">
          {result && t('view3d.perf.result', { median: result.median, p10: result.p10 })}
        </output>
      </div>
      <div className="min-h-0 flex-1">
        <Suspense fallback={<p className="p-6">{t('view3d.loading3d')}</p>}>
          <SceneCanvas scene={scene} parts={parts} colors={colors} selected={null} onSelect={() => undefined} ghosts={false}
            onFrame={running ? (d) => frames.current.push(d) : undefined} />
        </Suspense>
      </div>
    </div>
  )
}
