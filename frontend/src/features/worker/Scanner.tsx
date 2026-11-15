import { Camera, X } from 'lucide-react'
import { useEffect, useRef, useState } from 'react'
import { Button } from '@/components/ui/button'
import { t } from '@/lib/i18n'

/** The browser's built-in barcode reader (Chrome on Android); not yet in TypeScript's DOM types. */
interface Detector {
  detect(source: HTMLVideoElement): Promise<{ rawValue: string }[]>
}
type DetectorCtor = new (options: { formats: string[] }) => Detector
const FORMATS = ['ean_13', 'ean_8', 'upc_a', 'upc_e', 'code_128', 'code_39', 'qr_code']

/** A camera button next to a code field: opens the scanner and hands back what it read. */
export function ScanButton({ onCode, label }: { onCode: (code: string) => void; label: string }) {
  const [open, setOpen] = useState(false)
  return (
    <>
      <Button type="button" variant="outline" className="size-12 shrink-0" aria-label={label} onClick={() => setOpen(true)}>
        <Camera />
      </Button>
      {open && <Scanner onClose={() => setOpen(false)} onCode={(code) => {
        setOpen(false)
        onCode(code)
      }} />}
    </>
  )
}

/**
 * Full-screen camera scanner. Uses the BarcodeDetector built into the browser when there is one; otherwise loads
 * @zxing/browser on demand (its own file, so phones that do not need it never download it). Reads barcodes and the
 * QR codes on bin labels. Typing the code stays possible, for a broken label or a phone without a camera.
 */
export function Scanner({ onCode, onClose }: { onCode: (code: string) => void; onClose: () => void }) {
  const video = useRef<HTMLVideoElement>(null)
  const [problem, setProblem] = useState<'blocked' | 'nocamera' | null>(null)
  const done = useRef(onCode)
  useEffect(() => {
    done.current = onCode
  }, [onCode])

  useEffect(() => {
    let stream: MediaStream | undefined
    let stopped = false
    let timer: ReturnType<typeof setTimeout> | undefined
    let zxingStop: (() => void) | undefined
    const found = (code: string) => {
      if (stopped) return
      stopped = true
      navigator.vibrate?.(60)
      done.current(code.trim())
    }
    void (async () => {
      try {
        stream = await navigator.mediaDevices.getUserMedia({ video: { facingMode: 'environment' }, audio: false })
      } catch (e) {
        setProblem(e instanceof DOMException && e.name === 'NotAllowedError' ? 'blocked' : 'nocamera')
        return
      }
      const el = video.current
      if (!el || stopped) return
      el.srcObject = stream
      await el.play().catch(() => undefined)
      const Native = (window as unknown as { BarcodeDetector?: DetectorCtor }).BarcodeDetector
      if (Native) {
        const detector = new Native({ formats: FORMATS })
        const tick = async () => {
          if (stopped) return
          try {
            const hits = await detector.detect(el)
            if (hits[0]?.rawValue) return found(hits[0].rawValue)
          } catch {
            // a frame that could not be read; try the next one
          }
          timer = setTimeout(() => void tick(), 200)
        }
        void tick()
      } else {
        const { BrowserMultiFormatReader } = await import('@zxing/browser')
        if (stopped) return
        const controls = await new BrowserMultiFormatReader().decodeFromVideoElement(el, (result) => {
          if (result) found(result.getText())
        })
        zxingStop = () => controls.stop()
      }
    })()
    return () => {
      stopped = true
      clearTimeout(timer)
      zxingStop?.()
      stream?.getTracks().forEach((track) => track.stop())
    }
  }, [])

  return (
    <div role="dialog" aria-label={t('scan.title')} className="fixed inset-0 z-50 flex flex-col bg-black text-white">
      <div className="flex items-center justify-between p-3">
        <span className="font-medium">{t('scan.title')}</span>
        <Button variant="ghost" size="icon" className="text-white" aria-label={t('scan.close')} onClick={onClose}><X /></Button>
      </div>
      <div className="relative flex-1">
        <video ref={video} className="absolute inset-0 size-full object-cover" playsInline muted />
        <div className="pointer-events-none absolute inset-x-8 top-1/2 h-40 -translate-y-1/2 rounded-xl border-4 border-white/80" />
      </div>
      <p className="p-4 text-center text-sm">
        {problem === 'blocked' ? t('scan.blocked') : problem === 'nocamera' ? t('scan.noCamera') : t('scan.hint')}
      </p>
    </div>
  )
}
