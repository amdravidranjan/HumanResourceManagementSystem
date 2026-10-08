// Thin fetch wrapper: adds the session token, surfaces API error messages, tracks which node served us.
const API = {
  lastNode: null,
  token() { try { return localStorage.getItem('hrms.token'); } catch { return null; } },
  user() { try { return JSON.parse(localStorage.getItem('hrms.user') || 'null'); } catch { return null; } },
  save(login) {
    localStorage.setItem('hrms.token', login.token);
    localStorage.setItem('hrms.user', JSON.stringify({ employeeId: login.employeeId, name: login.name, role: login.role, department: login.department }));
  },
  async req(method, path, body) {
    const headers = { Accept: 'application/json' };
    if (body !== undefined) headers['Content-Type'] = 'application/json';
    const t = API.token();
    if (t) headers.Authorization = 'Bearer ' + t;
    let res;
    try {
      res = await fetch(path, { method, headers, body: body === undefined ? undefined : JSON.stringify(body) });
    } catch (e) {
      throw new Error('Cannot reach the server. Is the gateway running?');
    }
    const served = res.headers.get('X-Served-By');
    if (served) { API.lastNode = served; window.dispatchEvent(new CustomEvent('served', { detail: served })); }
    const text = await res.text();
    let data = null;
    try { data = text ? JSON.parse(text) : null; } catch { data = text; }
    if (res.status === 401 && !path.endsWith('/auth/login')) {
      API.clear();
      location.href = '/index.html?expired=1';
      throw new Error('Session expired');
    }
    if (!res.ok) {
      const err = new Error((data && data.error) || ('Request failed with status ' + res.status));
      err.status = res.status;
      throw err;
    }
    return data;
  },
  get(p) { return API.req('GET', p); },
  post(p, b) { return API.req('POST', p, b === undefined ? {} : b); },
  put(p, b) { return API.req('PUT', p, b); },
  clear() { try { localStorage.removeItem('hrms.token'); localStorage.removeItem('hrms.user'); } catch { /* ignore */ } },
  logout() {
    const t = API.token();
    if (t) fetch('/api/auth/logout', { method: 'POST', headers: { Authorization: 'Bearer ' + t } }).catch(() => {});
    API.clear();
  }
};

function esc(s) {
  return String(s ?? '').replace(/[&<>"']/g, c => ({ '&': '&amp;', '<': '&lt;', '>': '&gt;', '"': '&quot;', "'": '&#39;' }[c]));
}
function money(n) { return 'Rs. ' + Number(n || 0).toLocaleString('en-IN', { maximumFractionDigits: 2 }); }
function when(ms) { return ms ? new Date(Number(ms)).toLocaleString('en-IN') : '-'; }
function timeOf(ms) { return ms ? new Date(Number(ms)).toLocaleTimeString('en-IN', { hour: '2-digit', minute: '2-digit' }) : '-'; }
function thisMonth() { const d = new Date(); return d.getFullYear() + '-' + String(d.getMonth() + 1).padStart(2, '0'); }
function pct(x) { return (Number(x || 0) * 100).toFixed(1) + '%'; }
let toastTimer;
function toast(msg, bad) {
  let el = document.querySelector('.toast');
  if (!el) { el = document.createElement('div'); el.className = 'toast'; document.body.appendChild(el); }
  el.textContent = msg;
  el.classList.toggle('bad', !!bad);
  el.style.display = 'block';
  clearTimeout(toastTimer);
  toastTimer = setTimeout(() => { el.style.display = 'none'; }, bad ? 6000 : 3000);
}
async function guard(fn) {
  try { return await fn(); } catch (e) { toast(e.message, true); return undefined; }
}
function stageBadge(s) {
  const cls = { APPROVED: 'ok', HIRED: 'ok', SUBMITTED: 'ok', DONE: 'ok', REJECTED: 'bad', CANCELLED: 'bad', PENDING: 'warn', OFFER: 'warn' }[s] || '';
  return `<span class="badge ${cls}">${esc(s)}</span>`;
}
