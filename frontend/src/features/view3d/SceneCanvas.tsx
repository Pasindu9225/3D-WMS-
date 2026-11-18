import { CameraControls } from '@react-three/drei'
import { DRONE_SPEED, type Keyframe } from './drone'
import { Canvas, useFrame, useThree } from '@react-three/fiber'
import { useEffect, useLayoutEffect, useMemo, useRef } from 'react'
import * as THREE from 'three'
import { defaultHeight, nearestOnOutline } from '@/features/layout/geometry'
import type { Scene } from './api'
import type { CameraApi } from './camera'
import { slowFromFrameTimes } from './perf'
import { ZONE_COLOR } from './colors'
import { buildInstances, centreOf, contentBox, edgeStripes, floorBox, wallParts, type BoxSpec } from './sceneLayout'
import { boxTexture, cartonTexture, hazardTexture, labelTexture, leafTexture, palletTexture, shutterTexture, tagTexture } from './textures'
import { routePoints, type PickRoute } from './route'

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
 * Many boxes, one draw call: a single InstancedMesh whose instances are scaled unit cubes. colors (optional) gives
 * one colour per instance (it tints the texture, if any); otherwise the material colour is used. pickOnly draws
 * nothing but still catches clicks; ghost draws faint see-through boxes.
 */
