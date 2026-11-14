/**
 * Frame-rate rule for suggesting Lite mode. deltas are frame times (seconds) recorded only while the camera
 * moves, so idle time never counts and very slow frames still do. Decide after 60 frames, or after 5 s of
 * movement with at least 10 frames (a 3 fps PC should not wait 20 s): a median under 20 fps is slow.
 */
export function slowFromFrameTimes(deltas: number[]): boolean {
  if (deltas.length < 10) return false
  const total = deltas.reduce((a, b) => a + b, 0)
  if (deltas.length < 60 && total < 5) return false
  const sorted = [...deltas].sort((a, b) => a - b)
  return 1 / sorted[Math.floor(sorted.length / 2)]! < 20
}
