<script setup>
import { computed, onBeforeUnmount, onMounted, ref, watch } from 'vue'

/* ----- Props from App.vue ----- */
const props = defineProps({
  clients:   { type: Array,  default: () => [] },
  sessions:  { type: Object, default: null },
  proxy:     { type: Object, default: () => ({}) },
  server:    { type: Object, default: () => ({}) },
})

function formatUptime(ms) {
  if (!ms || ms < 1000) return '—'
  const s = Math.floor(ms / 1000)
  const d = Math.floor(s / 86400), h = Math.floor((s % 86400) / 3600), m = Math.floor((s % 3600) / 60)
  if (d > 0) return `${d}d ${h}h`
  if (h > 0) return `${h}h ${m}m`
  return `${m}m`
}

/* ----- Derived: clientNodes ----- */
const REGIONS = ['Tokyo', 'Singapore', 'Seoul', 'Taipei', 'Hong Kong', 'Los Angeles', 'New York', 'London', 'Frankfurt', 'Sydney', 'Toronto', 'Amsterdam']
const FAKES = [
  'MacBook-Pro', 'iPhone-15', 'iPad-Air', 'ThinkPad-X1', 'Dell-XPS', 'Pixel-7',
  'Galaxy-S24', 'Surface-Pro', 'ZenBook-14', 'ROG-Zephyrus', 'Framework-16', 'Steam-Dock',
  'mini-PC-01', 'NAS-Storage', 'HomePod', 'Apple-TV', 'Fire-Stick', 'Raspberry-Pi',
]
function _randIp(kind) {
  if (kind === 'vpn') return `10.8.${Math.floor(Math.random()*255)}.${Math.floor(Math.random()*254)+1}`
  const a = [103, 121, 185, 202, 45, 99, 172, 8, 62, 118][Math.floor(Math.random()*10)]
  return `${a}.${Math.floor(Math.random()*255)}.${Math.floor(Math.random()*255)}.${Math.floor(Math.random()*255)}`
}
function _randRegion() { return REGIONS[Math.floor(Math.random()*REGIONS.length)] }

const MIN_NODES = 20
const ovScrolling = ref(true)
const ovTip = ref({ show: false, x: 0, y: 0, c: null })
function ovShowTip(e, c) {
  const r = e.currentTarget.getBoundingClientRect()
  ovTip.value = {
    show: true,
    x: r.right + 8,
    y: r.top,
    c,
  }
}
function ovHideTip() { ovTip.value.show = false }

// Reliable dark theme detection — check data-theme attribute first, then system preference
function _isDark() {
  if (typeof document === 'undefined') return false
  const attr = document.documentElement.getAttribute('data-theme')
  if (attr) return attr === 'dark'
  return window.matchMedia && window.matchMedia('(prefers-color-scheme: dark)').matches
}

// Border color that inverts with theme: dark→white-ish, light→black-ish
function _ovLine() {
  const dark = _isDark()
  return dark ? 'rgba(255,255,255,0.35)' : 'rgba(0,0,0,0.30)'
}
const clientNodes = computed(() => {
  const s = props.sessions
  // Build lookups: client name → session info
  const ikeMap = new Map()
  for (const x of (s?.ikev2 || [])) ikeMap.set(x.client, x)
  const vlMap = new Map()
  for (const x of (s?.vless || [])) vlMap.set(x.client, x)

  const real = props.clients.map((c, i) => {
    const ikeS = ikeMap.get(c.name)
    const vlS = vlMap.get(c.name)
    const via = ikeS ? 'ikev2' : vlS ? 'vless' : null
    const ses = via === 'ikev2' ? ikeS : vlS

    const clientIp = ikeS?.remoteHost || vlS?.source || ''
    const region = ikeS?.region || ''
    const vpnIp = ikeS?.vpnIp || ''

    return {
      id: c.uuid || `c${i}`,
      name: c.name || c.uuid?.slice(0, 8) || `client-${i+1}`,
      online: !!via && !!ses?.online,
      disabled: !c.enabled,
      via: via,
      uuid: c.uuid,
      ikeUser: c.ikeUser,
      clientIp, region, vpnIp,
      fake: false,
    }
  })

  // Pad to MIN_NODES with deterministic-but-realistic fakes
  const pad = Math.max(0, MIN_NODES - real.length)
  const fake = []
  for (let i = 0; i < pad; i++) {
    const seed = real.length + i
    const rnd = (n) => ((seed * 9301 + 49297) % n) / n  // LCG-ish, stable per seed
    const isIke = rnd(100) > 40
    const online = rnd(100) > 55
    const name = FAKES[Math.floor(rnd(FAKES.length) * FAKES.length) % FAKES.length] + '-' + String.fromCharCode(65 + Math.floor(rnd(26) * 26))
    fake.push({
      id: `fake-${seed}`,
      name,
      online,
      disabled: false,
      via: online ? (isIke ? 'ikev2' : 'vless') : null,
      uuid: '',
      ikeUser: `user${seed}`,
      clientIp: online ? _randIp('pub') : '',
      region: online ? _randRegion() : '',
      vpnIp: online ? _randIp('vpn') : '',
      fake: true,
    })
  }

  return [...real, ...fake]
})

function inputNodeStyle(idx, total) {
  return { top: `calc(${20 + (idx / Math.max(total, 1)) * 70}% - 12px)` }
}

/* ----- Pipeline Canvas ----- */
const pipelineCanvas = ref(null)
let _pipeRaf = null, _pipeCtx = null, _pipeW = 0, _pipeH = 0
const _pipeFlows = [], _pipeExits = [], _pipeImpacts = []
const _goldParts = [], _tealParts = []
let _goldPartsR = 0, _tealPartsR = 0
let _pipeAccum = 0
const _FLOW_HEADS_PER_BAND = 3

function _pipeZones(w, h) {
  const L = w * 0.28, C = w * 0.56
  const sphereCx = L + C / 2, sphereCy = h * 0.52
  const sphereR = Math.min(C * 0.42, h * 0.40)
  return { L, C, sphereCx, sphereCy, sphereR, outputX: w - 24 }
}

function _buildFlowPathFrom(x0, y0, Z) {
  // Converge into the single golden rim node on the LEFT (angle = π)
  const { sphereCx: cx, sphereCy: cy, sphereR: r } = Z
  const target = { x: cx - r, y: cy }
  // Quadratic bezier control: perpendicular (normal) offset + gentle time sway → flowing arc
  const mx = (x0 + target.x) / 2, my = (y0 + target.y) / 2
  const dx = target.x - x0, dy = target.y - y0
  const dl = Math.hypot(dx, dy) || 1
  const px = -dy / dl, py = dx / dl
  const bend = Math.sin(Date.now() * 0.0012 + x0 * 0.031 + y0 * 0.017) * dl * 0.16
  return { x0, y0, x1: target.x, y1: target.y, cx: mx + px * bend, cy: my + py * bend }
}

// Fallback path when DOM positions unavailable
function _buildFlowPath(agentIdx, totalAgents, Z) {
  const startY = 20 + (agentIdx / Math.max(totalAgents, 1)) * 70
  return _buildFlowPathFrom(16, (startY / 100) * _pipeH, Z)
}