function Boxes({ specs, color, colors, onPick, map, ghost, pickOnly, opacity }: {
  specs: BoxSpec[]
  color: string
  colors?: THREE.Color[]
  /** Click on one instance (not a drag): its index. */
  onPick?: (index: number) => void
  map?: THREE.Texture | null
  ghost?: boolean
  pickOnly?: boolean
  /** See-through (walls): below 1, drawn transparent so what is behind stays visible. */
  opacity?: number
}) {
  const mesh = useRef<THREE.InstancedMesh>(null)
  useLayoutEffect(() => {
    const m = mesh.current
    if (!m) return
    const o = new THREE.Object3D()
    o.rotation.order = 'YXZ' // turn with the rack first, then tilt about its length (diagonal braces)
    specs.forEach((sp, i) => {
      o.position.set(sp.x, sp.y, sp.z)
      o.rotation.set(sp.tilt ?? 0, sp.rotY, 0)
      o.scale.set(sp.w, sp.h, sp.d)
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
      {pickOnly ? <meshBasicMaterial visible={false} />
        : ghost ? <meshBasicMaterial color={colors ? '#ffffff' : color} transparent opacity={0.28} depthWrite={false} />
          : <meshStandardMaterial color={colors ? '#ffffff' : color} map={map ?? null} roughness={0.85}
            transparent={opacity !== undefined} opacity={opacity ?? 1} depthWrite={opacity === undefined} />}
    </instancedMesh>
  )
}

/**
 * A name that keeps the same size on screen however far away it is (a sprite without size attenuation), drawn over
 * everything, so zone and door names stay readable on a big floor.
 */
function NameTag({ text, at }: { text: string; at: [number, number, number] }) {
  const tag = useMemo(() => tagTexture(text), [text])
  useEffect(() => () => tag?.texture.dispose(), [tag])
  if (!tag) return null
  const h = 0.035
  return (
    <sprite position={at} scale={[h * tag.aspect, h, 1]} renderOrder={10}>
      <spriteMaterial map={tag.texture} sizeAttenuation={false} depthTest={false} transparent />
    </sprite>
  )
}

/** A zone's code and name painted flat on the floor at its middle, sized to the zone. */
function ZoneLabel({ zone }: { zone: Scene['zones'][number] }) {
  const texture = useMemo(() => labelTexture(zone.code, zone.name), [zone.code, zone.name])
  useEffect(() => () => texture?.dispose(), [texture])
  const c = centreOf(zone.polygon)
  let minX = Infinity, maxX = -Infinity, minY = Infinity, maxY = -Infinity
  for (let i = 0; i < zone.polygon.length; i += 2) {
    minX = Math.min(minX, zone.polygon[i]!); maxX = Math.max(maxX, zone.polygon[i]!)
    minY = Math.min(minY, zone.polygon[i + 1]!); maxY = Math.max(maxY, zone.polygon[i + 1]!)
  }
  const size = Math.max(1.5, Math.min(8, Math.min(maxX - minX, maxY - minY) * M * 0.9)) // readable on narrow zones too
  if (!texture) return null
  return (
    <mesh position={[c.x * M, 0.02, c.y * M]} rotation={[-Math.PI / 2, 0, 0]}>
      <planeGeometry args={[size, size]} />
      <meshBasicMaterial map={texture} transparent depthWrite={false} />
    </mesh>
  )
}

/**
 * The 3D warehouse. Renders on demand only (no animation loop), no shadows, pixel ratio capped at 1.5, so it
 * stays light on office laptops (SPEC: performance rules for 3D). Posts, shelves and bins are one instanced
 * draw call each, whatever the number of racks.
 */
export function SceneCanvas({ scene, parts, colors, selected, onSelect, onCamera, onSlow, onFrame, route, drone, ghosts = true }: {
  scene: Scene
  /** A pick walk drawn on the floor (v1.2), if any. */
  /** The drone flight (v2.1) while it is on. */
  /** Show empty bins as faint coloured boxes (colour modes); off in the Real look. */
  ghosts?: boolean
  drone?: DroneProps | null
  route?: PickRoute | null
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
  /** Performance page only: keep the camera turning and report every frame time (seconds). */
  onFrame?: (delta: number) => void
}) {
  const floor = useMemo(() => new THREE.ShapeGeometry(shapeOf(scene.floor)), [scene.floor])
  const zones = useMemo(() => scene.zones.map((z) => ({ ...z, geometry: new THREE.ShapeGeometry(shapeOf(z.polygon)) })),
    [scene.zones])
  const box = useMemo(() => floorBox(scene.floor), [scene.floor])
  const start = useMemo(() => contentBox(scene), [scene])
  // Outer walls with real-size door and dock openings, when the server sends the wall height.
  const walls = useMemo(() => (scene.wallHeightMm ? wallParts(scene.floor, scene.openings, scene.wallHeightMm) : null),
    [scene.floor, scene.openings, scene.wallHeightMm])
  const binColors = useMemo(() => {
    const cache = new Map<string, THREE.Color>() // a few distinct colours, shared by thousands of bins
    return colors.map((c) => {
      let color = cache.get(c)
      if (!color) cache.set(c, (color = new THREE.Color(c)))
      return color
    })
  }, [colors])
  const cartonColors = useMemo(() => parts.cartons.map((c) => binColors[c.bin]!), [parts.cartons, binColors])
  const boxColors = useMemo(() => parts.boxes.map((c) => binColors[c.bin]!), [parts.boxes, binColors])
  const rackColors = useMemo(() => {
    const cache = new Map<string, THREE.Color>() // a few rack colours, shared by every part
    const of = (list: string[]) => list.map((c) => { let x = cache.get(c); if (!x) cache.set(c, (x = new THREE.Color(c))); return x })
    return { posts: of(parts.postColors), braces: of(parts.braceColors), beams: of(parts.beamColors), shelves: of(parts.shelfColors) }
  }, [parts])
  const emptyColors = useMemo(() => parts.empties.map((c) => binColors[c.bin]!), [parts.empties, binColors])
  const zoneLines = useMemo(() => {
    const specs: BoxSpec[] = []
    const cols: THREE.Color[] = []
    for (const z of scene.zones) {
      const c = new THREE.Color(ZONE_COLOR[z.type])
      for (const st of edgeStripes(z.polygon, 0.12)) { specs.push(st); cols.push(c) }
    }
    return { specs, cols }
  }, [scene.zones])
  const kerb = useMemo(() => edgeStripes(scene.floor, 0.2, 0.3), [scene.floor])
  const doors = useMemo<BoxSpec[]>(() => {
    const outline: { x: number; y: number }[] = []
    for (let i = 0; i < scene.floor.length; i += 2) outline.push({ x: scene.floor[i]!, y: scene.floor[i + 1]! })
    return scene.openings.map((o) => {
      const wall = nearestOnOutline(outline, o) // doors lie along their wall
      const tall = (o.heightMm ?? defaultHeight(o.kind)) / 1000
      return { x: o.x * M, y: tall / 2, z: o.y * M, w: o.widthMm * M, h: tall, d: 0.15, rotY: (-wall.angle * Math.PI) / 180 }
    })
  }, [scene.openings, scene.floor])

  return (
    <Canvas frameloop="demand" dpr={[1, 1.5]} data-testid="scene-canvas"
      camera={{ position: [box.cx + box.size * 0.6, box.size * 0.7, box.cz + box.size * 0.9], fov: 45, near: 0.3, far: box.size * 10 }}
      gl={{ antialias: true, powerPreference: 'low-power' }}
      // Dev builds only: lets the browser checks read draw calls and frame times (renderer.info).
      onCreated={({ gl }) => { if (import.meta.env.DEV) (window as unknown as { __wmsRenderer?: THREE.WebGLRenderer }).__wmsRenderer = gl }}>
      <color attach="background" args={['#0f172a']} />
      <hemisphereLight args={['#ffffff', '#334155', 1.2]} />
      <directionalLight position={[box.cx + 30, 60, box.cz + 20]} intensity={1.4} />
      {/* Plan y runs down the screen; turning the floor +90° about x maps plan (x, y) to scene (x, 0, y). */}
      <group rotation={[Math.PI / 2, 0, 0]}>
        <mesh geometry={floor} onClick={(e) => e.delta <= 4 && onSelect(null)}>
          <meshStandardMaterial color="#9ca3af" roughness={0.95} side={THREE.DoubleSide} />
        </mesh>
        {zones.map((z) => (
          <mesh key={z.code} geometry={z.geometry} position={[0, 0, -0.005]}>
            <meshStandardMaterial color={ZONE_COLOR[z.type]} transparent opacity={0.45} side={THREE.DoubleSide} depthWrite={false}
              polygonOffset polygonOffsetFactor={-1} polygonOffsetUnits={-4} />{/* drawn just above the floor: no stripes at low angles on big floors */}
          </mesh>
        ))}
      </group>
      <Boxes specs={zoneLines.specs} color="#ffffff" colors={zoneLines.cols} />
      {scene.zones.map((z) => <ZoneLabel key={z.code} zone={z} />)}
      {!walls && <Boxes specs={kerb} color="#475569" />}{/* walls mark the edge; a kerb would cross door openings */}
      <Boxes specs={parts.posts} color="#1d4ed8" colors={rackColors.posts} />
      <Boxes specs={parts.braces} color="#3b82f6" colors={rackColors.braces} />
      <Boxes specs={parts.beams} color="#f97316" colors={rackColors.beams} />
      <Boxes specs={parts.shelves} color="#94a3b8" colors={rackColors.shelves} />
      <Boxes specs={parts.floorMarks} color="#fde047" />
      <Boxes specs={parts.labels} color="#f8fafc" />
      <Boxes specs={parts.pallets} color="#b98a52" map={palletTexture()} />
      <Boxes specs={parts.cartons} color="#d8a86c" colors={cartonColors} map={cartonTexture()} />
      <Boxes specs={parts.boxes} color="#d8a86c" colors={boxColors} map={boxTexture()} />
      {ghosts && <Boxes specs={parts.empties} color="#cbd5e1" colors={emptyColors} ghost />}
      <Boxes specs={parts.bins} color="#ffffff" pickOnly onPick={onSelect} />
      {selected !== null && parts.bins[selected] && <Highlight spec={parts.bins[selected]!} />}
      {walls ? (
        <>
          <Boxes specs={walls.walls} color="#cbd5e1" opacity={0.32} />
          <Boxes specs={walls.doors} color="#1e3a8a" />
          <Boxes specs={walls.leaves} color="#3b82f6" map={leafTexture()} />
          <Boxes specs={walls.docks} color="#f59e0b" />
          <Boxes specs={walls.shutters} color="#cbd5e1" map={shutterTexture()} />
          <Boxes specs={walls.aprons} color="#ffffff" map={hazardTexture()} />
        </>
      ) : <Boxes specs={doors} color="#facc15" />}
      {scene.zones.map((z) => {
        const c = centreOf(z.polygon)
        return <NameTag key={'zt' + z.code} text={`${z.code} · ${z.name}`} at={[c.x * M, 1, c.y * M]} />
      })}
      {scene.openings.map((o, i) => (
        <NameTag key={'ot' + i} text={o.label} at={[o.x * M, ((o.heightMm ?? defaultHeight(o.kind)) + 600) * M, o.y * M]} />
      ))}
      {route && route.stops.length > 0 && <RouteLine route={route} />}
      <Rig box={box} start={start} onCamera={onCamera} />
      {drone && <Drone {...drone} />}
      {onSlow && <FpsWatch onSlow={onSlow} />}
      {onFrame && <Spin onFrame={onFrame} />}
    </Canvas>
  )
}

/** Camera with smooth moves; hands the page a small API for its buttons and keyboard shortcuts. */
function Rig({ box, start, onCamera }: {
  box: { cx: number; cz: number; size: number }
  /** Where the first view looks: the racks (contentBox), so a small block in a big building is not a speck. */
  start: { cx: number; cz: number; size: number }
  onCamera?: (api: CameraApi) => void
}) {
  const controls = useRef<CameraControls>(null)
  useEffect(() => {
    void controls.current?.setLookAt(start.cx + start.size * 0.6, start.size * 0.7, start.cz + start.size * 0.9, start.cx, 0, start.cz, false)
  }, [start])
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

export interface DroneProps {
  tour: Keyframe[]
  playing: boolean
  speed: number
  /** 0..1, reported a few times a second. */
  onProgress: (done: number) => void
  /** The flight ended, or someone dragged, scrolled or clicked in the view. */
  onStop: () => void
}

/**
 * Flies the camera along smooth curves through the tour (v2.1). It drives the orbit controls (which stay switched off
 * for the mouse), so stopping leaves the camera exactly where it is and normal moving carries on from there.
 */
function Drone({ tour, playing, speed, onProgress, onStop }: DroneProps) {
  const controls = useThree((state) => state.controls) as unknown as CameraControls | null
  const invalidate = useThree((state) => state.invalidate)
  const dom = useThree((state) => state.gl.domElement)
  const curves = useMemo(() => {
    const pos = new THREE.CatmullRomCurve3(tour.map((k) => new THREE.Vector3(k.pos.x, k.pos.y, k.pos.z)), false, 'centripetal')
    const look = new THREE.CatmullRomCurve3(tour.map((k) => new THREE.Vector3(k.look.x, k.look.y, k.look.z)), false, 'centripetal')
    return { pos, look, length: Math.max(1, pos.getLength()) }
  }, [tour])
  const done = useRef(0)
  const reported = useRef(0)
  const p = useMemo(() => new THREE.Vector3(), [])
  const l = useMemo(() => new THREE.Vector3(), [])
  useEffect(() => {
    if (!controls) return
    controls.enabled = false
    const stop = () => onStop()
    dom.addEventListener('pointerdown', stop)
    dom.addEventListener('wheel', stop, { passive: true })
    return () => {
      controls.enabled = true
      dom.removeEventListener('pointerdown', stop)
      dom.removeEventListener('wheel', stop)
    }
  }, [controls, dom, onStop])
  useEffect(() => {
    if (playing) invalidate()
  }, [playing, invalidate])
  useFrame((_, delta) => {
    if (!playing || !controls) return
    done.current = Math.min(1, done.current + (Math.min(delta, 0.1) * DRONE_SPEED * speed) / curves.length)
    curves.pos.getPointAt(done.current, p)
    curves.look.getPointAt(done.current, l)
    void controls.setLookAt(p.x, p.y, p.z, l.x, l.y, l.z, false)
    if (done.current >= 1) {
      onProgress(1)
      onStop()
      return
    }
    if (done.current - reported.current >= 0.01) {
      reported.current = done.current
      onProgress(done.current)
    }
    invalidate() // render on demand: ask for the next frame straight away
  })
  return null
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

/** Turns the camera slowly on every frame (continuous rendering) and reports frame times: a stress test. */
function Spin({ onFrame }: { onFrame: (delta: number) => void }) {
  const controls = useThree((state) => state.controls) as unknown as CameraControls | null
  const invalidate = useThree((state) => state.invalidate)
  useFrame((_, delta) => {
    onFrame(delta)
    void controls?.rotate(delta * 0.4, 0, false)
    invalidate() // render on demand: ask for the next frame straight away
  })
  return null
}

/** The pick walk as a bright line just above the floor, with a marker at every stop (no extra library). */
function RouteLine({ route }: { route: PickRoute }) {
  const line = useMemo(() => {
    const geometry = new THREE.BufferGeometry().setFromPoints(routePoints(route).map((p) => new THREE.Vector3(p.x * M, 0.06, p.y * M)))
    return new THREE.Line(geometry, new THREE.LineBasicMaterial({ color: '#22d3ee' }))
  }, [route])
  useEffect(() => () => {
    line.geometry.dispose()
    ;(line.material as THREE.Material).dispose()
  }, [line])
  return (
    <>
      <primitive object={line} />
      {route.stops.map((s) => (
        <mesh key={s.step} position={[s.x * M, 0.15, s.y * M]}>
          <cylinderGeometry args={[0.35, 0.35, 0.1, 20]} />
          <meshBasicMaterial color="#0e7490" />
        </mesh>
      ))}
    </>
  )
}
