import { CameraControls } from '@react-three/drei'
import { Canvas, useFrame, useThree } from '@react-three/fiber'
import { useEffect, useLayoutEffect, useMemo, useRef } from 'react'
import * as THREE from 'three'
import { nearestOnOutline } from '@/features/layout/geometry'
import type { Scene } from './api'
import type { CameraApi } from './camera'
import { slowFromFrameTimes } from './perf'
import { ZONE_COLOR } from './colors'
import { buildInstances, floorBox, type BoxSpec } from './sceneLayout'

const M = 1 / 1000


/** Flat outline (x0, y0, x1, y1 …) as a three.js shape lying on the floor. */
function shapeOf(flat: number[]): THREE.Shape {
  const shape = new THREE.Shape()
  for (let i = 0; i < flat.length; i += 2) {
    if (i === 0) shape.moveTo(flat[i]! * M, flat[i + 1]! * M)
    else shape.lineTo(flat[i]! * M, flat[i + 1]! * M)
  }
  return shape
}

const unitBox = new THREE.BoxGeometry(1, 1, 1)

/**
 * Many boxes, one draw call: a single InstancedMesh whose instances are scaled unit cubes. colors (optional)
 * gives one colour per instance; otherwise the material colour is used.
 */
function Boxes({ specs, color, colors, onPick }: {
  specs: BoxSpec[]
  color: string
  colors?: THREE.Color[]
  /** Click on one instance (not a drag): its index. */
  onPick?: (index: number) => void
}) {
  const mesh = useRef<THREE.InstancedMesh>(null)
  useLayoutEffect(() => {
    const m = mesh.current
    if (!m) return
    const o = new THREE.Object3D()
    specs.forEach((s, i) => {
      o.position.set(s.x, s.y, s.z)
      o.rotation.set(0, s.rotY, 0)
      o.scale.set(s.w, s.h, s.d)
      o.updateMatrix()
      m.setMatrixAt(i, o.matrix)
      if (colors) m.setColorAt(i, colors[i]!)
    })
    m.instanceMatrix.needsUpdate = true
    if (m.instanceColor) m.instanceColor.needsUpdate = true
    m.computeBoundingSphere()
  }, [specs, colors])
  if (specs.length === 0) return null
  return (
    <instancedMesh ref={mesh} args={[unitBox, undefined, specs.length]} frustumCulled={false}
      onClick={onPick && ((e) => {
        if (e.delta > 4 || e.instanceId === undefined) return // a drag to turn the camera, not a pick
        e.stopPropagation()
        onPick(e.instanceId)
      })}>
      <meshStandardMaterial color={colors ? '#ffffff' : color} />
    </instancedMesh>
  )
}

/**
 * The 3D warehouse. Renders on demand only (no animation loop), no shadows, pixel ratio capped at 1.5, so it
 * stays light on office laptops (SPEC: performance rules for 3D). Posts, shelves and bins are one instanced
 * draw call each, whatever the number of racks.
 */
export function SceneCanvas({ scene, parts, colors, selected, onSelect, onCamera, onSlow }: {
  scene: Scene
  /** Boxes from buildInstances(scene), worked out by the page so search and fly-to use the same positions. */
  parts: ReturnType<typeof buildInstances>
  /** One CSS colour per bin (colour mode worked out by the page). */
  colors: string[]
  /** Index of the selected bin, or null. */
  selected: number | null
  onSelect: (index: number | null) => void
  onCamera?: (api: CameraApi) => void
  /** Called once if moving the camera stays under 20 fps (the page then offers Lite mode). */
  onSlow?: () => void
}) {
  const floor = useMemo(() => new THREE.ShapeGeometry(shapeOf(scene.floor)), [scene.floor])
  const zones = useMemo(() => scene.zones.map((z) => ({ ...z, geometry: new THREE.ShapeGeometry(shapeOf(z.polygon)) })),
    [scene.zones])
  const box = useMemo(() => floorBox(scene.floor), [scene.floor])
  const binColors = useMemo(() => {
    const cache = new Map<string, THREE.Color>() // a few distinct colours, shared by thousands of bins
    return colors.map((c) => {
      let color = cache.get(c)
      if (!color) cache.set(c, (color = new THREE.Color(c)))
      return color
    })
  }, [colors])
  const doors = useMemo<BoxSpec[]>(() => {
    const outline: { x: number; y: number }[] = []
    for (let i = 0; i < scene.floor.length; i += 2) outline.push({ x: scene.floor[i]!, y: scene.floor[i + 1]! })
    return scene.openings.map((o) => {
      const wall = nearestOnOutline(outline, o) // doors lie along their wall
      const tall = o.kind === 'DOCK' ? 3 : 2.1
      return { x: o.x * M, y: tall / 2, z: o.y * M, w: o.widthMm * M, h: tall, d: 0.15, rotY: (-wall.angle * Math.PI) / 180 }
    })
  }, [scene.openings, scene.floor])

  return (
    <Canvas frameloop="demand" dpr={[1, 1.5]} data-testid="scene-canvas"
      camera={{ position: [box.cx + box.size * 0.6, box.size * 0.7, box.cz + box.size * 0.9], fov: 45, near: 0.1, far: box.size * 10 }}
      gl={{ antialias: true, powerPreference: 'low-power' }}
      // Dev builds only: lets the browser checks read draw calls and frame times (renderer.info).
      onCreated={({ gl }) => { if (import.meta.env.DEV) (window as unknown as { __wmsRenderer?: THREE.WebGLRenderer }).__wmsRenderer = gl }}>
      <color attach="background" args={['#0f172a']} />
      <hemisphereLight args={['#ffffff', '#334155', 1.2]} />
      <directionalLight position={[box.cx + 30, 60, box.cz + 20]} intensity={1.4} />
      {/* Plan y runs down the screen; turning the floor +90° about x maps plan (x, y) to scene (x, 0, y). */}
      <group rotation={[Math.PI / 2, 0, 0]}>
        <mesh geometry={floor} onClick={(e) => e.delta <= 4 && onSelect(null)}>
          <meshStandardMaterial color="#cbd5e1" side={THREE.DoubleSide} />
        </mesh>
        {zones.map((z) => (
          <mesh key={z.code} geometry={z.geometry} position={[0, 0, -0.01]}>
            <meshStandardMaterial color={ZONE_COLOR[z.type]} transparent opacity={0.3} side={THREE.DoubleSide} />
          </mesh>
        ))}
      </group>
      <Boxes specs={parts.posts} color="#1e3a8a" />
      <Boxes specs={parts.shelves} color="#f97316" />
      <Boxes specs={parts.bins} color="#94a3b8" colors={binColors} onPick={onSelect} />
      {selected !== null && parts.bins[selected] && <Highlight spec={parts.bins[selected]!} />}
      <Boxes specs={doors} color="#facc15" />
      <Rig box={box} onCamera={onCamera} />
      {onSlow && <FpsWatch onSlow={onSlow} />}
    </Canvas>
  )
}