function _syncFlowPathsFromDOM(Z) {
  // Build ONE flow per visible .ov-input entry (20+ items), online or not
  const stageEl = pipelineCanvas.value?.parentElement
  if (!stageEl) return
  const inputEls = stageEl.querySelectorAll('.ov-scroll-track .ov-input')
  const seenIds = new Set(); const uniq = []
  for (const el of inputEls) {
    const key = el.getAttribute('data-cid') || el.dataset.cid
    if (key && !seenIds.has(key)) { seenIds.add(key); uniq.push(el) }
    if (uniq.length >= clientNodes.value.length) break
  }
  if (!seenIds.size) {
    for (const el of inputEls) { uniq.push(el); if (uniq.length >= clientNodes.value.length) break }
  }

  const total = clientNodes.value.length
  // Grow/shrink _pipeFlows to match total entries
  while (_pipeFlows.length < total) {
    const c = clientNodes.value[_pipeFlows.length]
    _pipeFlows.push({
      agent: c,
      path: _buildFlowPath(_pipeFlows.length, total, Z),
      heads: [],
      speed: 0.004 + Math.random() * 0.004,
    })
  }
  _pipeFlows.length = total

  const stageR = stageEl.getBoundingClientRect()
  for (let i = 0; i < _pipeFlows.length; i++) {
    const candidates = [uniq[i], uniq[i + uniq.length]]
    let rect = null
    for (const el of candidates) {
      if (!el) continue
      const r = el.getBoundingClientRect()
      if (r.bottom >= stageR.top - 4 && r.top <= stageR.bottom + 4) { rect = r; break }
    }
    let yCenter, xRight
    if (rect) {
      yCenter = rect.top + rect.height / 2 - stageR.top
      xRight = rect.right - stageR.left
    } else {
      yCenter = (20 + (i / Math.max(total, 1)) * 70) / 100 * _pipeH
      xRight = 160
    }
    _pipeFlows[i].path = _buildFlowPathFrom(xRight, yCenter, Z)
  }
}

function _flowPos(path, s) {
  // Quadratic bezier: B(t) = (1-t)²P0 + 2(1-t)t·C + t²P1
  const t = Math.max(0, Math.min(1, s))
  const u = 1 - t
  if (path.cx === undefined) return { x: path.x0 + (path.x1 - path.x0) * t, y: path.y0 + (path.y1 - path.y0) * t }
  return {
    x: u * u * path.x0 + 2 * u * t * path.cx + t * t * path.x1,
    y: u * u * path.y0 + 2 * u * t * path.cy + t * t * path.y1,
  }
}

function tProgressAt(s, tailS, headS) {
  return Math.pow(Math.max(0, Math.min(1, (s - tailS) / (headS - tailS || 1))), 1.6)
}

function _initPipelineCanvas() {
  const canvas = pipelineCanvas.value
  if (!canvas) return
  _pipeCtx = canvas.getContext('2d')
  function resize() {
    const parent = canvas.parentElement, dpr = window.devicePixelRatio || 1
    _pipeW = parent.clientWidth; _pipeH = parent.clientHeight
    canvas.width = _pipeW * dpr; canvas.height = _pipeH * dpr
    canvas.style.width = _pipeW + 'px'; canvas.style.height = _pipeH + 'px'
    _pipeCtx.setTransform(dpr, 0, 0, dpr, 0, 0)
  }
  resize(); window.addEventListener('resize', resize); _pipeAnimate()
}

function _pipeAnimate() {
  const ctx = _pipeCtx
  if (!ctx) { _pipeRaf = requestAnimationFrame(_pipeAnimate); return }
  const Z = _pipeZones(_pipeW, _pipeH)
  const _cs = getComputedStyle(document.documentElement)
  const isDark = _isDark()
  const _cActiveIke = isDark ? '#2dd4bf' : '#0d9488'
  const _cActiveVle = isDark ? '#fbbf24' : '#d97706'
  const _cIdle = isDark ? 'rgba(251,191,36,0.26)' : 'rgba(217,119,6,0.22)'   // dim GOLD — left side stays uniformly gold

  // Keep flow paths synchronized with actual DOM item positions
  if (!_pipeFlows.length || !_pipeFlows[0]._syncTimer || (performance.now() - _pipeFlows[0]._syncTimer > 150)) {
    _syncFlowPathsFromDOM(Z)
    for (const f of _pipeFlows) f._syncTimer = performance.now()
  }

  for (const f of _pipeFlows) while (f.heads.length < _FLOW_HEADS_PER_BAND) f.heads.push({ s: Math.random() })

  // Fully clear the canvas each frame so particles leave no fading trail.
  // The canvas stays transparent and the stage's CSS background (var(--ov-stage-bg))
  // shows through, keeping the theme color correct in both dark and light modes.
  ctx.clearRect(0, 0, _pipeW, _pipeH)

  _drawSphere(ctx, Z, _pipeAccum, _cs)
  if (_pipeAccum > 0) _pipeAccum *= 0.96

  // ------------- Draw all beams — thin GOLD lines converging into the rim nodes -------------
  for (const f of _pipeFlows) {
    const online = f.agent.online
    const color = online ? _cActiveVle : _cIdle   // gold for live, faint for idle

    for (const h of f.heads) {
      h.s += f.speed
      if (h.s > 1.05) {
        h.s -= 1.05
        if (online) {
          const hit = _flowPos(f.path, 1.0)
          _pipeImpacts.push({ x: hit.x, y: hit.y, r: 2.0, maxR: 14, life: 1, color })
          _pipeAccum += 2
        }
      }
    }
    f.heads.sort((a, b) => a.s - b.s)

    // Render beam as FLUID PARTICLES — only the moving head dots; fade-fill leaves the trail
    for (const h of f.heads) {
      const pos = _flowPos(f.path, Math.min(h.s, 1))
      const a = online ? 0.95 : 0.30
      const rad = online ? 1.7 : 1.0
      ctx.fillStyle = color
      ctx.globalAlpha = a
      ctx.beginPath(); ctx.arc(pos.x, pos.y, rad, 0, Math.PI * 2); ctx.fill()
      if (online) {
        ctx.fillStyle = '#ffffff'
        ctx.globalAlpha = 0.55
        ctx.beginPath(); ctx.arc(pos.x, pos.y, 0.6, 0, Math.PI * 2); ctx.fill()
      }
      ctx.globalAlpha = 1
    }
  }

  // ------------- Impact flashes at sphere rim -------------
  for (let i = _pipeImpacts.length - 1; i >= 0; i--) {
    const im = _pipeImpacts[i]
    im.life -= 0.035
    im.r = im.maxR * (1 - im.life * im.life) + 2
    if (im.life <= 0) { _pipeImpacts.splice(i, 1); continue }
    // Outer fading glow
    ctx.fillStyle = im.color
    ctx.globalAlpha = im.life * 0.28
    ctx.beginPath(); ctx.arc(im.x, im.y, im.r, 0, Math.PI * 2); ctx.fill()
    // Bright core dot
    ctx.globalAlpha = im.life * 0.9
    ctx.beginPath(); ctx.arc(im.x, im.y, 1.8, 0, Math.PI * 2); ctx.fill()
  }
  ctx.globalAlpha = 1

  // ------------- Hub burst: golden fan + glow nodes + teal fan (drawn ON TOP) -------------
  _drawHubBurst(ctx, Z, _cs)

  // Exit beams — tiny bright lines shooting RIGHT from the sphere
  if (Math.random() < 0.30) {
    _pipeExits.push({
      x: Z.sphereCx + Z.sphereR * 1.02,
      y: Z.sphereCy + (Math.random() - 0.5) * Z.sphereR * 0.6,
      vx: 3.5 + Math.random() * 2.5, vy: (Math.random() - 0.5) * 1.0,
      s: 0, maxS: 90 + Math.floor(Math.random() * 40)
    })
  }
  // Exit beams — teal streaks (reference: right side is the cool/teal fan)
  const exitHex = isDark ? '#2dd4bf' : '#0d9488'
  ctx.globalAlpha = 1
  for (let i = _pipeExits.length - 1; i >= 0; i--) {
    const b = _pipeExits[i]
    ctx.strokeStyle = exitHex
    ctx.lineWidth = 1.4
    ctx.beginPath()
    ctx.moveTo(b.x, b.y)
    const tx = b.x + b.vx * 10, ty = b.y + b.vy * 10
    ctx.lineTo(tx, ty)
    ctx.globalAlpha = Math.max(0.25, 1 - b.s / b.maxS)
    ctx.stroke()
    b.x += b.vx; b.y += b.vy; b.s++
    if (b.s >= b.maxS || b.x > _pipeW + 10) { _pipeExits.splice(i, 1); continue }
  }
  ctx.globalAlpha = 1

  _pipeRaf = requestAnimationFrame(_pipeAnimate)
}

