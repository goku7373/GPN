async function request(path, options = {}) {
  const response = await fetch(path, {
    credentials: 'same-origin',
    headers: { 'Content-Type': 'application/json' },
    ...options
  })
  let data = null
  try { data = await response.json() } catch { data = null }
  if (!response.ok) {
    throw new Error((data && data.error) || `Request failed (${response.status})`)
  }
  return data
}

export const api = {
  health: () => request('/api/health'),
  me: () => request('/api/auth/me'),
  login: (username, password) =>
    request('/api/auth/login', { method: 'POST', body: JSON.stringify({ username, password }) }),
  logout: () => request('/api/auth/logout', { method: 'POST' }),

  overview: (lines = 200) => request(`/api/overview?lines=${lines}`),
  sessions: () => request('/api/sessions'),
  monitorIke: (vpnIp) => request(`/api/monitor/ikev2/${encodeURIComponent(vpnIp)}`),
  monitorVless: (client) => request(`/api/monitor/vless?client=${encodeURIComponent(client)}`),
  createClient: (name) =>
    request('/api/clients', { method: 'POST', body: JSON.stringify({ name }) }),
  toggleClient: (id) => request(`/api/clients/${id}/toggle`, { method: 'POST' }),
  banClient: (id) => request(`/api/clients/${id}/ban`, { method: 'POST' }),
  unbanClient: (id) => request(`/api/clients/${id}/unban`, { method: 'POST' }),
  terminateIke: (ikeId) => request(`/api/sessions/ikev2/${ikeId}/terminate`, { method: 'POST' }),
  speedTestIke: (ikeId) => request(`/api/sessions/ikev2/${ikeId}/speedtest`, { method: 'POST' }),
  speedTestAll: () => request('/api/sessions/speedtest-all', { method: 'POST' }),
  rotateClient: (id) => request(`/api/clients/${id}/rotate`, { method: 'POST' }),
  deleteClient: (id) => request(`/api/clients/${id}`, { method: 'DELETE' }),
  shareClient: (id) => request(`/api/clients/${id}/share`),
  restartProxy: () => request('/api/proxy/restart', { method: 'POST' }),
}
