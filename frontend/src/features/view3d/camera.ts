import type { BoxSpec } from './sceneLayout'

export interface Point3 {
  x: number
  y: number
  z: number
}

/** What the page can ask the 3D camera to do (buttons and keyboard shortcuts). */
export interface CameraApi {
  reset: () => void
  top: () => void
  /** in > 0 moves closer, < 0 moves away. */
  zoom: (direction: 1 | -1) => void
  /** Pan along the floor relative to the view: right (+x) and away from the viewer (+forward). */
  pan: (right: number, forward: number) => void
  /** Move the camera to `from` and look at `at` (scene metres), smoothly. */
  flyTo: (at: Point3, from: Point3) => void
}

/** Keyboard shortcut → camera action, or null. Kept pure so it can be tested without WebGL. */
export function shortcut(key: string, shift: boolean, api: CameraApi, step: number): (() => void) | null {
  const s = shift ? step * 3 : step
  switch (key) {
    case '+': case '=': return () => api.zoom(1)
    case '-': case '_': return () => api.zoom(-1)
    case 't': case 'T': return () => api.top()
    case 'r': case 'R': case 'Home': return () => api.reset()
    case 'ArrowLeft': return () => api.pan(-s, 0)
    case 'ArrowRight': return () => api.pan(s, 0)
    case 'ArrowUp': return () => api.pan(0, s)
    case 'ArrowDown': return () => api.pan(0, -s)
    default: return null
  }
}

/**
 * Where to stand to read a bin: in the aisle in front of it (along the rack's face normal, 1.8 m out),
 * a little to one side and above, so neighbouring rows never block the view.
 */
export function viewpoint(bin: BoxSpec): Point3 {
  const a = -bin.rotY // plan angle
  const normal = { x: -Math.sin(a), z: Math.cos(a) } // rack front, towards plan "down"
  const along = { x: Math.cos(a), z: Math.sin(a) }
  const out = bin.d / 2 + 1.8
  return { x: bin.x + normal.x * out + along.x * 0.8, y: bin.y + 0.6, z: bin.z + normal.z * out + along.z * 0.8 }
}