function _drawSphere(ctx, Z, accum, cs) {
  const { sphereCx: cx, sphereCy: cy, sphereR: r } = Z
  const glowA = Math.max(0.15, Math.min(1, accum / 50))

  // Reference palette: deep teal orbits + warm gold accents on a near-black sky
  const isDark = _isDark()
  const hexToRgba = (hex, a) => {
    const h = hex.replace('#','')
    const rr = parseInt(h.substring(0,2),16), gg = parseInt(h.substring(2,4),16), bb = parseInt(h.substring(4,6),16)
    return `rgba(${rr},${gg},${bb},${a})`
  }

  // Theme colors
  const TEAL  = isDark ? '#2dd4bf' : '#0d9488'   // orbit rings / ticks / nodes
  const CYAN  = isDark ? '#67e8f9' : '#0891b2'   // flow highlights / bright nodes
  const GOLD  = isDark ? '#fbbf24' : '#d97706'   // core ring + left-side golden entry beams
  const BLACK = '#0a0a0a'
  const WHITE = isDark ? '#ffffff' : '#1c1c1c'

  const now = Date.now()

  // ---------- Crisp concentric base rings (flat strokes only — no glow, no gradients) ----------
  for (const [rad, lw, al] of [
    [1.00, 1.5, 0.78],              // outer rim
    [0.86, 0.7, 0.32],
    [0.70, 0.7, 0.22],
    [0.52, 0.6, 0.14],
  ]) {
    ctx.strokeStyle = hexToRgba(TEAL, al)
    ctx.lineWidth = lw
    ctx.beginPath(); ctx.arc(cx, cy, r * rad, 0, Math.PI * 2); ctx.stroke()
  }

  // ---------- Self-rotating dashed tracks (dashes flow along the rim, alternating direction) ----------
  for (const [rad, lw, al, dash, dir] of [
    [0.93, 1.0, 0.50, [16, 7], 1],
    [0.79, 0.8, 0.38, [3, 12], -1],
    [0.60, 0.7, 0.30, [10, 6], 1],
  ]) {
    ctx.strokeStyle = hexToRgba(TEAL, al)
    ctx.lineWidth = lw
    ctx.setLineDash(dash)
    ctx.lineDashOffset = -dir * now * 0.0011 * r
    ctx.beginPath(); ctx.arc(cx, cy, r * rad, 0, Math.PI * 2); ctx.stroke()
  }
  ctx.setLineDash([])

  // ---------- Rotating dial band: 72 ticks, long/short alternating, cyan major marks ----------
  const TICKS = 72
  const dialSpin = now * 0.00024
  for (let i = 0; i < TICKS; i++) {
    const a = (i / TICKS) * Math.PI * 2 + dialSpin
    const major = i % 6 === 0
    const long = i % 2 === 0
    const r0 = r * (major ? 0.995 : 1.000)
    const r1 = r * (major ? 1.10 : long ? 1.06 : 1.035)
    ctx.strokeStyle = hexToRgba(major ? CYAN : TEAL, major ? 0.55 : 0.18)
    ctx.lineWidth = major ? 1.0 : 0.55
    ctx.beginPath()
    ctx.moveTo(cx + Math.cos(a) * r0, cy + Math.sin(a) * r0)
    ctx.lineTo(cx + Math.cos(a) * r1, cy + Math.sin(a) * r1)
    ctx.stroke()
  }

  // ---------- Two rotating arc blades (left = receiving rim, right = output rim) ----------
  for (const [baseA, dir] of [[Math.PI, 1], [0, -1]]) {
    const head = baseA + dir * (now * 0.0007)
    // crisp stepped blade: strong leading arc → thinner trailing arcs
    for (const [f0, f1, lw, al] of [[0, 0.14, 2.6, 0.85], [0.14, 0.33, 1.6, 0.50], [0.33, 0.52, 1.0, 0.26]]) {
      const a0 = head - dir * f0, a1 = head - dir * f1
      const lo = Math.min(a0, a1), hi = Math.max(a0, a1)
      ctx.strokeStyle = hexToRgba(f0 === 0 ? CYAN : TEAL, al)
      ctx.lineWidth = lw
      ctx.beginPath(); ctx.arc(cx, cy, r * 1.00, lo, hi, false); ctx.stroke()
    }
    // leading-edge notch: short radial bar crossing the rim
    const nx = Math.cos(head), ny = Math.sin(head)
    ctx.strokeStyle = hexToRgba(CYAN, 0.9)
    ctx.lineWidth = 1.4
    ctx.beginPath()
    ctx.moveTo(cx + nx * r * 0.965, cy + ny * r * 0.965)
    ctx.lineTo(cx + nx * r * 1.055, cy + ny * r * 1.055)
    ctx.stroke()
  }

  // ---------- Radial relay-node labels outside the rim (perpendicular to the ring) ----------
  const NAMES = [
    'Tokyo', 'Seoul', 'Busan', 'Osaka', 'Taipei', 'Manila', 'Jakarta', 'Bangkok',
    'Hanoi', 'Dhaka', 'Mumbai', 'Delhi', 'Karachi', 'Colombo', 'Sydney', 'Perth',
    'Auckland', 'Dubai', 'Doha', 'Riyadh', 'Istanbul', 'Moscow', 'Paris', 'Milan',
    'Madrid', 'Oslo', 'Prague', 'Vienna', 'Warsaw', 'Cairo', 'Lagos', 'Accra',
    'Dakar', 'Lima', 'Bogota', 'Miami',
  ]
  const LABEL_COLORS = isDark
    ? ['#8aa4c8', '#a97fbf', '#6bbb95', '#e39aae', '#7cc2d6']
    : ['#31537c', '#5b2f70', '#1f6b4c', '#a13f5e', '#1e6b80']
  const LABEL_N = 36
  const labelPx = Math.min(13, Math.max(9, Math.round(r * 0.028)))
  const charStep = labelPx * 1.18                       // stacking pitch along the radius
  const labelSpin = now * 0.00008                       // slow drift — slower than the dial
  const gap = r * 0.055                                 // clear air between ring and letters
  ctx.font = `600 ${labelPx}px ui-monospace, SFMono-Regular, Menlo, monospace`
  ctx.textBaseline = 'middle'
  ctx.textAlign = 'center'
  for (let i = 0; i < LABEL_N; i++) {
    const a = (i / LABEL_N) * Math.PI * 2 + labelSpin   // evenly spaced, rides the ring slowly
    const grpCol = LABEL_COLORS[i % 5]
    const name = NAMES[i % NAMES.length]

    // connector: rim dot + thin radial line, weight matched to the ring's tick marks
    ctx.fillStyle = hexToRgba(grpCol, 0.9)
    ctx.beginPath()
    ctx.arc(cx + Math.cos(a) * r, cy + Math.sin(a) * r, 1.6, 0, Math.PI * 2)
    ctx.fill()
    ctx.strokeStyle = hexToRgba(grpCol, 0.30)
    ctx.lineWidth = 0.8
    ctx.beginPath()
    ctx.moveTo(cx + Math.cos(a) * r, cy + Math.sin(a) * r)
    ctx.lineTo(cx + Math.cos(a) * (r + gap), cy + Math.sin(a) * (r + gap))
    ctx.stroke()

    // letters stacked one by one along the radial line — perpendicular to the ring,
    // each glyph upright so it reads like the ring's vertical ticks
    for (let k = 0; k < name.length; k++) {
      const rad = r + gap + k * charStep
      ctx.fillStyle = hexToRgba(grpCol, 0.78)
      ctx.fillText(name[k], cx + Math.cos(a) * rad, cy + Math.sin(a) * rad)
    }
  }

  // ---------- Core: LARGE pure black disk + GOLD ring (reference: dominant black hole) ----------
  ctx.fillStyle = BLACK
  ctx.beginPath(); ctx.arc(cx, cy, r * 0.40, 0, Math.PI * 2); ctx.fill()
  // Teal ring around black core — base circle + self-rotating ticks (direction 1)
  ctx.strokeStyle = hexToRgba(TEAL, 0.85 + glowA * 0.1)
  ctx.lineWidth = 1.2
  ctx.beginPath(); ctx.arc(cx, cy, r * 0.40, 0, Math.PI * 2); ctx.stroke()
  const tealSpin = now * 0.0012
  for (let i = 0; i < 24; i++) {
    const a = (i / 24) * Math.PI * 2 + tealSpin
    const major = i % 6 === 0
    const r0 = r * (major ? 0.380 : 0.388)
    const r1 = r * (major ? 0.424 : 0.412)
    ctx.strokeStyle = hexToRgba(major ? CYAN : TEAL, major ? 1.0 : 0.55)
    ctx.lineWidth = major ? 1.6 : 0.7
    ctx.beginPath()
    ctx.moveTo(cx + Math.cos(a) * r0, cy + Math.sin(a) * r0)
    ctx.lineTo(cx + Math.cos(a) * r1, cy + Math.sin(a) * r1)
    ctx.stroke()
  }
  // Gold accent ring outside the teal ring — base circle + counter-rotating ticks (direction -1)
  ctx.strokeStyle = hexToRgba(GOLD, 0.6 + glowA * 0.15)
  ctx.lineWidth = 1.0
  ctx.beginPath(); ctx.arc(cx, cy, r * 0.455, 0, Math.PI * 2); ctx.stroke()
  const goldSpin = -now * 0.0016
  for (let i = 0; i < 18; i++) {
    const a = (i / 18) * Math.PI * 2 + goldSpin
    const major = i % 3 === 0
    const r0 = r * (major ? 0.438 : 0.446)
    const r1 = r * (major ? 0.474 : 0.464)
    ctx.strokeStyle = hexToRgba(GOLD, major ? 1.0 : 0.55)
    ctx.lineWidth = major ? 1.4 : 0.7
    ctx.beginPath()
    ctx.moveTo(cx + Math.cos(a) * r0, cy + Math.sin(a) * r0)
    ctx.lineTo(cx + Math.cos(a) * r1, cy + Math.sin(a) * r1)
    ctx.stroke()
  }
  // Tiny bright dot dead center
  ctx.fillStyle = hexToRgba(CYAN, 1)
  ctx.beginPath(); ctx.arc(cx, cy, 2.0 + glowA * 1.0, 0, Math.PI * 2); ctx.fill()
}