/** Camera with smooth moves; hands the page a small API for its buttons and keyboard shortcuts. */
function Rig({ box, onCamera }: { box: { cx: number; cz: number; size: number }; onCamera?: (api: CameraApi) => void }) {
  const controls = useRef<CameraControls>(null)
  // Start looking at the middle of the floor (the controls would otherwise aim at the plan origin).
  useEffect(() => {
    void controls.current?.setLookAt(box.cx + box.size * 0.6, box.size * 0.7, box.cz + box.size * 0.9, box.cx, 0, box.cz, false)
  }, [box])
  useEffect(() => {
    const c = controls.current
    if (!c || !onCamera) return
    const home = () => c.setLookAt(box.cx + box.size * 0.6, box.size * 0.7, box.cz + box.size * 0.9, box.cx, 0, box.cz, true)
    const pos = new THREE.Vector3()
    const target = new THREE.Vector3()
    onCamera({
      reset: () => void home(),
      // Straight down, nudged a hair south so "up" on screen stays plan-up (north).
      top: () => void c.setLookAt(box.cx, box.size * 1.25, box.cz + 0.001, box.cx, 0, box.cz, true),
      zoom: (direction) => void c.dolly(direction * Math.max(1, c.distance * 0.2), true),
      pan: (right, forward) => {
        c.getPosition(pos)
        c.getTarget(target)
        const ahead = new THREE.Vector3(target.x - pos.x, 0, target.z - pos.z)
        if (ahead.lengthSq() < 1e-6) ahead.set(0, 0, -1) // top view: "ahead" is plan-up
        ahead.normalize()
        const side = new THREE.Vector3(-ahead.z, 0, ahead.x)
        const dx = side.x * right + ahead.x * forward
        const dz = side.z * right + ahead.z * forward
        void c.setLookAt(pos.x + dx, pos.y, pos.z + dz, target.x + dx, target.y, target.z + dz, true)
      },
      flyTo: (at, from) => void c.setLookAt(from.x, from.y, from.z, at.x, at.y, at.z, true),
    })
  }, [box, onCamera])
  return (
    <CameraControls ref={controls} makeDefault maxPolarAngle={Math.PI / 2.05} minDistance={1.5} maxDistance={box.size * 4}
      smoothTime={0.25} />
  )
}

/** Bright frame around the selected bin, a little larger so it shows from every side. */
function Highlight({ spec }: { spec: BoxSpec }) {
  return (
    <group position={[spec.x, spec.y, spec.z]} rotation={[0, spec.rotY, 0]} scale={[spec.w + 0.08, spec.h + 0.08, spec.d + 0.08]}>
      <lineSegments>
        <edgesGeometry args={[unitBox]} />
        <lineBasicMaterial color="#facc15" />
      </lineSegments>
      <mesh geometry={unitBox}>
        <meshBasicMaterial color="#facc15" transparent opacity={0.35} depthWrite={false} />
      </mesh>
    </group>
  )
}

/** Watches frame times while the camera moves (controls active), see slowFromFrameTimes. */
function FpsWatch({ onSlow }: { onSlow: () => void }) {
  const controls = useThree((state) => state.controls) as unknown as CameraControls | null
  const deltas = useRef<number[]>([])
  const moving = useRef(false)
  const done = useRef(false)
  useEffect(() => {
    if (!controls) return
    const start = () => { moving.current = true }
    const stop = () => { moving.current = false }
    controls.addEventListener('controlstart', start)
    controls.addEventListener('transitionstart', start)
    controls.addEventListener('sleep', stop)
    return () => {
      controls.removeEventListener('controlstart', start)
      controls.removeEventListener('transitionstart', start)
      controls.removeEventListener('sleep', stop)
    }
  }, [controls])
  useFrame((_, delta) => {
    if (done.current || !moving.current) return
    deltas.current.push(Math.min(delta, 2))
    if (deltas.current.length > 300) deltas.current.shift()
    if (slowFromFrameTimes(deltas.current)) {
      done.current = true
      onSlow()
    }
  })
  return null
}
