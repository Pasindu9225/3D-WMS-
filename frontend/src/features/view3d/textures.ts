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

let box: THREE.CanvasTexture | null | undefined
/** One real carton: its edges and a strip of tape across the middle. */
export function boxTexture(): THREE.CanvasTexture | null {
  if (box !== undefined) return box
  box = draw(64, (g, s) => {
    g.fillStyle = '#ffffff'
    g.fillRect(0, 0, s, s)
    g.strokeStyle = 'rgba(70,45,20,0.6)'
    g.lineWidth = 3
    g.strokeRect(1.5, 1.5, s - 3, s - 3)
    g.fillStyle = 'rgba(120,80,40,0.35)'
    g.fillRect(s / 2 - 5, 0, 10, s)
  })
  return box
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

/**
 * A name tag that keeps the same size on screen at any distance: a soft dark pill with, on the left, either a
 * coloured badge holding a short code (zones: "A" in the zone's colour) or a small coloured marker (doors, docks).
 * Drawn at twice the size for sharp text. Returns the texture and its width/height ratio.
 */
export function tagTexture(text: string, accent: string, badge?: string): { texture: THREE.CanvasTexture; aspect: number } | null {
  if (typeof document === 'undefined') return null
  const S = 2 // drawn at 2× for crisp text
  const H = 30 * S
  const label = text.slice(0, 32)
  const canvas = document.createElement('canvas')
  const g = canvas.getContext('2d')
  if (!g) return null
  const font = `600 ${15 * S}px system-ui, -apple-system, "Segoe UI", sans-serif`
  g.font = font
  const textW = g.measureText(label).width
  const lead = badge ? 26 * S : 18 * S // room for the badge or marker
  const width = Math.ceil(6 * S + lead + textW + 12 * S)
  canvas.width = width
  canvas.height = H
  // pill
  g.fillStyle = 'rgba(15,23,42,0.72)'
  g.beginPath()
  g.roundRect(0, 0, width, H, H / 2)
  g.fill()
  g.strokeStyle = 'rgba(255,255,255,0.18)'
  g.lineWidth = S
  g.stroke()
  if (badge) {
    g.fillStyle = accent
    g.beginPath()
    g.roundRect(4 * S, 4 * S, 22 * S, H - 8 * S, 6 * S)
    g.fill()
    g.fillStyle = '#ffffff'
    g.font = `700 ${13 * S}px system-ui, -apple-system, "Segoe UI", sans-serif`
    g.textAlign = 'center'
    g.textBaseline = 'middle'
    g.fillText(badge.slice(0, 3), 15 * S, H / 2 + S)
  } else {
    g.fillStyle = accent
    g.beginPath()
    g.roundRect(9 * S, 8 * S, 6 * S, H - 16 * S, 3 * S) // a slim upright bar, like a door edge
    g.fill()
  }
  g.font = font
  g.fillStyle = '#f8fafc'
  g.textAlign = 'left'
  g.textBaseline = 'middle'
  g.fillText(label, 6 * S + lead, H / 2 + S)
  const texture = new THREE.CanvasTexture(canvas)
  texture.colorSpace = THREE.SRGBColorSpace
  texture.anisotropy = 2
  return { texture, aspect: width / H }
}

/**
 * Hides labels that would cover a more important one on screen. Each rect is the label's box in screen pixels;
 * items come in priority order (most important first). Returns which ones to show.
 */
export function declutter(rects: ({ x: number; y: number; w: number; h: number } | null)[], pad = 4): boolean[] {
  const shown: { x: number; y: number; w: number; h: number }[] = []
  return rects.map((r) => {
    if (!r) return false
    const hit = shown.some((s) => Math.abs(s.x - r.x) * 2 < s.w + r.w + pad && Math.abs(s.y - r.y) * 2 < s.h + r.h + pad)
    if (!hit) shown.push(r)
    return !hit
  })
}

let shutter: THREE.CanvasTexture | null | undefined
/** A roller shutter: horizontal slats, darker grooves between them. */
export function shutterTexture(): THREE.CanvasTexture | null {
  if (shutter !== undefined) return shutter
  shutter = draw(64, (g, s) => {
    g.fillStyle = '#ffffff'
    g.fillRect(0, 0, s, s)
    g.fillStyle = 'rgba(30,41,59,0.45)'
    for (let y = 0; y < s; y += 8) g.fillRect(0, y, s, 2)
  })
  if (shutter) {
    shutter.wrapS = shutter.wrapT = THREE.RepeatWrapping
    shutter.repeat.set(1, 6) // about 6 × 8 slats over a dock door
  }
  return shutter
}

let leaf: THREE.CanvasTexture | null | undefined
/** A door leaf: two raised panels and a handle. */
export function leafTexture(): THREE.CanvasTexture | null {
  if (leaf !== undefined) return leaf
  leaf = draw(64, (g, s) => {
    g.fillStyle = '#ffffff'
    g.fillRect(0, 0, s, s)
    g.strokeStyle = 'rgba(15,23,42,0.45)'
    g.lineWidth = 2
    g.strokeRect(10, 6, s - 20, s * 0.38)
    g.strokeRect(10, s * 0.52, s - 20, s * 0.4)
    g.fillStyle = 'rgba(15,23,42,0.8)'
    g.fillRect(s - 16, s * 0.46, 8, 3) // handle
  })
  return leaf
}

let hazard: THREE.CanvasTexture | null | undefined
/** Yellow and black diagonal stripes, the marking in front of a loading dock. */
export function hazardTexture(): THREE.CanvasTexture | null {
  if (hazard !== undefined) return hazard
  hazard = draw(64, (g, s) => {
    g.fillStyle = '#facc15'
    g.fillRect(0, 0, s, s)
    g.strokeStyle = '#111827'
    g.lineWidth = 9
    for (let x = -s; x < s * 2; x += 24) {
      g.beginPath()
      g.moveTo(x, s)
      g.lineTo(x + s, 0)
      g.stroke()
    }
  })
  if (hazard) {
    hazard.wrapS = hazard.wrapT = THREE.RepeatWrapping
    hazard.repeat.set(3, 3)
  }
  return hazard
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