// =====================================================================
// Hub burst — reference-style golden light fan on the sphere's LEFT rim,
// glowing gold nodes, diagonal connector lines, teal fan on the RIGHT,
// and huge concentric outer arcs.
// =====================================================================
function _drawHubBurst(ctx, Z, cs) {
  const { sphereCx: cx, sphereCy: cy, sphereR: r } = Z
  const isDark = _isDark()
  const hexToRgba = (hex, a) => {
    const h = hex.replace('#','')
    return `rgba(${parseInt(h.substring(0,2),16)},${parseInt(h.substring(2,4),16)},${parseInt(h.substring(4,6),16)},${a})`
  }
  const TEAL = isDark ? '#2dd4bf' : '#0d9488'
  const GOLD = isDark ? '#fbbf24' : '#d97706'
  const GOLD_BRIGHT = isDark ? '#ffe9b0' : '#b45309'
  const now = Date.now()

  // ---------- 1. Huge concentric FULL circles (very faint, background texture) ----------
  for (const [rad, al, lw] of [[1.35, 0.16, 0.9], [1.80, 0.09, 0.7], [2.30, 0.05, 0.5]]) {
    ctx.strokeStyle = hexToRgba(TEAL, al)
    ctx.lineWidth = lw
    ctx.beginPath(); ctx.arc(cx, cy, r * rad, 0, Math.PI * 2); ctx.stroke()
  }

  // ---------- 2. Single golden glow node on the LEFT rim ----------
  // angle: π = direct left — the single entry point for all incoming flows
  const nodeAngles = [Math.PI]
  const nodes = nodeAngles.map(a => ({
    x: cx + Math.cos(a) * r,
    y: cy + Math.sin(a) * r,
    a
  }))

  // ---------- 3. GOLD FLUID PARTICLES streaming into the single node ----------
  if (!_goldParts.length || Math.abs(_goldPartsR - r) > 8) {
    _goldParts.length = 0
    _goldPartsR = r
    const CNT = 48
    const spread = 1.8
    const maxLen = r * 2.8
    for (let i = 0; i < CNT; i++) {
      const t = i / (CNT - 1)
      _goldParts.push({
        ni: 0,
        ang: Math.PI + (t - 0.5) * spread + (Math.random() - 0.5) * 0.06,
        len: maxLen * (0.35 + Math.random() * 0.65),
        bend: (Math.random() < 0.5 ? -1 : 1) * (0.06 + Math.random() * 0.16),
        t: Math.random(),
        sp: 0.0028 + Math.random() * 0.0042,
        sz: 0.8 + Math.random() * 1.2,
        ph: Math.random() * Math.PI * 2,
      })
    }
  }
  for (const p of _goldParts) {
    p.t += p.sp
    if (p.t > 1) { p.t = 0; p.ang += (Math.random() - 0.5) * 0.03 }
    const n = nodes[p.ni]
    const ex = n.x + Math.cos(p.ang) * p.len
    const ey = n.y + Math.sin(p.ang) * p.len
    const mx = (n.x + ex) / 2, my = (n.y + ey) / 2
    const dxr = ex - n.x, dyr = ey - n.y
    const dlr = Math.hypot(dxr, dyr) || 1
    const wob = Math.sin(now * 0.002 + p.ph + p.t * 6) * 0.05
    const b = (p.bend + wob) * dlr
    const cxq = mx + (-dyr / dlr) * b, cyq = my + (dxr / dlr) * b
    const tt = 1 - p.t                               // REVERSED: particles travel far-left → INTO the node
    const a = Math.sin(tt * Math.PI) * 0.85          // fade in & out along the channel
    const u = 1 - tt
    const x = u * u * n.x + 2 * u * tt * cxq + tt * tt * ex
    const y = u * u * n.y + 2 * u * tt * cyq + tt * tt * ey
    // quadratic bezier tangent (direction of travel) → single elongated streak
    let vx = 2 * u * (cxq - n.x) + 2 * tt * (ex - cxq)
    let vy = 2 * u * (cyq - n.y) + 2 * tt * (ey - cyq)
    const vl = Math.hypot(vx, vy) || 1
    const tailLen = 14 + p.sz * 8                     // streak length scales with particle size
    // NOTE: gold travels t:1→0 (into node) — bezier tangent points the other way, so tail goes ALONG +tangent
    const tx = x + (vx / vl) * tailLen
    const ty = y + (vy / vl) * tailLen
    const lg = ctx.createLinearGradient(tx, ty, x, y)
    lg.addColorStop(0, hexToRgba(GOLD, 0))
    lg.addColorStop(1, hexToRgba(GOLD, a))
    ctx.strokeStyle = lg
    ctx.lineWidth = p.sz * 1.6
    ctx.lineCap = 'round'
    ctx.beginPath(); ctx.moveTo(tx, ty); ctx.lineTo(x, y); ctx.stroke()
    if (p.sz > 1.6) {
      ctx.fillStyle = hexToRgba('#ffffff', a * 0.55)
      ctx.beginPath(); ctx.arc(x, y, 0.55, 0, Math.PI * 2); ctx.fill()
    }
  }

  // ---------- 4. Node halo (small ambient glow, drawn UNDER the streams) ----------
  const n0 = nodes[0]
  const pulse = 0.85 + Math.sin(now * 0.003) * 0.15
  const R = 14 * pulse
  const halo = ctx.createRadialGradient(n0.x, n0.y, 0, n0.x, n0.y, R)
  halo.addColorStop(0, hexToRgba(GOLD, 0.45))
  halo.addColorStop(0.35, hexToRgba(GOLD, 0.16))
  halo.addColorStop(1, hexToRgba(GOLD, 0))
  ctx.fillStyle = halo
  ctx.beginPath(); ctx.arc(n0.x, n0.y, R, 0, Math.PI * 2); ctx.fill()

  // ---------- 4b. Neural network: left rim opening → LEFT side of INNER rings ----------
  // 250 connections fan from the left rim opening (entry arc centered at π on the
  // outer ring) into the LEFT side of the two innermost rings (teal 0.40r, gold
  // 0.455r) — the side facing the entry. Anchored ALONG the rings, never one point.
  // Odd/even connections take opposite bend paths so they cross and interleave
  // (neural crossover). Colors: 12-color palette (no gold, no teal), cycled.
  const NN_COLORS = [
    '#ff4757', '#ff6348', '#ffa502', '#2ed573', '#7bed9f',
    '#70a1ff', '#1e90ff', '#5352ed', '#a55eea', '#f368e0',
    '#ff6b81', '#e84393',
  ]
  const NN_COUNT = 250
  const SPREAD = 1.0
  const nnPhase = now * 0.0008
  ctx.lineCap = 'round'
  for (let i = 0; i < NN_COUNT; i++) {
    const t = i / (NN_COUNT - 1)
    // End: LEFT side of the two innermost rings (teal 0.40r / gold 0.455r),
    // alternating between the two — the side FACING the entry opening (π).
    const endAngle = Math.PI + (t - 0.5) * SPREAD
    const endRad = r * ((i % 2 === 0) ? 0.40 : 0.455)
    const ex = cx + Math.cos(endAngle) * endRad
    const ey = cy + Math.sin(endAngle) * endRad

    // Start points ON the outer sphere ring (left-facing arc), so the streams
    // actually land on the ring instead of floating at a flat x. Same angular
    // spread as the inner-ring targets but at radius r (wider), so lines fan inward.
    const startAngle = Math.PI + (t - 0.5) * SPREAD
    const sx = cx + Math.cos(startAngle) * r * 0.99
    const sy = cy + Math.sin(startAngle) * r * 0.99 + Math.sin(i * 0.7) * r * 0.015

    const ringColor = NN_COLORS[i % NN_COLORS.length]

    const dx = ex - sx, dy = ey - sy
    const dl = Math.hypot(dx, dy) || 1
    // Crossover: odd/even streams bend in opposite vertical directions.
    const cross = (i % 2 === 0) ? 1 : -1
    const bendMag = dl * (0.05 * cross + 0.012 * Math.sin(i * 0.9))
    // Both openings keep full spread; the middle is pinched halfway to the center
    // so the bundle narrows in the middle (lens shape) while ends stay wide.
    const naturalMid = (sy + ey) * 0.5
    const midY = cy + (naturalMid - cy) * 0.5
    const c1x = sx + dx * 0.35
    const c1y = midY + bendMag
    const c2x = sx + dx * 0.65
    const c2y = midY - bendMag

    // glow halo
    ctx.strokeStyle = hexToRgba(ringColor, 0.06)
    ctx.lineWidth = 1.2
    ctx.beginPath(); ctx.moveTo(sx, sy); ctx.bezierCurveTo(c1x, c1y, c2x, c2y, ex, ey); ctx.stroke()
    // crisp core
    ctx.strokeStyle = hexToRgba(ringColor, 0.35)
    ctx.lineWidth = 0.4
    ctx.beginPath(); ctx.moveTo(sx, sy); ctx.bezierCurveTo(c1x, c1y, c2x, c2y, ex, ey); ctx.stroke()

    // startpoint node dot — left opening anchors on the rim, mirroring the right
    ctx.fillStyle = hexToRgba(ringColor, 0.55)
    ctx.beginPath(); ctx.arc(sx, sy, 0.8, 0, Math.PI * 2); ctx.fill()

    // endpoint node dot
    ctx.fillStyle = hexToRgba(ringColor, 0.70)
    ctx.beginPath(); ctx.arc(ex, ey, 0.9, 0, Math.PI * 2); ctx.fill()

    // traveling signal pulse — cross-transmission: even streams flow left→right,
    // odd streams flow right→left, so signals cross each other in the pinched middle.
    const pulseT = (nnPhase + i * 0.13) % 1.0
    const forward = (i % 2 === 0)
    const travelT = forward ? pulseT : (1 - pulseT)
    const u = 1 - travelT
    const px2 = u*u*u*sx + 3*u*u*travelT*c1x + 3*u*travelT*travelT*c2x + travelT*travelT*travelT*ex
    const py2 = u*u*u*sy + 3*u*u*travelT*c1y + 3*u*travelT*travelT*c2y + travelT*travelT*travelT*ey
    const pulseA = Math.sin(pulseT * Math.PI)
    // pulse uses the stream's own color (not all white)
    ctx.fillStyle = hexToRgba(ringColor, pulseA * 0.95)
    ctx.beginPath(); ctx.arc(px2, py2, 1.2, 0, Math.PI * 2); ctx.fill()
  }
  ctx.lineCap = 'butt'

  // Landing highlights — the channel rests ON the LEFT side of the two inner rings
  for (const [rr, col] of [[0.40, TEAL], [0.455, GOLD]]) {
    ctx.strokeStyle = hexToRgba(col, 0.30)
    ctx.lineWidth = 2.4
    ctx.beginPath(); ctx.arc(cx, cy, r * rr, Math.PI - SPREAD / 2 - 0.05, Math.PI + SPREAD / 2 + 0.05); ctx.stroke()
  }

  // ---------- 4c. LEFT RIM OPENING — gold arc resting on the ring ----------
  // The channel's left mouth is an open arc ON the rim (matching the entry fan),
  // not the single gold node: bright rim arc spanning the entry fan + radial end
  // caps forming the mouth. The hub core is re-drawn on top only as a marker.
  const openHalf = SPREAD / 2 + 0.06
  // soft wide underlay + crisp bright arc sitting on the ring
  ctx.lineCap = 'round'
  ctx.strokeStyle = hexToRgba(GOLD, 0.22)
  ctx.lineWidth = 4.5
  ctx.beginPath(); ctx.arc(cx, cy, r * 1.0, Math.PI - openHalf, Math.PI + openHalf); ctx.stroke()
  ctx.strokeStyle = hexToRgba(GOLD_BRIGHT, 0.9)
  ctx.lineWidth = 1.6
  ctx.beginPath(); ctx.arc(cx, cy, r * 1.0, Math.PI - openHalf, Math.PI + openHalf); ctx.stroke()
  ctx.lineCap = 'butt'
  // radial end caps — the mouth of the opening
  for (const capA of [Math.PI - openHalf, Math.PI + openHalf]) {
    const cpx = Math.cos(capA), cpy = Math.sin(capA)
    ctx.strokeStyle = hexToRgba(GOLD_BRIGHT, 0.95)
    ctx.lineWidth = 1.6
    ctx.beginPath()
    ctx.moveTo(cx + cpx * r * 0.955, cy + cpy * r * 0.955)
    ctx.lineTo(cx + cpx * r * 1.045, cy + cpy * r * 1.045)
    ctx.stroke()
  }
  // hub core on top — a small marker ON the opening, not the sole origin
  ctx.fillStyle = hexToRgba(GOLD_BRIGHT, 0.98)
  ctx.beginPath(); ctx.arc(n0.x, n0.y, 3.0 * pulse, 0, Math.PI * 2); ctx.fill()
  // tiny white-hot center
  ctx.fillStyle = hexToRgba('#ffffff', 0.9)
  ctx.beginPath(); ctx.arc(n0.x, n0.y, 1.2, 0, Math.PI * 2); ctx.fill()

  // ---------- 5. TEAL FLUID PARTICLES streaming out of the RIGHT rim (same style as gold side) ----------
  if (!_tealParts.length || Math.abs(_tealPartsR - r) > 8) {
    _tealParts.length = 0
    _tealPartsR = r
    const CNT = 42
    for (let i = 0; i < CNT; i++) {
      const t = i / (CNT - 1)
      _tealParts.push({
        startA: (t - 0.5) * 0.9 + (Math.random() - 0.5) * 0.04,
        len: r * (1.6 + Math.random() * 1.2),
        vfan: (t - 0.5) * r * 1.2,
        t: Math.random(),
        sp: 0.0030 + Math.random() * 0.0040,
        sz: 0.8 + Math.random() * 1.2,
        ph: Math.random() * Math.PI * 2,
      })
    }
  }
  for (const p of _tealParts) {
    p.t += p.sp
    if (p.t > 1) p.t = 0
    const sx = cx + Math.cos(p.startA) * r
    const sy = cy + Math.sin(p.startA) * r
    const ex = sx + p.len
    const ey = sy + p.vfan
    // Same S-curve channel as before, control points sway with time
    const w1 = Math.sin(p.ph + now * 0.0008) * r * 0.22
    const w2 = Math.sin(p.ph + 1.4 + now * 0.0011) * r * 0.30
    const c1x = sx + p.len * 0.35, c1y = sy + w1
    const c2x = sx + p.len * 0.70, c2y = ey - w2
    // cubic bezier eval + tangent (direction of travel) → single elongated streak
    const tt = p.t, u = 1 - tt
    const x = u*u*u*sx + 3*u*u*tt*c1x + 3*u*tt*tt*c2x + tt*tt*tt*ex
    const y = u*u*u*sy + 3*u*u*tt*c1y + 3*u*tt*tt*c2y + tt*tt*tt*ey
    let vx = 3*u*u*(c1x - sx) + 6*u*tt*(c2x - c1x) + 3*tt*tt*(ex - c2x)
    let vy = 3*u*u*(c1y - sy) + 6*u*tt*(c2y - c1y) + 3*tt*tt*(ey - c2y)
    const vl = Math.hypot(vx, vy) || 1
    const a = Math.sin(tt * Math.PI) * 0.85
    const tailLen = 14 + p.sz * 8                     // streak length scales with particle size
    const tx = x - (vx / vl) * tailLen
    const ty = y - (vy / vl) * tailLen
    const lg = ctx.createLinearGradient(tx, ty, x, y)
    lg.addColorStop(0, hexToRgba(TEAL, 0))
    lg.addColorStop(1, hexToRgba(TEAL, a))
    ctx.strokeStyle = lg
    ctx.lineWidth = p.sz * 1.6
    ctx.lineCap = 'round'
    ctx.beginPath(); ctx.moveTo(tx, ty); ctx.lineTo(x, y); ctx.stroke()
    if (p.sz > 1.6) {
      ctx.fillStyle = hexToRgba('#ffffff', a * 0.5)
      ctx.beginPath(); ctx.arc(x, y, 0.55, 0, Math.PI * 2); ctx.fill()
    }
  }
}

