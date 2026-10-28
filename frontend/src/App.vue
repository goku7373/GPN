<script setup>
import { computed, onBeforeUnmount, onMounted, ref } from 'vue'
import { api } from './api'
import { useTheme } from './composables/useTheme'

const props = defineProps({ username: { type: String, default: 'admin' } })
const emit = defineEmits(['logout'])
const { mode, toggle } = useTheme()

const data = ref(null)
const loading = ref(true)
const error = ref('')
const toast = ref('')
const newName = ref('')
const creating = ref(false)
const busyId = ref('')
let timer = null
let autoTestTimer = null
let toastTimer = null

const proxy = computed(() => data.value?.proxy || {})
const server = computed(() => data.value?.server || {})
const ikev2 = computed(() => data.value?.ikev2 || {})
const clients = computed(() => data.value?.clients || [])
const TABS = ['overview', 'sessions', 'config', 'logs']
const CONFIG_TABS = ['server', 'ikev2', 'vless']
const SESSION_TABS = ['vless', 'ikev2']

/** Menu state is stored in the URL hash so a refresh stays on the same menu; #/detail/<type>/<mode>/<key> is a standalone detail page */
function parseHash() {
  const parts = (location.hash || '').replace(/^#\/?/, '').split('/')
  const [name, sub] = parts
  if (name === 'detail') {
    const type = parts[1]
    const mode = parts[2]
    const key = decodeURIComponent(parts.slice(3).join('/'))
    if ((type === 'ikev2' || type === 'vless') && (mode === 'flows' || mode === 'audit') && key) {
      return { tab: 'detail', configTab: 'server', sessionTab: 'vless', detail: { type, mode, key } }
    }
  }
  const t = TABS.includes(name) ? name : 'overview'
  const c = CONFIG_TABS.includes(sub) ? sub : 'server'
  const s = SESSION_TABS.includes(sub) ? sub : 'vless'
  return { tab: t, configTab: c, sessionTab: s, detail: null }
}

const initial = parseHash()
const tab = ref(initial.tab)
const configTab = ref(initial.configTab)
const sessionTab = ref(initial.sessionTab)
const sessions = ref(null)

/** Detail page (opened in a new browser tab): type=ikev2|vless, mode=flows|audit */
const detail = ref(initial.detail)
const detailData = ref(null)
const detailLoading = ref(false)
const detailError = ref('')
const detailIp = ref('')
const detailPort = ref('')
const DETAIL_FLOW_LIMITS = [500, 2000, 10000]
const flowLimit = ref(500)

const logOptions = [200, 500, 1000, 2000]
const logLines = ref(200)
const ANSI_RE = /\u001B\[[0-9;?]*[ -/]*[@-~]/g
const logText = computed(() =>
  (proxy.value.logTail || []).map((l) => String(l).replace(ANSI_RE, '').replace(/\r/g, '')).join('\n')
)

const statusText = computed(() => {
  if (!proxy.value.available) return 'Proxy not deployed'
  return proxy.value.running ? 'Exit Running' : 'Exit Stopped'
})
const statusClass = computed(() => {
  if (!proxy.value.available) return 'badge-warn'
  return proxy.value.running ? 'badge-ok' : 'badge-danger'
})

const hostPlaceholder = computed(() => server.value.host === 'YOUR_SERVER_IP')

/* ---------- Overview: network topology view ---------- */
/** Relative coordinates (percent) of the central exit node */
const OV_CORE = { x: 52, y: 40 }
const enabledClients = computed(() => clients.value.filter((c) => c.enabled).length)

/** Nodes of the full chain: clients → two entries → exit node → public internet, plus the core node */
const ovNodes = computed(() => [
  {
    key: 'clients', label: 'Clients', x: 10, y: 40,
    sub: `${enabledClients.value} / ${clients.value.length} accounts enabled`,
  },
  {
    key: 'vless', label: 'VLESS Entry', x: 30, y: 16,
    sub: `:${proxy.value.port || '—'} · TCP`,
  },
  {
    key: 'ikev2', label: 'IKEv2 Gateway', x: 30, y: 64,
    sub: 'EAP-MSCHAPv2',
  },
  {
    key: 'egress', label: 'Public Exit', x: 76, y: 40,
    sub: server.value.host || '—',
  },
  {
    key: 'singbox', label: 'sing-box Core', x: 52, y: 68,
    sub: proxy.value.available ? (proxy.value.version || 'Installed') : 'Not installed',
  },
])

/** Chain links (viewBox 0 0 100 100, percent coordinates) */
const ovSegments = [
  'M10 40 L30 16',
  'M10 40 L30 64',
  'M30 16 L52 40',
  'M30 64 L52 40',
  'M52 40 L76 40',
  'M52 40 L52 68',
]

/** Bottom stats bar */
const ovMetrics = computed(() => {
  const total = clients.value.length
  return [
    {
      label: 'Exit Status',
      value: proxy.value.running ? 'Running' : 'Stopped',
      pct: proxy.value.running ? 100 : 0,
    },
    {
      label: 'Uptime',
      value: proxy.value.running ? formatUptime(proxy.value.uptimeMs) : '—',
      pct: null,
    },
    {
      label: 'Clients Enabled',
      value: `${enabledClients.value} / ${total}`,
      pct: total ? Math.round((enabledClients.value / total) * 100) : 0,
    },
    {
      label: 'sing-box',
      value: proxy.value.available ? (proxy.value.version || 'Installed') : 'Not installed',
      pct: null,
    },
  ]
})

/** Floating analysis cards: short sentences highlighting the states that need the most attention */
const ovAnalysis = computed(() => {
  const p = proxy.value
  const total = clients.value.length
  const off = total - enabledClients.value
  return [
    {
      title: 'Exit Service',
      tone: p.running ? 'i-ok' : 'i-bad',
      tag: p.running ? formatUptime(p.uptimeMs) : 'Stopped',
      detail: p.available
        ? (p.running ? `sing-box is running and listening on port ${p.port}.` : 'sing-box is not running; the proxy exit is unavailable.')
        : 'sing-box is not installed; deploy the proxy core first.',
    },
    {
      title: 'Accounts',
      tone: off ? 'i-warn' : 'i-ok',
      tag: `${total ? Math.round((enabledClients.value / total) * 100) : 0}%`,
      detail: total ? `${enabledClients.value} accounts enabled, ${off} disabled.` : 'No accounts created yet.',
    },
    {
      title: 'IKEv2 Service',
      tone: ikev2.value.available ? 'i-ok' : 'i-warn',
      tag: ikev2.value.available ? 'Available' : 'Not ready',
      detail: ikev2.value.available
        ? `Remote ID ${ikev2.value.remoteId || ikev2.value.host || '—'}; system VPN can connect directly.`
        : 'strongSwan is not ready; system VPN cannot connect.',
    },
  ]
})

async function load() {
  try {
    data.value = await api.overview(logLines.value)
    error.value = ''
  } catch (e) {
    error.value = e.message
  } finally {
    loading.value = false
  }
}

async function setLogLines(n) {
  if (logLines.value === n) return
  logLines.value = n
  await load()
}

function showToast(message) {
  toast.value = message
  clearTimeout(toastTimer)
  toastTimer = setTimeout(() => { toast.value = '' }, 1800)
}

async function copy(text, label) {
  try {
    await navigator.clipboard.writeText(text)
  } catch {
    const ta = document.createElement('textarea')
    ta.value = text
    ta.style.position = 'fixed'
    ta.style.opacity = '0'
    document.body.appendChild(ta)
    ta.select()
    document.execCommand('copy')
    document.body.removeChild(ta)
  }
  showToast(`${label} copied`)
}

async function copyParams() {
  const s = server.value
  const text = [
    `Server: ${s.host}`,
    `Port: ${s.port}`,
    'Protocol: VLESS',
    'Transport: TCP (XTLS-Vision)',
    `SNI: ${s.sni}`,
    `Public Key: ${s.publicKey}`,
    `Short ID: ${s.shortId}`,
    `Fingerprint: ${s.fingerprint}`
  ].join('\n')
  await copy(text, 'Connection parameters')
}

async function addClient() {
  const name = newName.value.trim()
  if (!name || creating.value) return
  creating.value = true
  try {
    await api.createClient(name)
    newName.value = ''
    await load()
    showToast('Client created')
  } catch (e) {
    error.value = e.message
  } finally {
    creating.value = false
  }
}

async function toggleClient(c) {
  busyId.value = c.id
  try {
    await api.toggleClient(c.id)
    await load()
  } catch (e) {
    error.value = e.message
  } finally {
    busyId.value = ''
  }
}

async function rotateClient(c) {
  if (!window.confirm(`Rotate credentials for "${c.name}"? The old config will stop working immediately and must be re-imported.`)) return
  busyId.value = c.id
  try {
    await api.rotateClient(c.id)
    await load()
    showToast('Credentials rotated')
  } catch (e) {
    error.value = e.message
  } finally {
    busyId.value = ''
  }
}

async function removeClient(c) {
  if (!window.confirm(`Delete "${c.name}"? The credentials will stop working immediately and cannot be recovered.`)) return
  busyId.value = c.id
  try {
    await api.deleteClient(c.id)
    await load()
    showToast('Client deleted')
  } catch (e) {
    error.value = e.message
  } finally {
    busyId.value = ''
  }
}

async function copyLink(c) {
  await copy(c.uri, 'VLESS link')
}

/** Map the EAP username in a session to a panel client, for ban / unban */
function clientOfSession(s) {
  return clients.value.find((c) => c.ikeUser === s.user) || null
}

async function kickSession(s) {
  if (!window.confirm(`Kick "${s.client}" offline? The device can reconnect on its own.`)) return
  busyId.value = `ike-${s.id}`
  try {
    await api.terminateIke(s.id)
    showToast('Disconnect sent')
    setTimeout(() => { loadSessions() }, 1200)
  } catch (e) {
    error.value = e.message
  } finally {
    busyId.value = ''
  }
}

async function banSession(s) {
  const c = clientOfSession(s)
  if (!c) {
    error.value = 'No client credential matches this session'
    return
  }
  if (!window.confirm(`Ban "${c.name}"? The credentials are revoked and the device is kicked immediately; it cannot reconnect until unbanned.`)) return
  busyId.value = `ike-${s.id}`
  try {
    await api.banClient(c.id)
    await load()
    await loadSessions()
    showToast(`Banned ${c.name}`)
  } catch (e) {
    error.value = e.message
  } finally {
    busyId.value = ''
  }
}

async function unbanDevice(c) {
  busyId.value = c.id
  try {
    await api.unbanClient(c.id)
    await load()
    showToast(`Unbanned ${c.name}`)
  } catch (e) {
    error.value = e.message
  } finally {
    busyId.value = ''
  }
}

async function copyConfig(c) {
  busyId.value = c.id
  try {
    const detail = await api.shareClient(c.id)
    await copy(detail.singbox, 'sing-box config')
  } catch (e) {
    error.value = e.message
  } finally {
    busyId.value = ''
  }
}

async function copySub(c) {
  await copy(`${location.origin}${c.subPath}`, 'Subscription URL')
}

async function copyIke(c) {
  await copy(`Username: ${c.ikeUser}\nPassword: ${c.ikePass}\nServer: ${ikev2.value.host}\nRemote ID: ${ikev2.value.remoteId}`, 'IKEv2 credentials')
}

async function restart() {
  busyId.value = 'proxy'
  try {
    await api.restartProxy()
    await load()
    showToast('Restarted')
  } catch (e) {
    error.value = e.message
  } finally {
    busyId.value = ''
  }
}

function formatUptime(ms) {
  if (!ms) return '—'
  const s = Math.floor(ms / 1000)
  if (s < 60) return `${s}s`
  const m = Math.floor(s / 60)
  if (m < 60) return `${m}m`
  const h = Math.floor(m / 60)
  if (h < 24) return `${h}h ${m % 60}m`
  return `${Math.floor(h / 24)}d ${h % 24}h`
}

function shortDate(ts) {
  return new Date(ts).toLocaleDateString('en-US', { month: '2-digit', day: '2-digit' })
}

async function loadSessions() {
  try {
    sessions.value = await api.sessions()
  } catch (e) {
    sessions.value = { vless: [], ikev2: [], vlessCount: 0, ikeCount: 0, error: e.message }
  }
}

/** Speed test results stored per device: ike-<uniqueid> → { total, upRate, downRate } */
const speedResults = ref({})
const testingId = ref('')
const testingAll = ref(false)
const lastTestAt = ref(0)
const searchQuery = ref('')
const filterStatus = ref('all')
const filterQuality = ref('all')
const openDD = ref('')

const statusOptions = computed(() => [
  { value: 'all', label: 'All' },
  { value: 'online', label: `Online (${sessions.value?.ikeCount || 0})` },
  { value: 'offline', label: `Offline (${sessions.value?.ikeOfflineCount || 0})` },
  { value: 'banned', label: `Banned (${clients.value.filter((c) => !c.enabled).length})` }
])

const qualityOptions = [
  { value: 'all', label: 'All' },
  { value: 'fast', label: 'Fast' },
  { value: 'good', label: 'Good' },
  { value: 'fair', label: 'Fair' },
  { value: 'poor', label: 'Poor' },
  { value: 'bad', label: 'Bad' },
  { value: 'none', label: 'Untested' }
]

const statusLabel = computed(
  () => statusOptions.value.find((o) => o.value === filterStatus.value)?.label || 'All'
)
const qualityLabel = computed(
  () => qualityOptions.find((o) => o.value === filterQuality.value)?.label || 'All'
)

function toggleDD(name) {
  openDD.value = openDD.value === name ? '' : name
  openMenu.value = ''
}

function qualityOfSpeed(total) {
  if (total >= 1024 * 1024) return { label: 'Fast', key: 'fast', cls: 'q-fast' }
  if (total >= 100 * 1024) return { label: 'Good', key: 'good', cls: 'q-good' }
  if (total >= 10 * 1024) return { label: 'Fair', key: 'fair', cls: 'q-fair' }
  if (total >= 1024) return { label: 'Poor', key: 'poor', cls: 'q-poor' }
  return { label: 'Bad', key: 'bad', cls: 'q-bad' }
}

function qualityKeyOf(s) {
  const r = speedResults.value[`ike-${s.id}`]
  return r ? qualityOfSpeed(r.total).key : 'none'
}

const ikeRows = computed(() => {
  const q = searchQuery.value.trim().toLowerCase()
  return (sessions.value?.ikev2 || []).filter((s) => {
    const hit = !q || [s.client, s.remoteHost, s.vpnIp, s.region]
      .some((v) => String(v || '').toLowerCase().includes(q))
    const qualityOk = filterQuality.value === 'all' || qualityKeyOf(s) === filterQuality.value
    const statusOk = filterStatus.value === 'all'
      || (filterStatus.value === 'online' && s.online)
      || (filterStatus.value === 'offline' && !s.online)
    return hit && qualityOk && statusOk
  })
})

const bannedRows = computed(() => {
  const q = searchQuery.value.trim().toLowerCase()
  return clients.value
    .filter((c) => !c.enabled)
    .filter((c) => !q || String(c.name || '').toLowerCase().includes(q))
})

function speedText(v) {
  if (v == null) return ''
  if (v < 1024) return `${Math.round(v)} B/s`
  if (v < 1024 * 1024) return `${(v / 1024).toFixed(1)} KB/s`
  return `${(v / 1024 / 1024).toFixed(1)} MB/s`
}

async function speedTest(s) {
  if (testingId.value || testingAll.value) return
  const key = `ike-${s.id}`
  testingId.value = key
  try {
    const r = await api.speedTestIke(s.id)
    speedResults.value = { ...speedResults.value, [key]: r }
    showToast(`Speed test done: ${qualityOfSpeed(r.total).label}`)
  } catch (e) {
    error.value = e.message
  } finally {
    testingId.value = ''
  }
}

/** One-click speed test: test all online devices in one window and write results back to each row's network status */
async function speedTestAllDevices() {
  if (testingAll.value || testingId.value) return
  const rows = sessions.value?.ikev2 || []
  if (!rows.length) return
  testingAll.value = true
  try {
    const res = await api.speedTestAll()
    const next = { ...speedResults.value }
    for (const [id, r] of Object.entries(res)) next[`ike-${id}`] = r
    speedResults.value = next
    lastTestAt.value = Date.now()
  } catch (e) {
    error.value = e.message
  } finally {
    testingAll.value = false
  }
}

function lastTestText() {
  if (!lastTestAt.value) return 'No auto test yet'
  const d = new Date(lastTestAt.value)
  return `Last ${String(d.getHours()).padStart(2, '0')}:${String(d.getMinutes()).padStart(2, '0')}`
}

const openMenu = ref('')

/** Device monitor view: null means still on the connection list; when set, the right side switches to that device's live monitor panel */
const monitor = ref(null)
const monitorData = ref(null)
const monitorLoading = ref(false)

async function loadMonitor() {
  if (!monitor.value) return
  monitorLoading.value = !monitorData.value
  try {
    monitorData.value = monitor.value.type === 'ikev2'
      ? await api.monitorIke(monitor.value.key)
      : await api.monitorVless(monitor.value.key)
    if (monitorData.value.device && monitorData.value.device.online === false) {
      // Device went offline: clear the sample cache so stale rates/connections disappear
      flowCache.value = new Map()
      monitorData.value.flows = []
    } else {
      mergeFlows(monitorData.value.flows)
    }
  } catch (e) {
    error.value = e.message
  } finally {
    monitorLoading.value = false
  }
}

const MONITOR_STORE = 'gpn.monitor.v1'

/** Remember the device being viewed so a page refresh returns to its monitor panel */
function persistMonitor() {
  try {
    if (monitor.value) localStorage.setItem(MONITOR_STORE, JSON.stringify(monitor.value))
    else localStorage.removeItem(MONITOR_STORE)
  } catch {}
}

function restoreMonitor() {
  try {
    const raw = localStorage.getItem(MONITOR_STORE)
    if (!raw) return
    const m = JSON.parse(raw)
    if (m && (m.type === 'ikev2' || m.type === 'vless') && m.key) monitor.value = m
  } catch {}
}

function openMonitor(type, key, client) {
  monitor.value = { type, key, client }
  monitorData.value = null
  flowCache.value = new Map()
  persistMonitor()
  closeMenu()
  loadMonitor()
}

function closeMonitor() {
  monitor.value = null
  monitorData.value = null
  flowCache.value = new Map()
  persistMonitor()
}

/** Detail button: open a standalone traffic/audit page in a new browser tab */
function openDetail(mode) {
  if (!monitor.value) return
  const { type, key } = monitor.value
  const url = `${location.origin}${location.pathname}#/detail/${type}/${mode}/${encodeURIComponent(key)}`
  window.open(url, '_blank', 'noopener')
}

async function loadDetail() {
  if (!detail.value) return
  detailLoading.value = !detailData.value
  try {
    detailData.value = detail.value.type === 'ikev2'
      ? await api.monitorIke(detail.value.key)
      : await api.monitorVless(detail.value.key)
    detailError.value = ''
  } catch (e) {
    detailError.value = e.message
  } finally {
    detailLoading.value = false
  }
}

/** Active connections: only show connections with traffic (or newly established) in the last 10 seconds */
const FLOW_LIMIT = 500
const FLOW_ACTIVE_MS = 10_000
const flowCache = ref(new Map())

function flowKey(f) {
  return `${f.proto || ''}|${f.destination || ''}|${f.port || 0}`
}

/** Merge this snapshot into the cache: refresh last-active time and compute live up/down rates from the byte delta between samples */
function mergeFlows(flows) {
  const now = Date.now()
  const next = new Map()
  for (const [k, v] of flowCache.value) {
    if (now - v.lastActive < FLOW_ACTIVE_MS) next.set(k, v)
  }
  for (const f of flows || []) {
    const key = flowKey(f)
    const prev = flowCache.value.get(key)
    const up = Number(f.bytes) || 0
    const down = Number(f.down) || 0
    const dt = prev ? (now - prev.sampledAt) / 1000 : 0
    let upRate = 0
    let downRate = 0
    if (prev && dt > 0.2) {
      if (up >= prev.up) upRate = (up - prev.up) / dt
      if (down >= prev.down) downRate = (down - prev.down) / dt
    }
    // New connections are marked active right away; afterwards they only stay while bytes keep growing — idle connections disappear after 10s
    const lastActive = !prev || upRate > 0 || downRate > 0 ? now : prev.lastActive
    next.set(key, { flow: { ...f, bytes: up, down }, lastActive, sampledAt: now, up, down, upRate, downRate })
  }
  flowCache.value = next
}

const flowRows = computed(() =>
  [...flowCache.value.values()]
    .sort((a, b) => b.lastActive - a.lastActive)
    .slice(0, FLOW_LIMIT)
    .map((v) => ({ ...v.flow, upRate: v.upRate, downRate: v.downRate }))
)

/** Aggregate by destination: total bytes and connection count per destination */
function aggregate(flows) {
  const map = new Map()
  for (const f of flows || []) {
    const key = String(f.destination || '')
    if (!key) continue
    const row = map.get(key) || { destination: key, port: f.port, proto: f.proto, bytes: 0, conns: 0 }
    row.bytes += (Number(f.bytes) || 0) + (Number(f.down) || 0)
    row.conns += 1
    map.set(key, row)
  }
  return [...map.values()].sort((a, b) => b.bytes - a.bytes)
}

/** Traffic audit: rank by connection count, then by bytes on ties */
const auditRows = computed(() =>
  aggregate(monitorData.value?.flows).sort((a, b) => b.conns - a.conns || b.bytes - a.bytes)
)
const auditBytes = computed(() => auditRows.value.reduce((sum, a) => sum + a.bytes, 0))
const auditConns = computed(() => auditRows.value.reduce((sum, a) => sum + a.conns, 0))
/** Bars are still normalized by bytes, using the max bytes across all destinations */
const auditPeakBytes = computed(() => auditRows.value.reduce((max, a) => Math.max(max, a.bytes), 0))

/** Rank colors: each row gets a distinct color */
const RANK_COLORS = ['#0a84ff', '#30d158', '#ff9f0a', '#bf5af2', '#ff453a',
  '#64d2ff', '#ffd60a', '#5e5ce6', '#ff375f', '#30b0c7']
function rankColor(i) { return RANK_COLORS[i % RANK_COLORS.length] }

/** Bar length normalized by the max bytes in this list; share computed against this list's total bytes */
function barWidth(bytes, peak) {
  return peak ? `${Math.max(2, (bytes / peak) * 100)}%` : '0%'
}
function shareOf(bytes, total) {
  return total ? `${((bytes / total) * 100).toFixed(1)}%` : '0%'
}

/** Detail page data: filter by IP / port */
const detailFlows = computed(() => {
  const ip = detailIp.value.trim().toLowerCase()
  const port = detailPort.value.trim()
  return (detailData.value?.flows || [])
    .filter((f) => (!ip || String(f.destination || '').toLowerCase().includes(ip))
      && (!port || String(f.port ?? '').includes(port)))
    .slice(0, flowLimit.value)
})

const detailAuditRows = computed(() => {
  const ip = detailIp.value.trim().toLowerCase()
  const port = detailPort.value.trim()
  return aggregate(detailData.value?.flows)
    .filter((a) => (!ip || a.destination.toLowerCase().includes(ip))
      && (!port || String(a.port ?? '').includes(port)))
    .slice(0, 2000)
})
const detailAuditBytes = computed(() => detailAuditRows.value.reduce((sum, a) => sum + a.bytes, 0))

const detailTitle = computed(() => (detail.value?.mode === 'audit' ? 'Traffic Audit Detail' : 'Active Connections Detail'))
const detailDevice = computed(() => detailData.value?.device?.client || detail.value?.key || '')

function toggleMenu(key) {
  openMenu.value = openMenu.value === key ? '' : key
  openDD.value = ''
}

function closeMenu() {
  openMenu.value = ''
  openDD.value = ''
}

function openTab(name, sub) {
  tab.value = name
  if (detail.value) {
    detail.value = null
    detailData.value = null
  }
  if (name !== 'sessions') closeMonitor()
  if (sub && CONFIG_TABS.includes(sub)) configTab.value = sub
  if (sub && SESSION_TABS.includes(sub)) sessionTab.value = sub
  const path = name === 'config'
    ? `#/config/${configTab.value}`
    : name === 'sessions'
      ? `#/sessions/${sessionTab.value}`
      : `#/${name}`
  if (location.hash !== path) history.replaceState(null, '', path)
  if (name === 'sessions') {
    loadSessions().then(() => {
      // Run a full speed test the first time the connections page opens, then every 15 minutes
      if (!lastTestAt.value) speedTestAllDevices()
    })
  }
}

function openConfig(sub) {
  if (configTab.value === sub && tab.value === 'config') return
  openTab('config', sub)
}

function openSession(sub) {
  if (sessionTab.value === sub && tab.value === 'sessions') return
  closeMonitor()
  openTab('sessions', sub)
}

function syncFromHash() {
  const s = parseHash()
  tab.value = s.tab
  configTab.value = s.configTab
  sessionTab.value = s.sessionTab
  detail.value = s.detail
  if (tab.value === 'sessions') loadSessions()
  if (tab.value === 'detail') {
    detailData.value = null
    loadDetail()
  }
}

function sinceSec(epoch) {
  const t = typeof epoch === 'string' && isNaN(Number(epoch))
    ? Date.parse(epoch) / 1000
    : Number(epoch)
  if (!t || isNaN(t)) return '—'
  const s = Math.max(0, Math.floor(Date.now() / 1000 - t))
  if (s < 60) return `${s}s`
  if (s < 3600) return `${Math.floor(s / 60)}m`
  return `${Math.floor(s / 3600)}h ${Math.floor((s % 3600) / 60)}m`
}

function formatRate(n) {
  const v = Number(n) || 0
  if (v < 1024) return `${Math.round(v)} B/s`
  if (v < 1024 * 1024) return `${(v / 1024).toFixed(1)} KB/s`
  return `${(v / 1024 / 1024).toFixed(1)} MB/s`
}

function formatBytes(n) {
  const v = Number(n) || 0
  if (v < 1024) return `${v} B`
  if (v < 1024 * 1024) return `${(v / 1024).toFixed(1)} KB`
  return `${(v / 1024 / 1024).toFixed(1)} MB`
}

async function doLogout() {
  try { await api.logout() } catch {}
  try { localStorage.removeItem(MONITOR_STORE) } catch {}
  emit('logout')
}

onMounted(async () => {
  window.addEventListener('hashchange', syncFromHash)
  document.addEventListener('click', closeMenu)
  if (tab.value === 'sessions') restoreMonitor()
  await load()
  if (tab.value === 'sessions') {
    loadSessions()
    if (monitor.value) loadMonitor()
  }
  if (tab.value === 'detail') loadDetail()
  timer = setInterval(async () => {
    try {
      data.value = await api.overview(logLines.value)
      if (tab.value === 'sessions') {
        await loadSessions()
        if (monitor.value) await loadMonitor()
      } else if (tab.value === 'detail') {
        await loadDetail()
      }
    } catch {}
  }, 5000)
  // Auto speed test: every 15 minutes, only on the connections page
  autoTestTimer = setInterval(async () => {
    if (tab.value !== 'sessions') return
    await loadSessions()
    await speedTestAllDevices()
  }, 15 * 60 * 1000)
})

onBeforeUnmount(() => {
  clearInterval(timer)
  clearInterval(autoTestTimer)
  window.removeEventListener('hashchange', syncFromHash)
  document.removeEventListener('click', closeMenu)
})
</script>

<template>
  <div class="page">
    <header class="top">
      <div class="brand">
        <div class="logo">G</div>
        <div>
          <h1>GPN</h1>
        </div>
      </div>
      <div class="top-actions">
        <span class="badge" :class="statusClass">{{ statusText }}</span>
        <button class="chip" @click="toggle">{{ mode === 'dark' ? 'Light' : 'Dark' }}</button>
        <span class="user">{{ props.username }}</span>
        <button class="chip" @click="doLogout">Log out</button>
      </div>
    </header>

    <div class="body">
      <aside class="side">
        <div class="side-label">Navigation</div>
        <nav class="nav">
          <button class="nav-item" :class="{ active: tab === 'overview' }" @click="openTab('overview')">
            <svg viewBox="0 0 24 24" aria-hidden="true"><rect x="3" y="3" width="7" height="7" rx="2"/><rect x="14" y="3" width="7" height="7" rx="2"/><rect x="3" y="14" width="7" height="7" rx="2"/><rect x="14" y="14" width="7" height="7" rx="2"/></svg>
            <span>Overview</span>
          </button>
          <button class="nav-item" :class="{ active: tab === 'sessions' }" @click="openTab('sessions')">
            <svg viewBox="0 0 24 24" aria-hidden="true"><path d="M5 12.5a10 10 0 0 1 14 0"/><path d="M8 15.5a6 6 0 0 1 8 0"/><circle cx="12" cy="19" r="1.4"/></svg>
            <span>Connections</span>
            <span v-if="sessions" class="nav-count">{{ (sessions.vlessCount || 0) + (sessions.ikeCount || 0) }}</span>
          </button>
          <button class="nav-item" :class="{ active: tab === 'config' }" @click="openTab('config')">
            <svg viewBox="0 0 24 24" aria-hidden="true"><path d="M4 7h9M17 7h3M4 17h3M11 17h9"/><circle cx="15" cy="7" r="2"/><circle cx="9" cy="17" r="2"/></svg>
            <span>VPN Settings</span>
          </button>
          <button class="nav-item" :class="{ active: tab === 'logs' }" @click="openTab('logs')">
            <svg viewBox="0 0 24 24" aria-hidden="true"><path d="M4 6h16M4 12h16M4 18h10"/></svg>
            <span>Logs</span>
          </button>
        </nav>
      </aside>

      <main class="main">
      <section v-if="tab === 'sessions'" class="sessions-page">
        <header class="page-head sessions-head">
          <div class="chan-tabs" role="tablist" aria-label="Connection type">
            <button
              class="chan"
              role="tab"
              :class="{ active: sessionTab === 'vless' }"
              :aria-selected="sessionTab === 'vless'"
              @click="openSession('vless')"
            >
              <span class="chan-icon">
                <svg viewBox="0 0 24 24" aria-hidden="true"><path d="M4 12a8 8 0 0 1 8-8"/><path d="M4 16a4 4 0 0 1 4-4"/><circle cx="5" cy="19" r="1.5"/><path d="M14 5h6v6"/></svg>
              </span>
              <span class="chan-body">
                <span class="chan-name">VLESS</span>
                <span class="chan-desc">Proxy clients · Encrypted exit</span>
              </span>
              <span class="chan-count">{{ sessions ? (sessions.vlessCount || 0) : '—' }}</span>
            </button>
            <button
              class="chan"
              role="tab"
              :class="{ active: sessionTab === 'ikev2' }"
              :aria-selected="sessionTab === 'ikev2'"
              @click="openSession('ikev2')"
            >
              <span class="chan-icon">
                <svg viewBox="0 0 24 24" aria-hidden="true"><rect x="7" y="2.5" width="10" height="19" rx="2.5"/><path d="M10.5 5.5h3"/><circle cx="12" cy="18" r="1"/></svg>
              </span>
              <span class="chan-body">
                <span class="chan-name">IKEv2</span>
                <span class="chan-desc">System VPN · iPhone / macOS</span>
              </span>
              <span class="chan-count">{{ sessions ? (sessions.ikeCount || 0) : '—' }}</span>
            </button>
          </div>
          <div v-if="!monitor && sessionTab === 'ikev2'" class="head-tools">
            <input
              v-model="searchQuery"
              class="input search"
              type="search"
              placeholder="Device / public / VPN IP"
            />
            <div class="tool-group">
              <span class="tool-label">Status</span>
              <div class="dd">
                <button class="dd-btn" :class="{ active: openDD === 'status' }" @click.stop="toggleDD('status')">
                  <span>{{ statusLabel }}</span>
                  <svg viewBox="0 0 24 24" aria-hidden="true"><path d="M6 9l6 6 6-6"/></svg>
                </button>
                <div v-if="openDD === 'status'" class="dd-menu" @click.stop>
                  <button
                    v-for="o in statusOptions"
                    :key="o.value"
                    class="dd-item"
                    :class="{ active: filterStatus === o.value }"
                    @click="filterStatus = o.value; openDD = ''"
                  >
                    <span>{{ o.label }}</span>
                    <svg v-if="filterStatus === o.value" viewBox="0 0 24 24" aria-hidden="true"><path d="M4 12.5l5 5L20 6.5"/></svg>
                  </button>
                </div>
              </div>
            </div>
            <div class="tool-group">
              <span class="tool-label">Speed</span>
              <div class="dd">
                <button class="dd-btn" :class="{ active: openDD === 'quality' }" @click.stop="toggleDD('quality')">
                  <span>{{ qualityLabel }}</span>
                  <svg viewBox="0 0 24 24" aria-hidden="true"><path d="M6 9l6 6 6-6"/></svg>
                </button>
                <div v-if="openDD === 'quality'" class="dd-menu" @click.stop>
                  <button
                    v-for="o in qualityOptions"
                    :key="o.value"
                    class="dd-item"
                    :class="{ active: filterQuality === o.value }"
                    @click="filterQuality = o.value; openDD = ''"
                  >
                    <span>{{ o.label }}</span>
                    <svg v-if="filterQuality === o.value" viewBox="0 0 24 24" aria-hidden="true"><path d="M4 12.5l5 5L20 6.5"/></svg>
                  </button>
                </div>
              </div>
            </div>
            <button
              class="btn btn-primary btn-test-inline"
              :disabled="testingAll || testingId || !(sessions && sessions.ikev2 && sessions.ikev2.length)"
              @click="speedTestAllDevices"
            >
              {{ testingAll ? 'Testing…' : 'Test All' }}
            </button>
            <span class="test-note muted">Auto every 15 min · {{ lastTestText() }}</span>
          </div>
          <span v-if="!monitor" class="muted">Auto-refresh every 5s</span>
        </header>

        <p v-if="sessions && sessions.error" class="alert alert-error">{{ sessions.error }}</p>

        <div class="sessions-body single">
          <section class="card sessions-main">
            <template v-if="monitor">
              <header class="mon-head">
                <button class="chip back" @click="closeMonitor">← Back</button>
                <h3>{{ monitorData ? monitorData.device.client : monitor.client }}</h3>
                <span
                  v-if="monitorData"
                  class="badge"
                  :class="monitorData.device.online ? 'badge-ok' : 'badge-warn'"
                >{{ monitorData.device.online ? 'Online' : 'Offline' }}</span>
                <span class="muted">Refreshing every 5s</span>
              </header>

              <p v-if="monitorLoading" class="empty muted">Loading monitor data…</p>
              <template v-else-if="monitorData">
                <h4 class="mon-sec">Proxy Route</h4>
                <div class="route">
                  <span class="route-node c1">
                    <svg class="route-ico" viewBox="0 0 24 24" aria-hidden="true"><rect x="3.5" y="4.5" width="17" height="11" rx="2"/><path d="M2 19h20"/></svg>
                    <span class="route-text">
                      <strong>{{ monitorData.route.client }}</strong>
                      <small class="mono">{{ monitorData.device.publicIp || '—' }}<template v-if="monitorData.device.region"> · {{ monitorData.device.region }}</template></small>
                      <small class="mono">
                        {{ monitorData.device.online && monitorData.device.connected ? `Connected ${sinceSec(monitorData.device.connected)}` : 'Disconnected' }}
                        · ↑ {{ formatBytes(monitorData.device.upload) }} · ↓ {{ formatBytes(monitorData.device.download) }}
                      </small>
                    </span>
                  </span>
                  <span class="route-arrow" aria-hidden="true"><i class="route-flow"></i></span>
                  <span class="route-node c2">
                    <svg class="route-ico" viewBox="0 0 24 24" aria-hidden="true"><rect x="7" y="2.5" width="10" height="19" rx="2.5"/><path d="M10.5 5.5h3"/><circle cx="12" cy="18" r="1"/></svg>
                    <span class="route-text">
                      <strong class="mono">{{ monitorData.route.localIp }}</strong><small>Proxy address</small>
                    </span>
                  </span>
                  <span class="route-arrow" aria-hidden="true"><i class="route-flow"></i></span>
                  <span class="route-node c3">
                    <svg class="route-ico" viewBox="0 0 24 24" aria-hidden="true"><rect x="3" y="4" width="18" height="7" rx="2"/><rect x="3" y="13" width="18" height="7" rx="2"/><path d="M7 7.5h.01M7 16.5h.01"/></svg>
                    <span class="route-text">
                      <strong class="mono">{{ monitorData.route.server }}:{{ monitorData.route.serverPort }}</strong>
                    </span>
                  </span>
                  <span class="route-arrow" aria-hidden="true"><i class="route-flow"></i></span>
                  <span class="route-node c4">
                    <svg class="route-ico" viewBox="0 0 24 24" aria-hidden="true"><circle cx="12" cy="12" r="9"/><path d="M3 12h18"/><path d="M12 3a15 15 0 0 1 0 18a15 15 0 0 1 0-18"/></svg>
                    <span class="route-text">
                      <strong>{{ monitorData.route.egress }}</strong>
                    </span>
                  </span>
                </div>

                <h4 class="mon-sec">
                  Active Connections
                  <span class="muted">
                    Active {{ flowRows.length }} · Current {{ monitorData.flows.length }} ·
                    Destinations {{ monitorData.summary.destinations }} · Traffic {{ formatBytes(monitorData.summary.bytes) }}
                  </span>
                  <button class="chip detail-btn" @click="openDetail('flows')">Details ↗</button>
                </h4>
                <p v-if="!flowRows.length" class="empty muted">No active connections in the last 10 seconds.</p>
                <div v-else class="flow-scroll">
                  <table class="tbl mon-tbl">
                    <thead><tr>
                      <th>Destination</th><th>Port</th><th>Proto</th><th>State</th><th>Live Rate</th><th>Total</th><th>Timeout Left</th>
                    </tr></thead>
                    <tbody>
                      <tr v-for="(f, i) in flowRows" :key="i">
                        <td class="mono">{{ f.destination }}</td>
                        <td class="mono">{{ f.port || '—' }}</td>
                        <td class="mono">{{ f.proto }}</td>
                        <td>{{ f.state || '—' }}</td>
                        <td class="mono">
                          <span class="rate-up">↑ {{ formatRate(f.upRate) }}</span>
                          <span class="rate-sep">·</span>
                          <span class="rate-down">↓ {{ formatRate(f.downRate) }}</span>
                        </td>
                        <td class="mono">{{ formatBytes((f.bytes || 0) + (f.down || 0)) }}</td>
                        <td>{{ f.timeout ? `${f.timeout}s` : '—' }}</td>
                      </tr>
                    </tbody>
                  </table>
                </div>

                <h4 class="mon-sec">
                  Traffic Audit
                  <span class="muted">Stats · {{ auditRows.length }} destinations</span>
                  <button class="chip detail-btn" @click="openDetail('audit')">Details ↗</button>
                </h4>
                <p v-if="!auditRows.length" class="empty muted">No access records yet.</p>
                <template v-else>
                  <div class="audit-stats">
                    <div class="audit-stat">
                      <span class="muted">Active Conns</span>
                      <strong>{{ monitorData.summary.active }}</strong>
                    </div>
                    <div class="audit-stat">
                      <span class="muted">Destinations</span>
                      <strong>{{ auditRows.length }}</strong>
                    </div>
                    <div class="audit-stat">
                      <span class="muted">Total Traffic</span>
                      <strong>{{ formatBytes(auditBytes) }}</strong>
                    </div>
                    <div class="audit-stat">
                      <span class="muted">Top Destination</span>
                      <strong class="mono ellipsis" :title="auditRows[0].destination">{{ auditRows[0].destination }}</strong>
                      <small>{{ auditRows[0].conns }}x · {{ shareOf(auditRows[0].conns, auditConns) }} · {{ formatBytes(auditRows[0].bytes) }}</small>
                    </div>
                  </div>

                  <div class="audit-top">
                    <div class="audit-top-row audit-top-head">
                      <span class="audit-rank">#</span>
                      <span>Destination</span>
                      <span class="audit-conns">Count</span>
                      <span class="audit-flow">Traffic</span>
                      <span class="audit-pct">Conn Share</span>
                    </div>
                    <div v-for="(a, i) in auditRows.slice(0, 10)" :key="a.destination" class="audit-top-row">
                      <span class="audit-rank" :style="{ color: rankColor(i) }">{{ i + 1 }}</span>
                      <span class="mono audit-dest" :title="`${a.destination}:${a.port || '—'} · ${a.proto} · ${a.conns} connections`">
                        {{ a.destination }}<span class="muted audit-port">:{{ a.port || '—' }}</span>
                      </span>
                      <span class="audit-conns mono">{{ a.conns }}x</span>
                      <span class="audit-flow">
                        <span class="audit-bar"><span :style="{ width: barWidth(a.bytes, auditPeakBytes), background: rankColor(i) }"></span></span>
                        <span class="mono audit-val">{{ formatBytes(a.bytes) }}</span>
                      </span>
                      <span class="muted audit-pct">{{ shareOf(a.conns, auditConns) }}</span>
                    </div>
                  </div>
                  <p v-if="auditRows.length > 10" class="hint">Showing top 10 by count; {{ auditRows.length - 10 }} more destinations not listed.</p>
                </template>
              </template>
              <p class="hint">Destinations come from the server kernel's connection tracking (conntrack) of assigned VPN IPs, updated about every 3 seconds; entries disappear when connections close.</p>
            </template>

            <template v-else-if="sessionTab === 'vless'">
              <p v-if="!sessions || !sessions.vless || !sessions.vless.length" class="empty muted">No active VLESS connections.</p>
              <table v-else class="tbl">
                <thead><tr>
                  <th>Client</th><th>Status</th><th>Source</th><th>Destination</th>
                  <th>Duration</th><th>Rate</th><th>Total Up / Down</th><th class="th-act"></th>
                </tr></thead>
                <tbody>
                  <tr v-for="(s, i) in sessions.vless" :key="i">
                    <td>{{ s.client }}</td>
                    <td><span class="online"><span class="dot"></span>Online</span></td>
                    <td class="mono">{{ s.source }}</td>
                    <td class="mono ellipsis">{{ s.destination }}</td>
                    <td>{{ sinceSec(s.startedAt) }}</td>
                    <td>
                      <div class="rate"><span class="muted">—</span></div>
                    </td>
                    <td class="mono">{{ formatBytes(s.upload) }} / {{ formatBytes(s.download) }}</td>
                    <td class="th-act">
                      <div class="menu-anchor">
                        <button
                          class="gear"
                          :class="{ active: openMenu === `vless-${i}` }"
                          aria-label="Device actions"
                          @click.stop="toggleMenu(`vless-${i}`)"
                        >
                          <svg viewBox="0 0 24 24" aria-hidden="true">
                            <circle cx="12" cy="12" r="3.2"/>
                            <path d="M19.4 15a1.7 1.7 0 0 0 .3 1.9l.1.1a2 2 0 1 1-2.8 2.8l-.1-.1a1.7 1.7 0 0 0-1.9-.3 1.7 1.7 0 0 0-1 1.5V21a2 2 0 1 1-4 0v-.1a1.7 1.7 0 0 0-1.1-1.5 1.7 1.7 0 0 0-1.9.3l-.1.1a2 2 0 1 1-2.8-2.8l.1-.1a1.7 1.7 0 0 0 .3-1.9 1.7 1.7 0 0 0-1.5-1H3a2 2 0 1 1 0-4h.1A1.7 1.7 0 0 0 4.6 8.9a1.7 1.7 0 0 0-.3-1.9l-.1-.1a2 2 0 1 1 2.8-2.8l.1.1a1.7 1.7 0 0 0 1.9.3H9a1.7 1.7 0 0 0 1-1.5V3a2 2 0 1 1 4 0v.1a1.7 1.7 0 0 0 1 1.5 1.7 1.7 0 0 0 1.9-.3l.1-.1a2 2 0 1 1 2.8 2.8l-.1.1a1.7 1.7 0 0 0-.3 1.9V9a1.7 1.7 0 0 0 1.5 1H21a2 2 0 1 1 0 4h-.1a1.7 1.7 0 0 0-1.5 1z"/>
                          </svg>
                        </button>
                        <div v-if="openMenu === `vless-${i}`" class="menu" @click.stop>
                          <button class="menu-item" @click="openMonitor('vless', s.client, s.client)">Network Status</button>
                        </div>
                      </div>
                    </td>
                  </tr>
                </tbody>
              </table>
              <p class="hint">Client names come from panel credentials; VLESS sessions are matched by UUID, so IPv6 or multiplexed connections appear as multiple rows.</p>
            </template>

            <template v-else>
              <template v-if="filterStatus !== 'banned'">
                <p v-if="!ikeRows.length" class="empty muted">
                  {{ sessions && sessions.ikev2 && sessions.ikev2.length ? 'No devices match the current filters.' : 'No devices connected via IKEv2.' }}
                </p>
                <table v-else class="tbl">
                  <thead><tr>
                    <th>Device</th><th>Status</th><th>Public IP</th><th>Region</th><th>VPN IP</th>
                    <th>Connected</th><th>Rate</th><th class="th-act"></th>
                  </tr></thead>
                  <tbody>
                    <tr v-for="(s, i) in ikeRows" :key="i" :class="{ 'row-offline': !s.online }">
                      <td>{{ s.client }}</td>
                      <td>
                        <span v-if="s.online" class="online"><span class="dot"></span>Online</span>
                        <span v-else class="offline"><span class="dot"></span>Offline</span>
                      </td>
                      <td class="mono">{{ s.remoteHost }}</td>
                      <td>{{ s.region || '—' }}</td>
                      <td class="mono">{{ s.vpnIp || '—' }}</td>
                      <td>
                        <template v-if="s.online">{{ sinceSec(s.established) }}</template>
                        <span v-else class="muted">Offline {{ sinceSec(s.lastSeen) }}</span>
                      </td>
                      <td>
                        <div v-if="!s.online" class="rate"><span class="muted">—</span></div>
                        <div v-else class="rate">
                          <template v-if="testingAll || testingId === `ike-${s.id}`">
                            <span class="muted">Testing…</span>
                          </template>
                          <template v-else-if="speedResults[`ike-${s.id}`]">
                            <span class="quality" :class="qualityOfSpeed(speedResults[`ike-${s.id}`].total).cls">
                              {{ qualityOfSpeed(speedResults[`ike-${s.id}`].total).label }}
                            </span>
                            <span class="rate-num mono">{{ speedText(speedResults[`ike-${s.id}`].total) }}</span>
                          </template>
                          <span v-else class="muted">Untested</span>
                        </div>
                      </td>
                      <td class="th-act">
                        <div class="menu-anchor">
                          <button
                            class="gear"
                            :class="{ active: openMenu === `ike-${s.id}` }"
                            aria-label="Device actions"
                            @click.stop="toggleMenu(`ike-${s.id}`)"
                          >
                            <svg viewBox="0 0 24 24" aria-hidden="true">
                              <circle cx="12" cy="12" r="3.2"/>
                              <path d="M19.4 15a1.7 1.7 0 0 0 .3 1.9l.1.1a2 2 0 1 1-2.8 2.8l-.1-.1a1.7 1.7 0 0 0-1.9-.3 1.7 1.7 0 0 0-1 1.5V21a2 2 0 1 1-4 0v-.1a1.7 1.7 0 0 0-1.1-1.5 1.7 1.7 0 0 0-1.9.3l-.1.1a2 2 0 1 1-2.8-2.8l.1-.1a1.7 1.7 0 0 0 .3-1.9 1.7 1.7 0 0 0-1.5-1H3a2 2 0 1 1 0-4h.1A1.7 1.7 0 0 0 4.6 8.9a1.7 1.7 0 0 0-.3-1.9l-.1-.1a2 2 0 1 1 2.8-2.8l.1.1a1.7 1.7 0 0 0 1.9.3H9a1.7 1.7 0 0 0 1-1.5V3a2 2 0 1 1 4 0v.1a1.7 1.7 0 0 0 1 1.5 1.7 1.7 0 0 0 1.9-.3l.1-.1a2 2 0 1 1 2.8 2.8l-.1.1a1.7 1.7 0 0 0-.3 1.9V9a1.7 1.7 0 0 0 1.5 1H21a2 2 0 1 1 0 4h-.1a1.7 1.7 0 0 0-1.5 1z"/>
                            </svg>
                          </button>
                          <div v-if="openMenu === `ike-${s.id}`" class="menu" @click.stop>
                            <button class="menu-item" @click="openMonitor('ikev2', s.vpnIp, s.client)">Network Status</button>
                            <button class="menu-item" :disabled="!s.online || !!testingId || testingAll" @click="closeMenu(); speedTest(s)">Speed Test</button>
                            <button class="menu-item" :disabled="!s.online || busyId === `ike-${s.id}`" @click="closeMenu(); kickSession(s)">Kick</button>
                            <button
                              class="menu-item danger"
                              :disabled="busyId === `ike-${s.id}` || !clientOfSession(s)"
                              @click="closeMenu(); banSession(s)"
                            >Ban Device</button>
                          </div>
                        </div>
                      </td>
                    </tr>
                  </tbody>
                </table>
              </template>

              <div v-if="filterStatus !== 'online' && filterStatus !== 'offline' && bannedRows.length" class="banned" :class="{ 'banned-only': filterStatus === 'banned' }">
                <h3 class="banned-title">Banned Devices</h3>
                <ul class="client-list">
                  <li v-for="c in bannedRows" :key="c.id" class="client">
                    <div class="client-main">
                      <div class="client-title">
                        <span class="name">{{ c.name }}</span>
                        <span class="badge badge-danger">Banned</span>
                      </div>
                      <div class="client-meta mono">IKEv2 user {{ c.ikeUser }}</div>
                    </div>
                    <div class="client-actions">
                      <button class="chip" :disabled="busyId === c.id" @click="unbanDevice(c)">Unban</button>
                    </div>
                  </li>
                </ul>
              </div>

              <p v-if="filterStatus !== 'banned'" class="hint">Kick only drops the current session and the device will reconnect automatically; ban revokes the credentials and disconnects — use "Unban" below to restore access. Offline records are kept for 7 days for reviewing connection history.</p>
            </template>
          </section>
        </div>
      </section>

      <section v-else-if="tab === 'config'" class="config-page">
        <header class="page-head">
          <div class="chan-tabs" role="tablist" aria-label="Config category">
            <button
              class="chan"
              role="tab"
              :class="{ active: configTab === 'server' }"
              :aria-selected="configTab === 'server'"
              @click="openConfig('server')"
            >
              <span class="chan-icon">
                <svg viewBox="0 0 24 24" aria-hidden="true"><path d="M6 3.5h8.5L19 8v11.5a1 1 0 0 1-1 1H6a1 1 0 0 1-1-1v-15a1 1 0 0 1 1-1z"/><path d="M14.5 3.5V8H19"/><path d="M8.5 13h7M8.5 16.5h4.5"/></svg>
              </span>
              <span class="chan-body">
                <span class="chan-name">Getting Started</span>
                <span class="chan-desc">Server params · Setup steps</span>
              </span>
            </button>
            <button
              class="chan"
              role="tab"
              :class="{ active: configTab === 'ikev2' }"
              :aria-selected="configTab === 'ikev2'"
              @click="openConfig('ikev2')"
            >
              <span class="chan-icon">
                <svg viewBox="0 0 24 24" aria-hidden="true"><path d="M12 3l7 2.8v6.4c0 4.4-2.9 7.4-7 8.8-4.1-1.4-7-4.4-7-8.8V5.8L12 3z"/><path d="M9 12l2 2 4-4"/></svg>
              </span>
              <span class="chan-body">
                <span class="chan-name">IKEv2 Setup</span>
                <span class="chan-desc">System VPN · Profiles</span>
              </span>
            </button>
            <button
              class="chan"
              role="tab"
              :class="{ active: configTab === 'vless' }"
              :aria-selected="configTab === 'vless'"
              @click="openConfig('vless')"
            >
              <span class="chan-icon">
                <svg viewBox="0 0 24 24" aria-hidden="true"><path d="M10.5 13.5a4.5 4.5 0 0 0 6.4 0l2.1-2.1a4.5 4.5 0 0 0-6.4-6.4l-1.2 1.2"/><path d="M13.5 10.5a4.5 4.5 0 0 0-6.4 0l-2.1 2.1a4.5 4.5 0 0 0 6.4 6.4l1.2-1.2"/></svg>
              </span>
              <span class="chan-body">
                <span class="chan-name">VLESS Setup</span>
                <span class="chan-desc">Proxy clients · Links & subscription</span>
              </span>
            </button>
          </div>
        </header>

        <p v-if="error" class="alert alert-error">{{ error }}</p>

        <div v-if="configTab === 'server'" class="cols">
          <section class="card">
            <header class="card-head">
              <h2>Server Connection Parameters</h2>
              <button class="chip" @click="copyParams">Copy All</button>
            </header>
            <dl class="kv">
              <div><dt>Server Address</dt><dd class="mono">{{ server.host }}</dd></div>
              <div><dt>Port</dt><dd class="mono">{{ server.port }}</dd></div>
              <div><dt>Protocol</dt><dd>{{ server.protocol }}</dd></div>
              <div><dt>SNI / Camouflage Domain</dt><dd class="mono">{{ server.sni }}</dd></div>
              <div><dt>Public Key</dt><dd class="mono ellipsis">{{ server.publicKey }}</dd></div>
              <div><dt>Short ID</dt><dd class="mono">{{ server.shortId }}</dd></div>
              <div><dt>Fingerprint</dt><dd class="mono">{{ server.fingerprint }}</dd></div>
            </dl>
            <p v-if="hostPlaceholder" class="hint warn">
              Public IP could not be detected automatically; set PUBLIC_HOST in .env and restart the api.
            </p>
          </section>

          <section class="card">
            <header class="card-head"><h2>How to Connect</h2></header>
            <ol class="steps">
              <li>Add a client under "VLESS Setup" to get its dedicated credentials.</li>
              <li>Copy the client's <b>VLESS link</b> or <b>subscription URL</b>.</li>
              <li>Import into a client: sing-box / v2rayN / Shadowrocket / NekoBox all recognize links or subscriptions directly.</li>
              <li>For iPhone system VPN, use the profiles under "IKEv2 Setup" instead.</li>
            </ol>
            <p class="hint">All exit traffic is sent by this server's sing-box; clients need no extra routing config.</p>
          </section>
        </div>

        <template v-else-if="configTab === 'ikev2'">
          <section v-if="ikev2.available" class="card">
            <header class="card-head">
              <h2>iPhone Native VPN (IKEv2)</h2>
              <a class="chip" :href="ikev2.caPath">Download CA Cert</a>
            </header>
            <ol class="steps">
              <li>On the iPhone, tap "Install Profile" for the device. After Safari downloads it, go to Settings → Downloaded Profile → Install and enter the lock-screen passcode — the VPN and CA certificate are installed together.</li>
              <li>Then go to Settings → General → About → Certificate Trust Settings and enable <b>GPN Zero Trust CA</b>.</li>
              <li>Select <b>GPN VPN</b> under Settings → General → VPN & Device Management → VPN and turn it on.</li>
              <li>Or configure manually: type IKEv2, set both Server and Remote ID to <span class="mono">{{ ikev2.host }}</span>, leave Local ID empty, choose "Username" authentication — credentials are listed below.</li>
            </ol>
            <ul class="client-list ike-list">
              <li v-for="c in clients" :key="c.id" class="client">
                <div class="client-main">
                  <div class="client-title">
                    <span class="name">{{ c.name }}</span>
                    <span class="badge" :class="c.enabled ? 'badge-ok' : 'badge-idle'">{{ c.enabled ? 'Active' : 'Disabled' }}</span>
                  </div>
                  <div class="client-meta mono">Username {{ c.ikeUser }} · Password {{ c.ikePass }}</div>
                </div>
                <div class="client-actions">
                  <a class="chip" :href="c.profilePath">Install Profile</a>
                  <button class="chip" @click="copyIke(c)">Copy Credentials</button>
                </div>
              </li>
            </ul>
            <p class="hint">The profile already includes the CA certificate and credentials — it works right after installation. If manual entry reports "Unable to store VPN configuration", a field is usually missing or the phone has no lock-screen passcode; using the profile is the most reliable option.</p>
          </section>
          <section v-else class="card">
            <p class="empty muted">IKEv2 is not deployed, or the container has not mounted /etc/swanctl.</p>
          </section>
        </template>

        <section v-else class="card">
          <header class="card-head">
            <h2>Client Credentials <span class="count">{{ clients.length }}</span></h2>
            <form class="add-form" @submit.prevent="addClient">
              <input class="input" v-model="newName" maxlength="40" placeholder="e.g. MacBook / iPhone" />
              <button class="btn btn-primary btn-sm" type="submit" :disabled="!newName.trim() || creating">
                {{ creating ? 'Creating…' : 'Add Client' }}
              </button>
            </form>
          </header>

          <p v-if="!clients.length" class="empty muted">No clients yet — add one to get credentials.</p>

          <ul v-else class="client-list">
            <li v-for="c in clients" :key="c.id" class="client">
              <div class="client-main">
                <div class="client-title">
                  <span class="name">{{ c.name }}</span>
                  <span class="badge" :class="c.enabled ? 'badge-ok' : 'badge-idle'">{{ c.enabled ? 'Enabled' : 'Disabled' }}</span>
                </div>
                <div class="client-meta mono">UUID {{ c.uuid.slice(0, 8) }} · Created {{ shortDate(c.createdAt) }}</div>
              </div>
              <div class="client-actions">
                <button class="chip" @click="copyLink(c)">VLESS Link</button>
                <button class="chip" :disabled="busyId === c.id" @click="copyConfig(c)">sing-box Config</button>
                <button class="chip" @click="copySub(c)">Subscription URL</button>
                <button class="chip" :disabled="busyId === c.id" @click="rotateClient(c)">Rotate</button>
                <button class="chip" :disabled="busyId === c.id" @click="toggleClient(c)">{{ c.enabled ? 'Disable' : 'Enable' }}</button>
                <button class="chip danger" :disabled="busyId === c.id" @click="removeClient(c)">Delete</button>
              </div>
            </li>
          </ul>
        </section>
      </section>

      <section v-else-if="tab === 'logs'" class="card logs-page">
        <header class="card-head">
          <h2>Logs</h2>
          <div class="log-tools">
            <span class="muted">Last {{ logLines }} lines · Refreshing every 5s</span>
            <div class="seg" role="group" aria-label="Log lines">
              <button
                v-for="n in logOptions"
                :key="n"
                class="seg-item"
                :class="{ active: logLines === n }"
                @click="setLogLines(n)"
              >{{ n }}</button>
            </div>
          </div>
        </header>
        <p v-if="!proxy.logTail || !proxy.logTail.length" class="empty muted">No log output yet.</p>
        <pre v-else class="log-box">{{ logText }}</pre>
      </section>

      <section v-else-if="tab === 'detail'" class="detail-page">
        <header class="page-head sessions-head detail-head">
          <div class="detail-title-wrap">
            <h2 class="detail-title">{{ detailTitle }}</h2>
            <p class="muted detail-sub">
              {{ detailDevice }} · {{ detail.type === 'ikev2' ? 'IKEv2' : 'VLESS' }} · Refreshing every 5s
            </p>
          </div>
          <span v-if="detailData" class="badge" :class="detailData.device.online ? 'badge-ok' : 'badge-warn'">
            {{ detailData.device.online ? 'Online' : 'Offline' }}
          </span>
          <span class="muted detail-sum">
            {{ detailData ? detailData.flows.length : 0 }} tracked connections ·
            {{ detailData ? detailData.summary.destinations : 0 }} destinations ·
            {{ detailData ? formatBytes(detailData.summary.bytes) : '—' }}
          </span>
        </header>

        <p v-if="detailError" class="alert alert-error">{{ detailError }}</p>

        <div class="detail-tools">
          <input v-model="detailIp" class="input search" type="search" placeholder="Filter by destination IP / domain" />
          <input v-model="detailPort" class="input detail-port" type="search" placeholder="Port, e.g. 443" />
          <div v-if="detail.mode === 'flows'" class="seg" role="group" aria-label="Row limit">
            <button
              v-for="n in DETAIL_FLOW_LIMITS"
              :key="n"
              class="seg-item"
              :class="{ active: flowLimit === n }"
              @click="flowLimit = n"
            >{{ n }}</button>
          </div>
          <span class="muted">
            {{ detail.mode === 'flows'
              ? `${detailFlows.length} matched · limit ${flowLimit}`
              : `${detailAuditRows.length} destinations matched · max 2000` }}
          </span>
        </div>

        <p v-if="detailLoading && !detailData" class="empty muted">Loading…</p>

        <section v-else-if="detailData" class="card detail-card">
          <template v-if="detail.mode === 'flows'">
            <p v-if="!detailFlows.length" class="empty muted">No matching connections.</p>
            <div v-else class="flow-scroll detail-scroll">
              <table class="tbl mon-tbl">
                <thead><tr>
                  <th>Destination</th><th>Port</th><th>Proto</th><th>State</th><th>Traffic</th><th>Timeout Left</th>
                </tr></thead>
                <tbody>
                  <tr v-for="(f, i) in detailFlows" :key="i">
                    <td class="mono">{{ f.destination }}</td>
                    <td class="mono">{{ f.port || '—' }}</td>
                    <td class="mono">{{ f.proto }}</td>
                    <td>{{ f.state || '—' }}</td>
                    <td class="mono">{{ formatBytes((f.bytes || 0) + (f.down || 0)) }}</td>
                    <td>{{ f.timeout ? `${f.timeout}s` : '—' }}</td>
                  </tr>
                </tbody>
              </table>
            </div>
          </template>

          <template v-else>
            <p v-if="!detailAuditRows.length" class="empty muted">No matching destinations.</p>
            <div v-else class="audit-top detail-audit">
              <div class="audit-top-row audit-top-head">
                <span class="audit-rank">#</span>
                <span>Destination</span>
                <span class="audit-conns">Count</span>
                <span>Traffic</span>
                <span class="audit-val">Bytes</span>
                <span class="audit-pct">Share</span>
              </div>
              <div v-for="(a, i) in detailAuditRows" :key="a.destination" class="audit-top-row">
                <span class="audit-rank" :style="{ color: rankColor(i) }">{{ i + 1 }}</span>
                <span class="mono audit-dest" :title="`${a.destination}:${a.port || '—'} · ${a.proto} · ${a.conns} connections`">
                  {{ a.destination }}<span class="muted audit-port">:{{ a.port || '—' }}</span>
                </span>
                <span class="audit-conns mono">{{ a.conns }}x</span>
                <span class="audit-bar"><span :style="{ width: barWidth(a.bytes, detailAuditRows[0].bytes), background: rankColor(i) }"></span></span>
                <span class="mono audit-val">{{ formatBytes(a.bytes) }}</span>
                <span class="muted audit-pct">{{ shareOf(a.bytes, detailAuditBytes) }}</span>
              </div>
            </div>
          </template>
        </section>

        <p class="hint">Data comes from the server's active connection tracking (conntrack / clash_api), refreshed every 5 seconds; entries disappear when connections close.</p>
      </section>

      <template v-else>
        <p v-if="error" class="alert alert-error">{{ error }}</p>
      <p v-if="proxy.error" class="alert alert-warn">{{ proxy.error }}</p>
      <p v-if="loading" class="muted">Loading status…</p>

      <section v-else class="ov">
        <div class="ov-hero">
          <header class="ov-head">
            <div>
              <p class="ov-kicker">Overview</p>
              <h2>Overview</h2>
              <p class="ov-sub">Full chain · Clients → Entry → Exit → Internet</p>
            </div>
            <span class="badge" :class="proxy.running ? 'badge-ok' : 'badge-danger'">
              {{ proxy.running ? 'Exit Running' : 'Exit Stopped' }}
            </span>
          </header>

          <div class="ov-stage">
            <div class="ov-canvas">
              <svg class="ov-mesh" viewBox="0 0 100 100" preserveAspectRatio="none" aria-hidden="true">
                <path v-for="(d, i) in ovSegments" :key="`base-${i}`" :d="d" class="ov-link" />
                <path
                  v-for="(d, i) in ovSegments" :key="`flow-${i}`" :d="d" class="ov-flow"
                  :style="{ animationDelay: `-${(i % 3) * 0.9}s` }"
                />
              </svg>

              <div class="ov-core" :style="{ left: `${OV_CORE.x}%`, top: `${OV_CORE.y}%` }">
                <span class="ov-core-orb" aria-hidden="true"><i></i></span>
                <span class="ov-core-text">
                  <strong>Exit Node</strong>
                  <small>{{ proxy.running ? formatUptime(proxy.uptimeMs) : 'Stopped' }}</small>
                </span>
              </div>

              <div
                v-for="n in ovNodes"
                :key="n.key"
                class="ov-node"
                :style="{ left: `${n.x}%`, top: `${n.y}%` }"
              >
                <span class="ov-orb" aria-hidden="true"><i></i></span>
                <span class="ov-node-text">
                  <strong>{{ n.label }}</strong>
                  <small>{{ n.sub }}</small>
                </span>
              </div>
            </div>

            <aside class="ov-panel">
              <header class="ov-panel-head">
                <span>Insights</span>
                <span class="ov-panel-tag">Live</span>
              </header>
              <div v-for="item in ovAnalysis" :key="item.title" class="ov-item" :class="item.tone">
                <div class="ov-item-head">
                  <i class="ov-item-mark" aria-hidden="true"></i>
                  <strong>{{ item.title }}</strong>
                  <span class="ov-item-tag">{{ item.tag }}</span>
                </div>
                <p>{{ item.detail }}</p>
              </div>
            </aside>
          </div>

          <div class="ov-dock">
            <div v-for="m in ovMetrics" :key="m.label" class="ov-metric">
              <span class="ov-metric-label">{{ m.label }}</span>
              <strong>{{ m.value }}</strong>
              <span v-if="m.pct !== null" class="ov-mini" aria-hidden="true">
                <i :style="{ width: `${m.pct}%` }"></i>
              </span>
            </div>
            <div class="ov-dock-actions">
              <button class="btn btn-sm" :disabled="busyId === 'proxy'" @click="restart">Restart Exit</button>
              <button class="btn btn-sm btn-primary" @click="openTab('sessions')">Connections</button>
            </div>
          </div>
        </div>
      </section>
      </template>
      </main>
    </div>

    <transition name="fade">
      <div v-if="toast" class="toast">{{ toast }}</div>
    </transition>
  </div>
</template>

<style scoped>
.page { min-height: 100dvh; display: flex; flex-direction: column; }
.top {
  display: flex; align-items: center; justify-content: space-between;
  gap: var(--space-4); padding: var(--space-4) var(--space-6);
  border-bottom: 1px solid var(--color-border-muted);
}
.brand { display: flex; align-items: center; gap: var(--space-3); }
.logo {
  width: 36px; height: 36px; border-radius: var(--radius-md);
  display: grid; place-items: center; flex-shrink: 0;
  background: var(--color-text); color: var(--color-surface);
  font-size: 18px; font-weight: 800;
}
h1 { margin: 0; font-size: 15px; font-weight: 600; letter-spacing: -0.2px; }
.brand p { margin: 2px 0 0; font-size: 11px; }
.top-actions { display: flex; align-items: center; gap: var(--space-2); }
.user { font-size: 12px; color: var(--color-text-muted); padding: 0 var(--space-1); }

.body { flex: 1; display: flex; align-items: stretch; min-height: 0; }
.side {
  width: 204px; flex-shrink: 0;
  padding: var(--space-5) var(--space-3);
  background: var(--color-surface-sub);
}
.side-label {
  font-size: 10px; font-weight: 600; letter-spacing: 0.1em;
  text-transform: uppercase; color: var(--color-text-subtle);
  padding: 0 12px var(--space-2);
}
.nav {
  display: flex; flex-direction: column; gap: 2px;
  position: sticky; top: var(--space-5);
}
.nav-item {
  appearance: none; background: none; border: 0; cursor: pointer;
  padding: 9px 11px; border-radius: var(--radius-sm);
  font: inherit; font-size: 13px; color: var(--color-text-muted);
  display: flex; align-items: center; gap: 10px;
  text-align: left; width: 100%;
  transition: background 0.15s ease, color 0.15s ease;
}
.nav-item svg {
  width: 16px; height: 16px; flex-shrink: 0;
  fill: none; stroke: currentColor; stroke-width: 1.7;
  stroke-linecap: round; stroke-linejoin: round;
  opacity: 0.7;
}
.nav-item:hover { color: var(--color-text); background: var(--color-surface); }
.nav-item:hover svg { opacity: 1; }
.nav-item.active {
  color: var(--color-accent); font-weight: 600;
  background: var(--color-surface);
  box-shadow: var(--shadow-xs);
}
.nav-item.active svg { opacity: 1; }
.nav-count {
  margin-left: auto;
  font-size: 11px; line-height: 1; padding: 3px 7px; border-radius: 999px;
  font-variant-numeric: tabular-nums;
  background: var(--color-accent-sub); color: var(--color-accent);
}

@media (max-width: 720px) {
  .body { flex-direction: column; }
  .side {
    width: auto; border-right: 0; border-bottom: 1px solid var(--color-border-muted);
    padding: var(--space-2) var(--space-4);
    background: transparent;
  }
  .side-label { display: none; }
  .nav { flex-direction: row; position: static; gap: var(--space-1); }
  .nav-item { width: auto; }
  .nav-item.active { box-shadow: none; }
}
.tbl { width: 100%; border-collapse: collapse; font-size: 12px; }
.tbl th, .tbl td {
  text-align: left; padding: 8px 10px;
  border-bottom: 1px solid var(--color-border-muted);
}
.tbl th { color: var(--color-text-muted); font-weight: 500; font-size: 11px; }
.tbl td.ellipsis { max-width: 260px; overflow: hidden; text-overflow: ellipsis; white-space: nowrap; }
.tbl .th-act { width: 44px; text-align: right; }
.online {
  display: inline-flex; align-items: center; gap: 6px;
  font-size: 12px; color: var(--color-text);
}
.online .dot {
  width: 7px; height: 7px; border-radius: 50%;
  background: var(--color-success);
  box-shadow: 0 0 0 3px var(--color-success-sub);
}
.offline {
  display: inline-flex; align-items: center; gap: 6px;
  font-size: 12px; color: var(--color-text-muted);
}
.offline .dot {
  width: 7px; height: 7px; border-radius: 50%;
  background: var(--color-text-subtle);
  box-shadow: 0 0 0 3px var(--color-surface-sub);
}
tr.row-offline td { color: var(--color-text-muted); }
.rate { display: flex; align-items: center; gap: 8px; flex-wrap: wrap; }
.rate-num { font-size: 11px; color: var(--color-text-muted); white-space: nowrap; }
.quality {
  padding: 1px 8px; border-radius: var(--radius-pill);
  font-size: 10px; font-weight: 600; line-height: 1.6;
}
.q-fast { background: var(--color-success-sub); color: var(--color-success); }
.q-good { background: var(--color-accent-sub); color: var(--color-accent); }
.q-fair { background: rgba(127,127,127,0.12); color: var(--color-text-muted); }
.q-poor { background: var(--color-warning-sub); color: var(--color-warning); }
.q-bad  { background: var(--color-danger-sub); color: var(--color-danger); }

.menu-anchor { position: relative; display: inline-flex; justify-content: flex-end; }
.gear {
  appearance: none; cursor: pointer; font: inherit;
  width: 28px; height: 28px; padding: 0;
  display: grid; place-items: center;
  border: 0; border-radius: var(--radius-xs);
  background: transparent; color: var(--color-text-subtle);
  transition: background .15s ease, color .15s ease, transform .3s ease;
}
.gear svg {
  width: 15px; height: 15px;
  fill: none; stroke: currentColor; stroke-width: 1.6;
  stroke-linecap: round; stroke-linejoin: round;
}
.gear:hover { background: var(--color-surface-sub); color: var(--color-text); }
.gear.active { background: var(--color-surface-sub); color: var(--color-accent); transform: rotate(45deg); }
.menu {
  position: absolute; top: calc(100% + 4px); right: 0; z-index: 20;
  min-width: 116px; padding: 4px;
  display: flex; flex-direction: column;
  background: var(--color-surface);
  border: 1px solid var(--color-border-muted);
  border-radius: var(--radius-sm);
  box-shadow: var(--shadow-md);
}
.menu-item {
  appearance: none; border: 0; background: none; cursor: pointer;
  font: inherit; font-size: 12px; text-align: left;
  padding: 7px 10px; border-radius: var(--radius-xs);
  color: var(--color-text);
}
.menu-item:hover { background: var(--color-surface-sub); }
.menu-item.danger { color: var(--color-danger); }
.menu-item.danger:hover { background: var(--color-danger-sub); }
.menu-item:disabled { opacity: .45; cursor: not-allowed; }
.sessions-head {
  display: flex; align-items: center; flex-wrap: wrap;
  gap: var(--space-3);
}
.sessions-head > .muted { margin-left: auto; flex-shrink: 0; font-size: 11px; }
.head-tools {
  display: flex; align-items: center; flex-wrap: wrap;
  gap: 10px; margin-left: auto;
}
.tool-group { display: flex; align-items: center; gap: 6px; }
.tool-label {
  font-size: 10px; font-weight: 600; letter-spacing: .5px;
  text-transform: uppercase; color: var(--color-text-subtle);
  white-space: nowrap;
}
.search { width: 150px; font-size: 11px; padding: 5px 8px; }
.dd { position: relative; }
.dd-btn {
  appearance: none; cursor: pointer; font: inherit; font-size: 11px;
  display: inline-flex; align-items: center; gap: 6px;
  padding: 5px 8px 5px 10px;
  border: 0; border-radius: var(--radius-pill);
  background: var(--color-surface-sub); color: var(--color-text);
  transition: background .15s ease, color .15s ease;
}
.dd-btn svg {
  width: 11px; height: 11px; flex-shrink: 0;
  fill: none; stroke: currentColor; stroke-width: 2;
  stroke-linecap: round; stroke-linejoin: round;
  color: var(--color-text-subtle);
  transition: transform .18s ease;
}
.dd-btn:hover { background: var(--color-surface-sub); }
.dd-btn.active { background: var(--color-accent-sub); color: var(--color-accent); }
.dd-btn.active svg { color: var(--color-accent); transform: rotate(180deg); }
.dd-menu {
  position: absolute; top: calc(100% + 6px); left: 0; z-index: 40;
  min-width: 132px; padding: 4px;
  display: flex; flex-direction: column;
  background: var(--color-surface);
  border: 1px solid var(--color-border-muted);
  border-radius: var(--radius-md);
  box-shadow: var(--shadow-md);
}
.dd-item {
  appearance: none; border: 0; background: none; cursor: pointer;
  font: inherit; font-size: 11px; text-align: left;
  display: flex; align-items: center; justify-content: space-between; gap: 10px;
  padding: 6px 8px; border-radius: var(--radius-xs);
  color: var(--color-text);
  white-space: nowrap;
}
.dd-item:hover { background: var(--color-surface-sub); }
.dd-item.active { color: var(--color-accent); font-weight: 600; }
.dd-item svg {
  width: 12px; height: 12px;
  fill: none; stroke: currentColor; stroke-width: 2.2;
  stroke-linecap: round; stroke-linejoin: round;
}
.btn-test-inline { padding: 5px 12px; font-size: 11px; white-space: nowrap; }
.test-note { font-size: 10px; white-space: nowrap; }
.sessions-main { min-width: 0; }
.banned-only { margin-top: 0; }

@media (max-width: 1100px) {
  .head-tools { margin-left: 0; width: 100%; }
  .sessions-head > .muted { margin-left: 0; }
}

.banned { margin-top: var(--space-5); }
.banned-title {
  margin: 0 0 var(--space-2); font-size: 12px; font-weight: 600;
  color: var(--color-text-muted); letter-spacing: .2px;
}

.main {
  flex: 1; width: 100%; max-width: none; margin: 0;
  padding: var(--space-6); display: flex; flex-direction: column; gap: var(--space-4);
}
.config-page, .sessions-page { display: flex; flex-direction: column; gap: var(--space-4); }
.page-head {
  display: flex; align-items: center; justify-content: flex-start;
  gap: var(--space-3); flex-wrap: wrap;
}
.page-head.between { justify-content: space-between; }
/* ---------- Overview: topology view (follows theme variables) ---------- */
.ov { flex: 1; display: flex; min-height: 0; }
.ov-hero {
  flex: 1; display: flex; flex-direction: column; gap: 12px;
  min-height: 420px;
  padding: 0;
  border: 0;
  background: none;
  box-shadow: none;
}

.ov-stage { flex: 1; display: flex; gap: 16px; min-height: 0; }
.ov-canvas { position: relative; flex: 1; min-width: 0; min-height: 340px; }

.ov-mesh { position: absolute; inset: 0; width: 100%; height: 100%; }
.ov-link {
  fill: none; stroke: var(--color-accent); stroke-opacity: .18; stroke-width: 1;
  vector-effect: non-scaling-stroke;
}
.ov-flow {
  fill: none; stroke: var(--color-accent); stroke-width: 2; stroke-linecap: round;
  vector-effect: non-scaling-stroke; stroke-dasharray: .8 7;
  animation: ov-dash 2.6s linear infinite;
}
@keyframes ov-dash { to { stroke-dashoffset: -7.8; } }

.ov-head {
  display: flex; align-items: flex-start; justify-content: space-between; gap: 16px;
}
.ov-kicker {
  margin: 0; font-size: 9px; letter-spacing: 2.4px; text-transform: uppercase;
  color: var(--color-text-subtle);
}
.ov-head h2 {
  margin: 2px 0 0; font-size: 20px; font-weight: 600; letter-spacing: .3px;
  color: var(--color-text);
}
.ov-sub { margin: 3px 0 0; font-size: 11px; color: var(--color-text-muted); }

.ov-core {
  position: absolute; z-index: 2; transform: translate(-50%, -50%);
  display: flex; align-items: center; gap: 13px;
}
.ov-core-orb {
  position: relative; width: 22px; height: 22px; border-radius: 50%;
  background: radial-gradient(circle at 35% 30%, #fff, var(--color-accent) 68%);
  box-shadow: 0 0 14px var(--color-accent-sub), 0 0 0 4px var(--color-accent-sub);
}
.ov-core-orb i {
  position: absolute; inset: -17px; border-radius: 50%;
  border: 1px solid color-mix(in srgb, var(--color-accent) 35%, transparent);
  animation: ov-ring 3.8s ease-out infinite;
}
@keyframes ov-ring {
  0% { transform: scale(.72); opacity: .8; }
  100% { transform: scale(1.15); opacity: 0; }
}
.ov-core-text { display: flex; flex-direction: column; gap: 1px; }
.ov-core-text strong { font-size: 12px; color: var(--color-text); letter-spacing: .2px; }
.ov-core-text small { font-size: 10px; color: var(--color-text-muted); font-variant-numeric: tabular-nums; }

.ov-node {
  position: absolute; z-index: 2; transform: translate(-50%, -50%);
  display: flex; align-items: center; gap: 8px; white-space: nowrap;
}
.ov-orb {
  width: 12px; height: 12px; border-radius: 50%; flex: none;
  display: grid; place-items: center;
  background: var(--color-surface);
  border: 1px solid var(--color-border);
  box-shadow: 0 0 0 3px var(--color-accent-sub);
}
.ov-orb i { width: 5px; height: 5px; border-radius: 50%; background: var(--color-accent); }
.ov-node-text { display: flex; flex-direction: column; gap: 1px; }
.ov-node-text strong { font-size: 11px; font-weight: 600; color: var(--color-text); }
.ov-node-text small { font-size: 9.5px; color: var(--color-text-subtle); font-variant-numeric: tabular-nums; }

.ov-panel {
  flex: none; width: 320px; align-self: stretch; overflow: auto;
  padding: 10px 12px 12px;
  border-radius: var(--radius-md);
  background: var(--glass-bg-strong);
  -webkit-backdrop-filter: var(--glass-blur); backdrop-filter: var(--glass-blur);
  border: 1px solid var(--glass-border);
  box-shadow: var(--shadow-md);
}
.ov-panel-head {
  display: flex; align-items: center; justify-content: space-between;
  font-size: 9.5px; letter-spacing: 1.6px; text-transform: uppercase;
  color: var(--color-text-subtle);
}
.ov-panel-tag {
  padding: 1px 6px; border-radius: 6px; letter-spacing: .6px;
  background: var(--color-accent-sub); color: var(--color-accent);
}
.ov-item {
  position: relative; overflow: hidden; margin-top: 8px;
  padding: 7px 10px 8px; border-radius: var(--radius-sm);
  background: color-mix(in srgb, var(--color-text) 4%, transparent);
  --tint: var(--color-success-sub);
}
.ov-item.i-warn { --tint: var(--color-warning-sub); }
.ov-item.i-bad { --tint: var(--color-danger-sub); }
.ov-item::before {
  content: ''; position: absolute; inset: 0; pointer-events: none;
  background: linear-gradient(90deg, var(--tint), transparent 72%);
}
.ov-item-head { position: relative; display: flex; align-items: center; gap: 6px; }
.ov-item-mark { width: 2px; height: 11px; border-radius: 2px; background: var(--color-success); }
.ov-item.i-warn .ov-item-mark { background: var(--color-warning); }
.ov-item.i-bad .ov-item-mark { background: var(--color-danger); }
.ov-item-head strong { font-size: 11px; font-weight: 500; color: var(--color-text); }
.ov-item-tag {
  margin-left: auto; font-size: 9.5px; color: var(--color-text-subtle);
  font-variant-numeric: tabular-nums;
}
.ov-item p {
  position: relative; margin: 4px 0 0 8px; font-size: 10.5px; line-height: 1.5;
  color: var(--color-text-muted);
}

.ov-dock {
  display: flex; align-items: center; gap: 24px; padding: 9px 14px;
  border-radius: var(--radius-md);
  background: var(--glass-bg-strong);
  -webkit-backdrop-filter: var(--glass-blur); backdrop-filter: var(--glass-blur);
  border: 1px solid var(--glass-border);
  box-shadow: var(--shadow-sm);
}
.ov-metric { display: flex; flex-direction: column; gap: 3px; }
.ov-metric-label {
  font-size: 8.5px; letter-spacing: 1.2px; text-transform: uppercase;
  color: var(--color-text-subtle);
}
.ov-metric strong { font-size: 13px; font-weight: 600; color: var(--color-text); font-variant-numeric: tabular-nums; }
.ov-mini {
  width: 46px; height: 3px; border-radius: 3px; overflow: hidden;
  background: color-mix(in srgb, var(--color-text) 12%, transparent);
}
.ov-mini i {
  display: block; height: 100%; border-radius: 3px;
  background: linear-gradient(90deg, var(--color-accent), color-mix(in srgb, var(--color-accent) 55%, #fff));
}
.ov-dock-actions { margin-left: auto; display: flex; gap: 8px; flex: none; }

@media (max-width: 980px) {
  .ov-hero { min-height: 0; }
  .ov-stage { flex-direction: column; }
  .ov-canvas { min-height: 300px; }
  .ov-panel { width: auto; align-self: auto; }
  .ov-dock { flex-wrap: wrap; gap: 16px; }
}
@media (prefers-reduced-motion: reduce) {
  .ov-flow, .ov-core-orb i { animation: none; }
}

.cols { display: grid; grid-template-columns: 1.15fr 1fr; gap: var(--space-4); }
@media (max-width: 860px) { .cols { grid-template-columns: 1fr; } }

.card { padding: var(--space-4) var(--space-5); }
.card-head {
  display: flex; align-items: center; justify-content: space-between;
  gap: var(--space-3); flex-wrap: wrap; margin-bottom: var(--space-3);
}
.card-head h2 { margin: 0; font-size: 13px; font-weight: 600; }
.count { color: var(--color-text-muted); font-weight: 500; }

.kv { margin: 0; display: flex; flex-direction: column; gap: var(--space-2); }
.kv > div { display: flex; justify-content: space-between; gap: var(--space-4); font-size: 12px; }
.kv dt { color: var(--color-text-muted); flex-shrink: 0; }
.kv dd { margin: 0; text-align: right; }
.mono { font-family: var(--font-mono); font-size: 11px; }
.ellipsis { max-width: 220px; overflow: hidden; text-overflow: ellipsis; white-space: nowrap; }

.steps { margin: 0; padding-left: 18px; display: flex; flex-direction: column; gap: 6px; font-size: 12px; line-height: 1.6; }
.hint { margin: var(--space-3) 0 0; font-size: 11px; color: var(--color-text-subtle); line-height: 1.6; }
.hint.warn { color: var(--color-warning); }
.alert-warn {
  background: var(--color-warning-sub);
  border-color: color-mix(in srgb, var(--color-warning) 30%, transparent);
  color: var(--color-warning);
}

.add-form { display: flex; align-items: center; gap: var(--space-2); }
.add-form .input { width: 200px; padding: 5px 10px; font-size: 12px; }

.empty { margin: var(--space-2) 0 0; font-size: 12px; }
.client-list { list-style: none; margin: 0; padding: 0; display: flex; flex-direction: column; }
.client {
  display: flex; align-items: center; justify-content: space-between;
  gap: var(--space-3); flex-wrap: wrap;
  padding: var(--space-3) 0; border-top: 1px solid var(--color-border-muted);
}
.client:first-child { border-top: none; }
.client-main { display: flex; flex-direction: column; gap: 3px; min-width: 160px; }
.client-title { display: flex; align-items: center; gap: var(--space-2); }
.name { font-size: 13px; font-weight: 500; }
.client-meta { font-size: 10.5px; color: var(--color-text-subtle); }
.client-actions { display: flex; flex-wrap: wrap; gap: 6px; }

.chip {
  padding: 3px 9px; font-size: 11px; line-height: 1.6;
  border: 1px solid var(--color-border-muted); border-radius: var(--radius-pill);
  background: transparent; color: var(--color-text-muted); cursor: pointer;
  transition: color .16s ease, border-color .16s ease, background .16s ease;
}
.chip:hover { color: var(--color-text); border-color: var(--color-border); background: var(--color-surface-sub); }
.chip:disabled { opacity: .45; cursor: default; }
.chip.danger { color: var(--color-danger); border-color: color-mix(in srgb, var(--color-danger) 35%, transparent); }
.chip.danger:hover { background: var(--color-danger-sub); }
a.chip { text-decoration: none; display: inline-block; }
.ike-list { margin-top: var(--space-3); }

.log-tools { display: flex; align-items: center; gap: var(--space-3); flex-wrap: wrap; }
.log-tools .muted { font-size: 11px; }
.seg {
  display: inline-flex; padding: 2px;
  border-radius: var(--radius-pill);
  background: var(--color-surface-sub);
}
.seg-item {
  appearance: none; border: 0; background: none; cursor: pointer;
  font: inherit; font-size: 11px; line-height: 1.6;
  padding: 2px 10px; border-radius: var(--radius-pill);
  color: var(--color-text-muted);
  transition: background .15s ease, color .15s ease;
}
.seg-item:hover { color: var(--color-text); }
.seg-item.active {
  background: var(--color-surface); color: var(--color-accent);
  font-weight: 600; box-shadow: var(--shadow-xs);
}

/* Connection type switch: two channel cards with icons and live counts */
.chan-tabs {
  display: inline-flex; gap: 6px; padding: 5px;
  background: var(--color-surface-sub);
  border-radius: var(--radius-md);
}
.chan {
  appearance: none; cursor: pointer; font: inherit; text-align: left;
  display: flex; align-items: center; gap: 10px;
  padding: 7px 10px 7px 8px;
  border: 1px solid transparent; border-radius: var(--radius-sm);
  background: transparent; color: var(--color-text-muted);
  transition: background .18s ease, color .18s ease,
              box-shadow .18s ease, border-color .18s ease;
}
.chan:hover { background: var(--color-surface); color: var(--color-text); }
.chan-icon {
  width: 30px; height: 30px; flex-shrink: 0;
  display: grid; place-items: center;
  border-radius: var(--radius-xs);
  background: var(--color-surface); color: var(--color-text-subtle);
  transition: background .18s ease, color .18s ease;
}
.chan-icon svg {
  width: 16px; height: 16px;
  fill: none; stroke: currentColor; stroke-width: 1.7;
  stroke-linecap: round; stroke-linejoin: round;
}
.chan-body { display: flex; flex-direction: column; gap: 1px; }
.chan-name { font-size: 12px; font-weight: 600; letter-spacing: -0.1px; }
.chan-desc { font-size: 10px; color: var(--color-text-subtle); letter-spacing: .1px; }
.chan-count {
  min-width: 28px; margin-left: 4px; padding: 3px 8px;
  border-radius: var(--radius-pill);
  background: var(--color-surface); color: var(--color-text-muted);
  font-size: 11px; font-weight: 700; line-height: 1.3;
  font-variant-numeric: tabular-nums; text-align: center;
  transition: background .18s ease, color .18s ease;
}
.chan.active {
  background: var(--color-surface); color: var(--color-text);
  border-color: var(--color-border-muted); box-shadow: var(--shadow-sm);
}
.chan.active .chan-icon { background: var(--color-accent-sub); color: var(--color-accent); }
.chan.active .chan-count { background: var(--color-accent); color: #fff; }

@media (max-width: 720px) {
  .chan-tabs { width: 100%; }
  .chan { flex: 1; }
  .chan-desc { display: none; }
}

.log-box {
  margin: 0; padding: var(--space-4);
  font-family: var(--font-mono); font-size: 11px; line-height: 1.7;
  color: var(--color-text-muted); background: var(--color-surface-sub);
  border-radius: var(--radius-sm); max-height: 62vh; overflow: auto;
  white-space: pre-wrap; word-break: break-all;
}

.toast {
  position: fixed; left: 50%; bottom: 32px; transform: translateX(-50%);
  padding: 7px 16px; border-radius: var(--radius-pill);
  background: var(--color-text); color: var(--color-surface);
  font-size: 12px; z-index: 30;
}
.fade-enter-active, .fade-leave-active { transition: opacity .2s ease, transform .2s ease; }
.fade-enter-from, .fade-leave-to { opacity: 0; transform: translate(-50%, 6px); }

/* ---- Device monitor view ---- */
.mon-head { display: flex; align-items: center; gap: var(--space-3); flex-wrap: nowrap; margin-bottom: var(--space-3); }
.mon-head h3 { margin: 0; font-size: 15px; font-weight: 600; flex: none; }
.mon-head > .muted { margin-left: auto; font-size: 11px; flex: none; }
.mon-head > .badge { flex: none; }
.mon-head .back { font-size: 12px; flex: none; }
@media (max-width: 900px) {
  .mon-head { flex-wrap: wrap; }
  .mon-head > .muted { margin-left: 0; }
}

.mon-sec {
  display: flex; align-items: baseline; gap: var(--space-3); flex-wrap: wrap;
  margin: var(--space-3) 0 4px;
  font-size: 12px; font-weight: 600; letter-spacing: .2px;
}
.mon-sec .muted { font-size: 11px; font-weight: 400; }

/* Proxy route: keep only nodes and connecting lines, no outer background/border */
.route {
  display: flex; align-items: center; gap: 0;
  padding: 0;
  background: none;
  border: 0;
  overflow-x: auto;
}
.route-node {
  --hue: var(--color-accent);
  display: flex; align-items: center; gap: 7px;
  min-height: 34px;
  padding: 3px 11px;
  border-radius: var(--radius-sm);
  background: color-mix(in srgb, var(--hue) 7%, var(--color-surface));
  border: 1px solid color-mix(in srgb, var(--hue) 22%, transparent);
  box-shadow: var(--shadow-xs);
  white-space: nowrap;
}
/* Each of the four chain segments uses its own hue: client blue / proxy purple / server orange / exit green */
.route-node.c1 { --hue: #4f7cff; }
.route-node.c2 { --hue: #8b5cf6; }
.route-node.c3 { --hue: #e8963a; }
.route-node.c4 { --hue: #12b981; }
.route-ico {
  width: 15px; height: 15px; flex: none;
  fill: none; stroke: currentColor; stroke-width: 1.7;
  stroke-linecap: round; stroke-linejoin: round;
  color: color-mix(in srgb, var(--hue) 85%, var(--color-text));
}
.route-text { display: flex; flex-direction: column; gap: 0; line-height: 1.3; }
.route-text strong { font-size: 11px; font-weight: 600; }
.route-text small { font-size: 9px; font-weight: 400; color: var(--color-text-subtle); }
.route-node.c4 {
  background: linear-gradient(135deg,
    color-mix(in srgb, var(--hue) 13%, var(--color-surface)),
    var(--color-surface) 92%);
  box-shadow: 0 2px 12px color-mix(in srgb, var(--hue) 18%, transparent);
  animation: route-glow 2.6s ease-in-out infinite;
}
.route-node.c4 .route-text strong { color: var(--hue); }

/* Connecting line: thin gradient line + arrow + flowing dot */
.route-arrow { position: relative; flex: 0 0 32px; height: 16px; --hue: #4f7cff; }
.route-arrow:nth-of-type(4) { --hue: #8b5cf6; }
.route-arrow:nth-of-type(6) { --hue: #e8963a; }
.route-arrow::before {
  content: ""; position: absolute; left: 3px; right: 8px; top: 50%;
  height: 1px;
  background: linear-gradient(90deg,
    color-mix(in srgb, var(--color-border) 35%, transparent),
    var(--color-border));
}
.route-arrow::after {
  content: ""; position: absolute; right: 7px; top: 50%;
  width: 5px; height: 5px; margin-top: -3px;
  border-top: 1.5px solid var(--color-text-subtle);
  border-right: 1.5px solid var(--color-text-subtle);
  transform: rotate(45deg);
}
.route-flow {
  position: absolute; top: 50%; left: 2px; margin-top: -2.5px;
  width: 5px; height: 5px; border-radius: 50%;
  background: var(--hue);
  box-shadow: 0 0 6px color-mix(in srgb, var(--hue) 85%, transparent),
              0 0 2px var(--hue);
  animation: route-flow 1.6s linear infinite;
}
.route-arrow:nth-of-type(4) .route-flow { animation-delay: .35s; }
.route-arrow:nth-of-type(6) .route-flow { animation-delay: .7s; }
@keyframes route-flow {
  0% { left: 2px; opacity: 0; }
  12% { opacity: 1; }
  88% { opacity: 1; }
  100% { left: calc(100% - 9px); opacity: 0; }
}
@keyframes route-glow {
  0%, 100% { box-shadow: 0 2px 10px color-mix(in srgb, var(--hue) 14%, transparent); }
  50% { box-shadow: 0 2px 20px color-mix(in srgb, var(--hue) 32%, transparent); }
}
@media (prefers-reduced-motion: reduce) {
  .route-flow { animation: none; opacity: .9; left: 50%; margin-left: -2.5px; }
  .route-node.c4 { animation: none; }
}

/* Latest 500 connections: compact rows + fixed-height scrolling with sticky header */
.flow-scroll {
  max-height: 420px; overflow: auto;
  border: 1px solid var(--color-border-muted); border-radius: var(--radius-sm);
}
.flow-scroll .mon-tbl thead th {
  position: sticky; top: 0; z-index: 1;
  background: var(--color-surface);
}
.mon-tbl td, .mon-tbl th {
  font-size: 11px; padding: 4px 8px; line-height: 1.4;
}
.mon-tbl tbody tr:hover td { background: var(--color-surface-sub); }
/* Upload orange, download green for quick distinction */
.rate-up { color: #e8963a; }
.rate-down { color: #12b981; }
.rate-sep { color: var(--color-text-subtle); margin: 0 2px; }

/* Traffic audit: summary stat cards + top destinations bar ranking */
.audit-stats {
  display: flex; flex-wrap: wrap; align-items: center; gap: 4px 18px;
  padding: 5px 12px; margin-bottom: var(--space-3);
  background: var(--color-surface-sub); border-radius: var(--radius-xs);
}
.audit-stat { display: flex; align-items: baseline; gap: 6px; }
.audit-stat .muted { font-size: 10px; letter-spacing: .2px; }
.audit-stat strong { font-size: 13px; font-weight: 600; letter-spacing: -0.1px; }
.audit-stat strong.ellipsis { max-width: 220px; }
.audit-stat small { font-size: 10px; color: var(--color-text-subtle); }

.audit-top { display: flex; flex-direction: column; gap: 4px; }
.audit-top-row {
  display: grid;
  grid-template-columns: 16px minmax(120px, 1.6fr) 54px 2fr 44px;
  align-items: center; gap: 10px;
  font-size: 11px;
}
.audit-top-head {
  color: var(--color-text-subtle);
  font-size: 10px; letter-spacing: .3px;
  padding-bottom: 2px; border-bottom: 1px solid var(--color-border-muted);
}
.audit-conns {
  color: var(--color-text-muted);
  font-variant-numeric: tabular-nums; white-space: nowrap;
}
.audit-flow { display: flex; align-items: center; gap: 8px; min-width: 0; }
.audit-flow .audit-bar { flex: 1 1 auto; min-width: 24px; }
.audit-flow .audit-val { flex: none; }
.audit-rank {
  text-align: right; color: var(--color-text-subtle);
  font-size: 10px; font-weight: 700; font-variant-numeric: tabular-nums;
}
.audit-dest { overflow: hidden; text-overflow: ellipsis; white-space: nowrap; }
.audit-port { font-size: 10px; }
.audit-bar {
  height: 6px; border-radius: var(--radius-pill);
  background: var(--color-surface-sub); overflow: hidden;
}
.audit-bar > span {
  display: block; height: 100%;
  border-radius: inherit; background: var(--color-accent);
}
.audit-val { text-align: right; color: var(--color-text-muted); }
.audit-pct { text-align: right; font-size: 10px; font-variant-numeric: tabular-nums; }

@media (max-width: 720px) {
  .audit-top-row { grid-template-columns: 16px minmax(0, 1fr) 44px 58px 36px; }
  .audit-bar { display: none; }
}

/* Detail page (standalone view opened in a new browser tab) */
.detail-page { display: flex; flex-direction: column; gap: var(--space-4); }
.detail-title-wrap { display: flex; flex-direction: column; gap: 2px; }
.detail-title { margin: 0; font-size: 15px; font-weight: 600; }
.detail-sub { margin: 0; font-size: 11px; }
.detail-head > .muted { margin-left: auto; }
.detail-tools {
  display: flex; align-items: center; gap: var(--space-2); flex-wrap: wrap;
}
.detail-tools .search { width: 220px; }
.detail-port { width: 120px; font-size: 11px; padding: 5px 8px; }
.detail-tools > .muted { font-size: 11px; margin-left: auto; }
.detail-card { padding: var(--space-3) var(--space-4); }
.detail-scroll { max-height: 72vh; }
.detail-audit { max-height: 72vh; overflow: auto; }
.detail-btn { margin-left: auto; font-size: 11px; }

@media (max-width: 720px) {
  .route { flex-direction: column; align-items: flex-start; }
  .route-arrow { flex: 0 0 18px; width: 18px; height: 18px; margin-left: 18px; }
  .route-arrow::before {
    left: 50%; top: 3px; right: auto; bottom: 7px;
    width: 1px; height: auto;
    background: linear-gradient(180deg,
      color-mix(in srgb, var(--color-border) 35%, transparent),
      var(--color-border));
  }
  .route-arrow::after {
    left: 50%; right: auto; top: auto; bottom: 6px;
    margin: 0 0 0 -3px; transform: rotate(135deg);
  }
  .route-flow {
    left: 50%; top: 3px; margin: -2.5px 0 0 -2.5px;
    animation-name: route-flow-v;
  }
  @keyframes route-flow-v {
    0% { top: 3px; opacity: 0; }
    12% { opacity: 1; }
    88% { opacity: 1; }
    100% { top: calc(100% - 8px); opacity: 0; }
  }
}
</style>
