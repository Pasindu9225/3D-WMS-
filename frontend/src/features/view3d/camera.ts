/** What the page can ask the 3D camera to do (buttons and keyboard shortcuts). */
export interface CameraApi {
  reset: () => void
  top: () => void
  /** in > 0 moves closer, < 0 moves away. */
  zoom: (direction: 1 | -1) => void
  /** Pan along the floor relative to the view: right (+x) and away from the viewer (+forward). */
  pan: (right: number, forward: number) => void
  /** Look at a point on the plan (metres), close enough to read a bin. */
  flyTo: (x: number, y: number, z: number) => void
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
