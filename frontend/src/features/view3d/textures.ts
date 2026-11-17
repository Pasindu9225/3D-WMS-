import * as THREE from 'three'

/**
 * Surface detail for the 3D warehouse, drawn in code on small canvases once (v2.1): nothing is downloaded, so the 3D
 * view works on a slow or metered connection. Textures are light (white-ish) so each instance's colour tints them.
 * Returns null where there is no canvas (unit tests).
 */
function draw(size: number, paint: (g: CanvasRenderingContext2D, s: number) => void): THREE.CanvasTexture | null {
  if (typeof document === 'undefined') return null
  const canvas = document.createElement('canvas')
  canvas.width = size
  canvas.height = size
  const g = canvas.getContext('2d')
  if (!g) return null
  paint(g, size)
  const texture = new THREE.CanvasTexture(canvas)
  texture.colorSpace = THREE.SRGBColorSpace
  texture.anisotropy = 2
  return texture
}

let carton: THREE.CanvasTexture | null | undefined
/** A stack of 2 × 2 cartons: seams between them and brown tape across each top. */
export function cartonTexture(): THREE.CanvasTexture | null {
  if (carton !== undefined) return carton
  carton = draw(128, (g, s) => {
    g.fillStyle = '#ffffff'
    g.fillRect(0, 0, s, s)
    g.strokeStyle = 'rgba(70,45,20,0.55)'
    g.lineWidth = 3
    g.strokeRect(1.5, 1.5, s - 3, s - 3)
    g.beginPath()
    g.moveTo(s / 2, 0); g.lineTo(s / 2, s)
    g.moveTo(0, s / 2); g.lineTo(s, s / 2)
    g.stroke()
    g.fillStyle = 'rgba(120,80,40,0.35)' // tape
    for (const x of [s / 4, (3 * s) / 4]) g.fillRect(x - 5, 0, 10, s)
  })
  return carton
}

let pallet: THREE.CanvasTexture | null | undefined
/** Wooden boards with gaps. */
export function palletTexture(): THREE.CanvasTexture | null {
  if (pallet !== undefined) return pallet
  pallet = draw(64, (g, s) => {
    g.fillStyle = '#ffffff'
    g.fillRect(0, 0, s, s)
    g.fillStyle = 'rgba(60,35,15,0.6)'
    for (let x = 0; x < s; x += s / 5) g.fillRect(x, 0, 3, s) // gaps between boards
    g.fillStyle = 'rgba(60,35,15,0.2)'
    for (let y = 4; y < s; y += 9) g.fillRect(0, y, s, 1) // grain
  })
  return pallet
}

/** A zone's code and name painted on the floor (white text on a clear background). */
export function labelTexture(title: string, subtitle: string): THREE.CanvasTexture | null {
  return draw(256, (g, s) => {
    g.clearRect(0, 0, s, s)
    g.fillStyle = 'rgba(255,255,255,0.92)'
    g.textAlign = 'center'
    g.textBaseline = 'middle'
    g.font = `bold ${Math.round(s * 0.3)}px system-ui, sans-serif`
    g.fillText(title.slice(0, 8), s / 2, s * 0.42)
    g.font = `${Math.round(s * 0.11)}px system-ui, sans-serif`
    g.fillText(subtitle.slice(0, 22), s / 2, s * 0.72)
  })
}