function _stopPipelineCanvas() {
  if (_pipeRaf) { cancelAnimationFrame(_pipeRaf); _pipeRaf = null }
  _pipeFlows.length = 0; _pipeExits.length = 0
  _goldParts.length = 0; _tealParts.length = 0; _goldPartsR = 0; _tealPartsR = 0
}

onMounted(() => {
  requestAnimationFrame(() => _initPipelineCanvas())
})

onBeforeUnmount(() => {
  _stopPipelineCanvas()
})

// Restart when props change (e.g. data reload) — not needed; canvas reads CSS vars each frame
</script>

<template>
  <section class="ov">
    <div class="ov-stage">
      <canvas ref="pipelineCanvas" class="ov-canvas"></canvas>
      <div class="ov-inputzone">
        <div class="ov-zone-label">INPUT / ENTRY</div>
        <div class="ov-inputs" @mouseenter="ovScrolling = false" @mouseleave="ovScrolling = true">
          <div class="ov-scroll-track" :class="{ paused: !ovScrolling }">
            <template v-for="dup in 2" :key="'dup-'+dup">
              <div v-for="(c, idx) in clientNodes" :key="c.id+'-'+dup"
                   class="ov-input"
                   :data-cid="c.id"
                   @mouseenter="ovShowTip($event, c)"
                   @mouseleave="ovHideTip"
                   :class="{ online: c.online, disabled: c.disabled, ikev2: c.via==='ikev2', vless: c.via==='vless' }"
                   :style="{ borderColor: _ovLine() }">
                <span class="ov-in-dot"></span>
                <div class="ov-in-body">
                  <div class="ov-in-meta">
                    <span v-if="c.clientIp" class="meta-clientip">{{ c.clientIp }}</span>
                    <span v-if="c.region" class="meta-region">{{ c.region }}</span>
                    <span v-if="c.vpnIp" class="meta-vpnip">{{ c.vpnIp }}</span>
                  </div>
                </div>
              </div>
            </template>
          </div>
          <div v-if="!clientNodes.length" class="ov-empty">// NO ENTRANTS</div>
        </div>
      </div>
      <div class="ov-spherezone">
      </div>
      <div class="ov-outputzone">
        <div class="ov-out-label">OUTPUT / EXIT →</div>
        <div class="ov-out-info">
          <div class="ov-out-line"><span class="k">SING-BOX</span><span class="v ok">{{ proxy.available ? (proxy.version || 'OK') : 'MISSING' }}</span></div>
          <div class="ov-out-line"><span class="k">LISTEN</span><span class="v">{{ proxy.port || '—' }}</span></div>
          <div class="ov-out-line"><span class="k">PROTOCOL</span><span class="v">VLESS · REALITY</span></div>
          <div class="ov-out-line"><span class="k">UPTIME</span><span class="v">{{ proxy.running ? formatUptime(proxy.uptimeMs) : '—' }}</span></div>
        </div>
        <div class="ov-out-status" :class="{ online: proxy.running, offline: !proxy.running }">
          {{ proxy.running ? '● LIVE' : '○ DOWN' }}
        </div>
      </div>
      <!-- Custom hover tooltip -->
      <div v-if="ovTip.show && ovTip.c"
           class="ov-tip"
           :style="{ left: ovTip.x + 'px', top: ovTip.y + 'px' }">
        <div class="ov-tip-head">
          <span class="ov-tip-name">{{ ovTip.c.name }}</span>
          <span class="ov-tip-state" :class="{ on: ovTip.c.online, off: !ovTip.c.online, dis: ovTip.c.disabled }">
            {{ ovTip.c.disabled ? 'DISABLED' : ovTip.c.online ? 'ONLINE' : 'IDLE' }}
          </span>
        </div>
        <div class="ov-tip-body">
          <div v-if="ovTip.c.via" class="ov-tip-row"><span class="tk">Protocol</span><span class="tv">{{ ovTip.c.via.toUpperCase() }}</span></div>
          <div v-if="ovTip.c.clientIp" class="ov-tip-row"><span class="tk">Client IP</span><span class="tv code">{{ ovTip.c.clientIp }}</span></div>
          <div v-if="ovTip.c.region" class="ov-tip-row"><span class="tk">Region</span><span class="tv">{{ ovTip.c.region }}</span></div>
          <div v-if="ovTip.c.vpnIp" class="ov-tip-row"><span class="tk">GPN IP</span><span class="tv code">{{ ovTip.c.vpnIp }}</span></div>
          <div v-if="ovTip.c.uuid" class="ov-tip-row"><span class="tk">UUID</span><span class="tv code">{{ ovTip.c.uuid.slice(0,8) }}…</span></div>
          <div v-if="ovTip.c.ikeUser" class="ov-tip-row"><span class="tk">IKE User</span><span class="tv code">{{ ovTip.c.ikeUser }}</span></div>
        </div>
      </div>
    </div>
  </section>
