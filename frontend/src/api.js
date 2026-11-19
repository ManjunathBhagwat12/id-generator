// Thin fetch wrapper. All data shown in the UI comes from these backend calls.
// Session auth uses an HttpOnly cookie (invisible to JS); state-changing calls also echo the XSRF-TOKEN cookie
// as the X-XSRF-TOKEN header (CSRF double-submit).

export class ApiError extends Error {
  constructor(message, status) {
    super(message);
    this.status = status;
  }
}

let onUnauthorized = () => {};
export const setUnauthorizedHandler = (fn) => { onUnauthorized = fn; };

const readCookie = (name) =>
  document.cookie.split('; ').find((c) => c.startsWith(`${name}=`))?.slice(name.length + 1);

async function csrfToken() {
  let token = readCookie('XSRF-TOKEN');
  if (!token) {
    await fetch('/api/v1/auth/me'); // makes the server issue the cookie
    token = readCookie('XSRF-TOKEN');
  }
  return token ? decodeURIComponent(token) : '';
}

async function request(path, { method = 'GET', json, form, auth = false } = {}) {
  const headers = {};
  let body;
  if (json !== undefined) {
    headers['Content-Type'] = 'application/json';
    body = JSON.stringify(json);
  } else if (form) {
    headers['Content-Type'] = 'application/x-www-form-urlencoded';
    body = new URLSearchParams(form).toString();
  }
  if (method !== 'GET') headers['X-XSRF-TOKEN'] = await csrfToken();

  const res = await fetch(path, { method, headers, body, credentials: 'same-origin' });
  let data = null;
  try { data = await res.json(); } catch { /* empty or non-JSON body */ }
  if (!res.ok) {
    if (res.status === 401 && auth) onUnauthorized();
    throw new ApiError(data?.message || `Request failed (HTTP ${res.status})`, res.status);
  }
  return data;
}

// ---- public (ID generator) ----
export const getTemplates = () => request('/api/v1/templates');
export const getCities = () => request('/api/v1/cities'); // active tenants only
export const getRecent = () => request('/api/v1/ids/recent');

/** count === 1 uses the single endpoint, otherwise the bulk endpoint. Always returns string[]. */
export async function generate(templateId, city, count) {
  if (count === 1) {
    const r = await request('/api/v1/ids/issue', { method: 'POST', json: { templateId, city } });
    return [r.id];
  }
  const r = await request('/api/v1/ids/bulk', { method: 'POST', json: { templateId, city, count } });
  return r.ids;
}

// ---- auth ----
export const me = () => request('/api/v1/auth/me');
export const login = (username, password) =>
  request('/api/v1/auth/login', { method: 'POST', form: { username, password } });
export const logout = () => request('/api/v1/auth/logout', { method: 'POST', json: {} });

// ---- admin: tenants ----
const qs = (o) => {
  const p = new URLSearchParams();
  Object.entries(o).forEach(([k, v]) => v && v !== 'all' && p.set(k, v));
  const s = p.toString();
  return s ? `?${s}` : '';
};
export const listTenants = (search, status) =>
  request(`/api/v1/admin/tenants${qs({ search, status })}`, { auth: true });
export const tenantSummary = () => request('/api/v1/admin/tenants/summary', { auth: true });
export const getTenant = (id) => request(`/api/v1/admin/tenants/${encodeURIComponent(id)}`, { auth: true });
export const createTenant = (t) => request('/api/v1/admin/tenants', { method: 'POST', json: t, auth: true });
export const updateTenant = (id, t) =>
  request(`/api/v1/admin/tenants/${encodeURIComponent(id)}`, { method: 'PUT', json: t, auth: true });
export const setTenantActive = (id, active) =>
  request(`/api/v1/admin/tenants/${encodeURIComponent(id)}/status`, { method: 'PATCH', json: { active }, auth: true });

// ---- admin: templates (immutable: create and view only; there is intentionally no update/delete call) ----
const T = '/api/v1/admin/templates';
export const listTemplates = () => request(T, { auth: true });
export const getTemplate = (id) => request(`${T}/${encodeURIComponent(id)}`, { auth: true });
export const createTemplate = (t) => request(T, { method: 'POST', json: t, auth: true });

// ---- admin: analytics (read-only aggregates from issued_ids) ----
export const getAnalytics = (period) => request(`/api/v1/admin/analytics?period=${encodeURIComponent(period)}`, { auth: true });

// ---- admin: API credentials (the full key is returned only by create and rotate) ----
const AC = '/api/v1/admin/api-credentials';
export const listCredentials = () => request(AC, { auth: true });
export const getCredential = (id) => request(`${AC}/${encodeURIComponent(id)}`, { auth: true });
export const createCredential = (c) => request(AC, { method: 'POST', json: c, auth: true });
export const rotateCredential = (id) => request(`${AC}/${encodeURIComponent(id)}/rotate`, { method: 'POST', json: {}, auth: true });
export const revokeCredential = (id) => request(`${AC}/${encodeURIComponent(id)}/revoke`, { method: 'POST', json: {}, auth: true });
