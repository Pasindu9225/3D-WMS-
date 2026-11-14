import { OrbitControls } from '@react-three/drei'
import { Canvas } from '@react-three/fiber'
import { useMemo } from 'react'
import * as THREE from 'three'
import type { ZoneType } from '@/features/warehouses/api'
import type { Scene } from './api'

/** Plan millimetres (x right, y down) to scene metres (x right, z towards the viewer, y up). */
const M = 1 / 1000

const ZONE_COLOR: Record<ZoneType, string> = {
  RECEIVING: '#3b82f6',
  STORAGE: '#64748b',
  PICKING: '#10b981',
  DISPATCH: '#f59e0b',
  QUARANTINE: '#ef4444',
  COLD: '#0ea5e9',
}

/** Flat outline (x0, y0, x1, y1 …) as a three.js shape lying on the floor. */
function shapeOf(flat: number[]): THREE.Shape {
  const shape = new THREE.Shape()
  for (let i = 0; i < flat.length; i += 2) {
    const x = flat[i]! * M
    const y = flat[i + 1]! * M
    if (i === 0) shape.moveTo(x, y)
    else shape.lineTo(x, y)
  }
  return shape
}

/**
 * The 3D warehouse. Renders on demand only (no animation loop), no shadows, pixel ratio capped at 1.5, so it
 * stays light on office laptops (SPEC: performance rules for 3D).
 */
export function SceneCanvas({ scene }: { scene: Scene }) {
  const floor = useMemo(() => new THREE.ShapeGeometry(shapeOf(scene.floor)), [scene.floor])
  const zones = useMemo(() => scene.zones.map((z) => ({ ...z, geometry: new THREE.ShapeGeometry(shapeOf(z.polygon)) })),
    [scene.zones])
  const box = useMemo(() => {
    let minX = Infinity, minY = Infinity, maxX = -Infinity, maxY = -Infinity
    for (let i = 0; i < scene.floor.length; i += 2) {
      minX = Math.min(minX, scene.floor[i]!); maxX = Math.max(maxX, scene.floor[i]!)
      minY = Math.min(minY, scene.floor[i + 1]!); maxY = Math.max(maxY, scene.floor[i + 1]!)
    }
    return { cx: ((minX + maxX) / 2) * M, cz: ((minY + maxY) / 2) * M, size: Math.max(maxX - minX, maxY - minY) * M }
  }, [scene.floor])

  return (
    <Canvas frameloop="demand" dpr={[1, 1.5]} camera={{ position: [box.cx + box.size * 0.6, box.size * 0.7, box.cz + box.size * 0.9], fov: 45, near: 0.1, far: box.size * 10 }}
      gl={{ antialias: true, powerPreference: 'low-power' }} data-testid="scene-canvas">
      <color attach="background" args={['#0f172a']} />
      <hemisphereLight args={['#ffffff', '#334155', 1.2]} />
      <directionalLight position={[box.cx + 30, 60, box.cz + 20]} intensity={1.4} />
      {/* Plan y runs down the screen; turning the floor +90° about x maps plan (x, y) to scene (x, 0, y). */}
      <group rotation={[Math.PI / 2, 0, 0]}>
        <mesh geometry={floor}>
          <meshStandardMaterial color="#cbd5e1" side={THREE.DoubleSide} />
        </mesh>
        {zones.map((z) => (
          <mesh key={z.code} geometry={z.geometry} position={[0, 0, -0.01]}>
            <meshStandardMaterial color={ZONE_COLOR[z.type]} transparent opacity={0.35} side={THREE.DoubleSide} />
          </mesh>
        ))}
      </group>
      <OrbitControls makeDefault target={[box.cx, 0, box.cz]} maxPolarAngle={Math.PI / 2.05} />
    </Canvas>
  )
}