</template>

<style scoped>
.ov { padding: 0; flex: 1; display: flex; min-height: 0; }

.ov-stage {
  position: relative;
  flex: 1;
  border: 0;
  border-radius: var(--radius-md);
  overflow: hidden;
  background: var(--ov-stage-bg);
}

.ov-canvas {
  position: absolute;
  inset: 0;
  width: 100%;
  height: 100%;
  display: block;
}

.ov-zone-label, .ov-out-label {
  position: absolute;
  font-size: 10px;
  font-weight: 700;
  letter-spacing: 1.5px;
  text-transform: uppercase;
  color: var(--ov-label-muted);
  pointer-events: none;
  z-index: 3;
}

/* ---- INPUT zone ---- */
.ov-inputzone {
  position: absolute;
  top: 0; left: 0;
  width: 14%;
  height: 100%;
  border-right: 0;
  padding: 32px 8px 16px;
  z-index: 2;
  display: flex; flex-direction: column;
  overflow: hidden;
}

.ov-inputzone .ov-zone-label { top: 12px; left: 16px; }
.ov-inputs {
  flex: 1;
  margin-top: 4px;
  overflow: hidden;
  position: relative;
}
.ov-scroll-track {
  display: flex; flex-direction: column; gap: 6px;
  animation: ovMarquee 40s linear infinite;
}
.ov-scroll-track.paused { animation-play-state: paused; }
@keyframes ovMarquee {
  0%   { transform: translateY(0); }
  100% { transform: translateY(-50%); }
}

.ov-input {
  position: relative;
  left: auto; right: auto; top: auto;
  flex-shrink: 0;
  min-height: 34px;
  display: flex; align-items: flex-start; gap: 8px;
  padding: 7px 10px;
  border: 0;
  border-radius: var(--radius-sm);
  font-size: 11px;
  transition: background-color .18s, border-color .18s;
  background: transparent !important;
}
.ov-input { border: 1px solid transparent; }
.ov-input:hover { background: rgba(255,255,255,0.06) !important; }
.ov-input.disabled { opacity: 0.45; }
.ov-input.online { /* transparent bg */ }

.ov-in-body { flex: 1; min-width: 0; display: flex; flex-direction: column; gap: 2px; }
.ov-in-top { display: flex; align-items: center; gap: 6px; min-width: 0; }
.ov-in-meta {
  display: flex; flex-wrap: wrap; gap: 4px 10px;
  font-size: 10px; line-height: 1.4;
  font-family: var(--font-mono);
}
.ov-in-meta.meta-muted { font-family: inherit; color: var(--color-text-subtle); opacity: 0.7; }

/* Per-type meta colors */
.meta-clientip { color: #ff9f43; }          /* warm orange — client IP */
.meta-region   { color: #1dd1a1; font-family: inherit; }  /* teal — geo */
.meta-vpnip    { color: #54a0ff; }           /* soft blue — VPN tunnel IP */

.light .meta-clientip { color: #d35400; }
.light .meta-region   { color: #16a085; }
.light .meta-vpnip    { color: #2980b9; }
/* The actual theme selector is [data-theme="light"], .light may not exist — fallback via CSS vars below */
:root[data-theme="light"] .meta-clientip { color: #c0392b; }
:root[data-theme="light"] .meta-region   { color: #1e8449; }
:root[data-theme="light"] .meta-vpnip    { color: #2471a3; }

.ov-in-dot {
  width: 8px; height: 8px; border-radius: 50%;
  background: var(--color-text-subtle);
  flex-shrink: 0;
}
.ov-input.online .ov-in-dot {
  background: var(--color-success);
}
.ov-input.disabled .ov-in-dot { background: var(--ov-dot-disabled); }
.ov-input.ikev2.online .ov-in-dot { background: var(--color-ikev2); }
.ov-input.vless.online .ov-in-dot { background: var(--color-vless); }

.ov-in-name {
  flex: 1; min-width: 0;
  color: var(--color-text);
  font-weight: 500;
  white-space: nowrap; overflow: hidden; text-overflow: ellipsis;
}
.ov-input.disabled .ov-in-name { color: var(--color-text-subtle); opacity: 0.6; }

.ov-in-tag {
  font-size: 10px; font-weight: 700; padding: 1px 6px;
  border-radius: 4px; letter-spacing: 0.5px;
  background: var(--color-surface-sub);
  color: var(--color-text-muted);
  flex-shrink: 0;
}
.ov-input.ikev2.online .ov-in-tag { background: var(--color-ikev2-sub); color: var(--color-ikev2); }
.ov-input.vless.online .ov-in-tag { background: var(--color-vless-sub); color: var(--color-vless); }
.ov-input.disabled .ov-in-tag.tag-disabled { background: var(--ov-tag-disabled-bg); color: var(--color-text-subtle); }
.ov-in-tag.tag-idle { background: var(--ov-tag-idle-bg); color: var(--color-text-muted); }

.ov-empty {
  display: grid; place-items: center; height: 100%;
  font-family: var(--font-mono); font-size: 11px;
  color: var(--color-text-muted); opacity: 0.6;
}

/* ---- SPHERE zone ---- */
.ov-spherezone {
  position: absolute;
  top: 0; left: 28%;
  width: 56%; height: 100%;
  z-index: 2;
  pointer-events: none;
}

/* ---- OUTPUT zone ---- */
.ov-outputzone {
  position: absolute;
  top: 0; right: 0;
  width: 30%; height: 100%;
  border-left: 0;
  padding: 32px 20px 16px;
  z-index: 2;
}

.ov-outputzone .ov-out-label { top: 12px; left: 20px; }

.ov-out-info {
  position: absolute;
  left: 20px; right: 20px; bottom: 48px;
  display: flex; flex-direction: column; gap: 4px;
}

.ov-out-line {
  display: flex; justify-content: space-between;
  font-size: 11px; line-height: 1.6;
  padding: 2px 0;
}
.ov-out-line .k { color: var(--color-text-muted); font-weight: 500; letter-spacing: 0.5px; }
.ov-out-line .v { color: var(--color-text); font-family: var(--font-mono); font-size: 10px; }
.ov-out-line .v.ok { color: var(--color-success); }

.ov-out-status {
  position: absolute;
  right: 20px; bottom: 16px;
  font-size: 11px; font-weight: 700; letter-spacing: 1px;
  padding: 4px 10px; border-radius: var(--radius-pill);
  border: 0;
}
.ov-out-status.online { color: var(--color-success); background: transparent; }
.ov-out-status.offline { color: var(--color-danger); background: transparent; }

/* ---- Responsive ---- */
@media (max-width: 980px) {
  .ov-stage { min-height: 560px; }
  .ov-inputzone { width: 100%; height: auto; top: 0; left: 0; border-right: none; border-bottom: 0; padding: 36px 0 4px; }
  .ov-inputs { position: relative; display: flex; flex-wrap: wrap; gap: 6px; padding: 0 12px; }
  .ov-input { position: relative; left: auto; right: auto; top: auto !important; width: auto; min-width: 140px; }
  .ov-spherezone { left: 0; width: 100%; top: 140px; height: auto; }
  .ov-sphere-info { bottom: auto; top: 50%; }
  .ov-outputzone { left: 0; width: 100%; top: auto; bottom: 0; height: auto; padding-bottom: 12px; }
  .ov-out-info { position: relative; top: auto; padding-top: 8px; }
  .ov-out-status { bottom: 12px; }
}

/* ---- Custom hover tooltip ---- */
.ov-tip {
  position: fixed;
  z-index: 9999;
  min-width: 210px;
  max-width: 280px;
  padding: 10px 12px;
  font-size: 12px;
  line-height: 1.5;
  background: rgba(18, 18, 20, 0.94);
  border: 1px solid rgba(255,255,255,0.22);
  border-radius: 8px;
  color: #eaeaea;
  backdrop-filter: blur(10px);
  box-shadow: 0 10px 30px rgba(0,0,0,0.4);
  pointer-events: none;
  font-family: -apple-system, BlinkMacSystemFont, "Segoe UI", Roboto, sans-serif;
}
:root[data-theme="light"] .ov-tip {
  background: rgba(255,255,255,0.96);
  border-color: rgba(0,0,0,0.18);
  color: #1c1c1c;
  box-shadow: 0 8px 24px rgba(0,0,0,0.12);
}
.ov-tip-head {
  display: flex; align-items: center; justify-content: space-between;
  gap: 10px; margin-bottom: 6px; padding-bottom: 6px;
  border-bottom: 1px dashed rgba(255,255,255,0.18);
}
:root[data-theme="light"] .ov-tip-head { border-color: rgba(0,0,0,0.12); }
.ov-tip-name { font-weight: 600; font-size: 12.5px; }
.ov-tip-state {
  font-size: 9.5px; letter-spacing: 0.8px; font-weight: 700;
  padding: 1px 6px; border-radius: 3px;
}
.ov-tip-state.on  { background: rgba(29,209,161,0.18); color: #1dd1a1; }
.ov-tip-state.off { background: rgba(127,127,127,0.2); color: #bbb; }
.ov-tip-state.dis { background: rgba(255,99,99,0.18); color: #ff6b6b; }
.ov-tip-body { display: flex; flex-direction: column; gap: 3px; }
.ov-tip-row {
  display: flex; justify-content: space-between; gap: 12px;
  font-size: 11.5px;
}
.ov-tip-row .tk { color: #9a9a9a; letter-spacing: 0.3px; }
.ov-tip-row .tv { color: #eaeaea; font-family: "SF Mono", Menlo, Consolas, monospace; font-size: 11px; }
:root[data-theme="light"] .ov-tip-row .tk { color: #666; }
:root[data-theme="light"] .ov-tip-row .tv { color: #1c1c1c; }
</style>
