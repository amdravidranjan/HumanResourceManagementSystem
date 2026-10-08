// Single-page app: hash router + one render function per page.
const me = API.user();
if (!API.token() || !me) location.href = '/index.html';
const isHr = me && me.role === 'HR_ADMIN';
const isMgr = me && (me.role === 'MANAGER' || isHr);
const view = document.getElementById('view');

const NAV = [
  ['dashboard', 'Dashboard', true], ['employees', 'Employees', true], ['attendance', 'Attendance', true],
  ['leave', 'Leave', true], ['payroll', 'Payroll', true], ['recruitment', 'Recruitment', isHr],
  ['performance', 'Performance', true], ['notifications', 'Notifications', true], ['system', 'System', isHr]
];

document.getElementById('who').textContent = me ? `${me.name} · ${me.role.replace('_', ' ')}` : '';
document.getElementById('logout').onclick = () => { API.logout(); location.href = '/index.html'; };
window.addEventListener('served', e => { document.getElementById('served').textContent = 'served by ' + e.detail; });

function renderNav(active) {
  document.getElementById('nav').innerHTML = NAV.filter(n => n[2])
    .map(([k, label]) => `<a href="#/${k}" class="${k === active ? 'active' : ''}">${label}${k === 'notifications' ? ' <span class="badge" id="unread"></span>' : ''}</a>`).join('');
  API.get('/api/notifications?limit=1').then(r => { const b = document.getElementById('unread'); if (b) b.textContent = r.unread || ''; }).catch(() => {});
}
function setTitle(t, sub) { document.getElementById('title').textContent = t; document.getElementById('subtitle').textContent = sub || ''; }
function debounce(fn, ms) { let t; return (...a) => { clearTimeout(t); t = setTimeout(() => fn(...a), ms); }; }

const routes = {
  dashboard: pageDashboard, employees: pageEmployees, employee: pageEmployee, attendance: pageAttendance, leave: pageLeave,
  payroll: pagePayroll, payslip: pagePayslip, recruitment: pageRecruitment, performance: pagePerformance,
  notifications: pageNotifications, system: pageSystem
};
let pollTimer = null;
async function router() {
  clearInterval(pollTimer);
  const [name, ...params] = (location.hash.replace(/^#\/?/, '') || 'dashboard').split('/');
  const fn = routes[name] || pageDashboard;
  renderNav(name === 'employee' ? 'employees' : name === 'payslip' ? 'payroll' : name);
  view.innerHTML = '<p class="muted">Loading...</p>';
  try { await fn(...params.map(decodeURIComponent)); } catch (e) { view.innerHTML = `<div class="card">${esc(e.message)}</div>`; }
}
window.addEventListener('hashchange', router);

/* ---------------- Dashboard ---------------- */
async function pageDashboard() {
  setTitle('Welcome, ' + me.name, me.department + ' · ' + new Date().toDateString());
  const month = thisMonth();
  const [att, bal, notes] = await Promise.all([
    API.get('/api/attendance/me?month=' + month), API.get('/api/leaves/balance/' + me.employeeId), API.get('/api/notifications?limit=5')]);
  const today = new Date().toISOString().slice(0, 10);
  const t = att.find(a => a.date === today);
  const pending = isMgr ? await API.get('/api/leaves/pending').catch(() => []) : [];
  view.innerHTML = `
    <div class="grid g4">
      <div class="card stat"><div class="l">Today</div><div class="v">${t ? (t.checkOut ? 'Done' : 'In') : 'Not in'}</div>
        <div class="small muted">${t ? 'Checked in ' + timeOf(t.checkIn) + (t.checkOut ? ' · out ' + timeOf(t.checkOut) : '') : ''}</div>
        <div style="margin-top:8px">${!t ? '<button id="ci" class="sm">Check in</button>' : !t.checkOut ? '<button id="co" class="sm secondary">Check out</button>' : ''}</div></div>
      <div class="card stat"><div class="l">Days present this month</div><div class="v">${att.length}</div></div>
      <div class="card stat"><div class="l">Leave balance (C / S / E)</div><div class="v">${bal.casual} / ${bal.sick} / ${bal.earned}</div></div>
      <div class="card stat"><div class="l">${isMgr ? 'Leaves awaiting you' : 'Unread notifications'}</div><div class="v">${isMgr ? pending.length : notes.unread}</div>
        ${isMgr ? '<a class="small" href="#/leave">Review</a>' : ''}</div>
    </div>
    <div class="card"><h2>Recent notifications</h2>${notes.items.length ? `<table>${notes.items.map(n =>
      `<tr><td>${stageBadge(n.type)}</td><td>${esc(n.message)}</td><td class="muted small">${when(n.createdAt)}</td></tr>`).join('')}</table>` : '<div class="empty">Nothing new.</div>'}</div>`;
  const ci = document.getElementById('ci'); if (ci) ci.onclick = () => guard(async () => { await API.post('/api/attendance/check-in'); toast('Checked in'); router(); });
  const co = document.getElementById('co'); if (co) co.onclick = () => guard(async () => { await API.post('/api/attendance/check-out'); toast('Checked out'); router(); });
}

/* ---------------- Employees ---------------- */
function autocomplete(input, fetcher, render, onPick) {
  const wrap = input.parentElement;
  wrap.classList.add('ac');
  const list = document.createElement('div');
  list.className = 'ac-list';
  list.style.display = 'none';
  wrap.appendChild(list);
  let items = [];
  let sel = -1;
  const show = () => {
    list.innerHTML = items.map((it, i) => `<div class="ac-item ${i === sel ? 'sel' : ''}" data-i="${i}">${render(it)}</div>`).join('');
    list.style.display = items.length ? 'block' : 'none';
  };
  input.addEventListener('input', debounce(async () => {
    const q = input.value.trim();
    if (!q) { items = []; show(); return; }
    const t0 = performance.now();
    items = await fetcher(q).catch(() => []);
    const ms = (performance.now() - t0).toFixed(1);
    const info = document.getElementById('acinfo');
    if (info) info.textContent = `${items.length} suggestion(s) in ${ms} ms (round trip via gateway)`;
    sel = -1;
    show();
  }, 120));
  input.addEventListener('keydown', e => {
    if (e.key === 'ArrowDown') { sel = Math.min(items.length - 1, sel + 1); show(); e.preventDefault(); }
    if (e.key === 'ArrowUp') { sel = Math.max(0, sel - 1); show(); e.preventDefault(); }
    if (e.key === 'Enter' && sel >= 0) { onPick(items[sel]); list.style.display = 'none'; e.preventDefault(); }
    if (e.key === 'Escape') list.style.display = 'none';
  });
  list.addEventListener('mousedown', e => { const el = e.target.closest('.ac-item'); if (el) { onPick(items[+el.dataset.i]); list.style.display = 'none'; } });
  input.addEventListener('blur', () => setTimeout(() => { list.style.display = 'none'; }, 150));
}

async function pageEmployees() {
  setTitle('Employees', 'Search uses the autocomplete trie on each HR node; full search fans out to every shard');
  const depts = await API.get('/api/employees/departments');
  view.innerHTML = `
    <div class="card">
      <div class="row">
        <div class="field" style="flex:3"><label>Search by name, department, designation or skill</label><input id="q" placeholder="Start typing, e.g. 'pri' or 'java'"></div>
        <div class="field"><label>Department</label><select id="dept"><option value="">All</option>${depts.map(d => `<option>${esc(d)}</option>`).join('')}</select></div>
        <div class="field shrink"><button id="go">Search</button></div>
      </div>
      <div class="small muted" id="acinfo"></div>
    </div>
    <div class="card"><h2>Results</h2><div id="res" class="tablewrap"><div class="empty">Search to see employees.</div></div></div>
    ${isHr ? registerForm(depts) : ''}`;
  const q = document.getElementById('q');
  autocomplete(q, s => API.get('/api/employees/suggest?q=' + encodeURIComponent(s)),
    h => `<b>${esc(h.name)}</b> <span class="muted small">${esc(h.designation || '')} · ${esc(h.department || '')}</span>`,
    h => { location.hash = '#/employee/' + h.id; });
  const run = () => guard(async () => {
    const r = await API.get(`/api/employees/search?limit=100&q=${encodeURIComponent(q.value)}&department=${encodeURIComponent(document.getElementById('dept').value)}`);
    document.getElementById('res').innerHTML = (r.failedShards.length ? `<p class="badge warn">Partial results: ${esc(r.failedShards.join(', '))} unavailable</p>` : '') +
      (r.items.length ? `<table><tr><th>Name</th><th>Designation</th><th>Department</th><th>Email</th></tr>${r.items.map(e =>
        `<tr><td><a href="#/employee/${e.id}">${esc(e.name)}</a></td><td>${esc(e.designation)}</td><td>${esc(e.department)}</td><td>${esc(e.email)}</td></tr>`).join('')}</table>`
        : '<div class="empty">No employees match.</div>');
  });
  document.getElementById('go').onclick = run;
  if (isHr) bindRegister();
}

function registerForm(depts) {
  return `<div class="card"><h2>Register a new employee</h2><form id="reg">
    <div class="row"><div class="field"><label>Full name</label><input name="name" required></div>
      <div class="field"><label>Email</label><input name="email" type="email" required></div>
      <div class="field"><label>Initial password</label><input name="password" value="welcome123"></div></div>
    <div class="row"><div class="field"><label>Role</label><select name="role"><option>EMPLOYEE</option><option>MANAGER</option><option>HR_ADMIN</option></select></div>
      <div class="field"><label>Department</label><select name="department">${depts.map(d => `<option>${esc(d)}</option>`).join('')}</select></div>
      <div class="field"><label>Designation</label><input name="designation" value="Associate"></div></div>
    <div class="row"><div class="field"><label>Manager (type to search)</label><input id="mgrq" placeholder="optional"><input type="hidden" name="managerId"></div>
      <div class="field"><label>Skills (comma separated)</label><input name="skills"></div>
      <div class="field"><label>Phone</label><input name="phone"></div></div>
    <div class="row"><div class="field"><label>Basic (monthly)</label><input name="basic" type="number" value="30000"></div>
      <div class="field"><label>HRA</label><input name="hra" type="number" value="12000"></div>
      <div class="field"><label>Allowances</label><input name="allowances" type="number" value="8000"></div>
      <div class="field shrink"><button>Register</button></div></div></form>
    <div id="regout" class="small muted"></div></div>`;
}
function bindRegister() {
  const f = document.getElementById('reg');
  autocomplete(document.getElementById('mgrq'), s => API.get('/api/employees/suggest?q=' + encodeURIComponent(s)),
    h => `${esc(h.name)} <span class="muted small">${esc(h.designation || '')}</span>`,
    h => { document.getElementById('mgrq').value = h.name; f.managerId.value = h.id; });
  f.onsubmit = e => { e.preventDefault(); guard(async () => {
    const t0 = performance.now();
    const emp = await API.post('/api/employees', {
      name: f.name.value, email: f.email.value, password: f.password.value, role: f.role.value, department: f.department.value,
      designation: f.designation.value, managerId: f.managerId.value || null, phone: f.phone.value,
      skills: f.skills.value.split(',').map(s => s.trim()).filter(Boolean),
      salary: { basic: +f.basic.value, hra: +f.hra.value, allowances: +f.allowances.value, deductions: 200 } });
    document.getElementById('regout').innerHTML = `Created <a href="#/employee/${emp.id}">${esc(emp.name)}</a> with Snowflake ID <code>${emp.id}</code> in ${(performance.now() - t0).toFixed(0)} ms. They are searchable on every node now.`;
    toast('Employee registered');
    f.reset();
  }); };
}

async function pageEmployee(id) {
  const e = await API.get('/api/employees/' + id);
  setTitle(e.name, `${e.designation} · ${e.department}`);
  const canEdit = isHr || id === me.employeeId;
  const [team, mgr] = await Promise.all([API.get(`/api/employees/${id}/team`), e.managerId ? API.get('/api/employees/' + e.managerId).catch(() => null) : null]);
  view.innerHTML = `
    <div class="grid g2">
      <div class="card"><h2>Profile</h2><div class="kv">
        <div>Employee ID</div><div><code>${esc(e.id)}</code></div><div>Email</div><div>${esc(e.email)}</div>
        <div>Phone</div><div>${esc(e.phone || '-')}</div><div>Role</div><div>${esc(e.role)}</div>
        <div>Manager</div><div>${mgr ? `<a href="#/employee/${mgr.id}">${esc(mgr.name)}</a>` : '-'}</div>
        <div>Joined</div><div>${esc(e.joinDate)}</div><div>Status</div><div>${stageBadge(e.status)}</div>
        <div>Skills</div><div>${(e.skills || []).map(s => `<span class="badge">${esc(s)}</span>`).join(' ') || '-'}</div></div></div>
      <div class="card"><h2>Compensation & leave</h2>${e.salary ? `<div class="kv">
        <div>Basic</div><div>${money(e.salary.basic)}</div><div>HRA</div><div>${money(e.salary.hra)}</div>
        <div>Allowances</div><div>${money(e.salary.allowances)}</div><div>Monthly gross</div><div><b>${money(e.salary.basic + e.salary.hra + e.salary.allowances)}</b></div>
        <div>Leave (C/S/E)</div><div>${e.leaveBalance.casual} / ${e.leaveBalance.sick} / ${e.leaveBalance.earned}</div></div>`
        : '<div class="empty">Salary details are visible only to the employee, their manager and HR.</div>'}</div>
    </div>
    ${canEdit ? `<div class="card"><h2>Edit</h2><form id="ed"><div class="row">
      <div class="field"><label>Phone</label><input name="phone" value="${esc(e.phone || '')}"></div>
      <div class="field"><label>Skills</label><input name="skills" value="${esc((e.skills || []).join(', '))}"></div>
      ${isHr ? `<div class="field"><label>Designation</label><input name="designation" value="${esc(e.designation)}"></div>
      <div class="field"><label>Status</label><select name="status">${['ACTIVE', 'INACTIVE'].map(s => `<option ${s === e.status ? 'selected' : ''}>${s}</option>`).join('')}</select></div>` : ''}
      <div class="field shrink"><button>Save</button></div></div></form></div>` : ''}
    <div class="card"><h2>Direct reports (${team.length})</h2>${team.length ? `<div class="tablewrap"><table><tr><th>Name</th><th>Designation</th></tr>${team.slice(0, 300).map(t =>
      `<tr><td><a href="#/employee/${t.id}">${esc(t.name)}</a></td><td>${esc(t.designation)}</td></tr>`).join('')}</table></div>` : '<div class="empty">None.</div>'}</div>`;
  const f = document.getElementById('ed');
  if (f) f.onsubmit = ev => { ev.preventDefault(); guard(async () => {
    const body = { phone: f.phone.value, skills: f.skills.value.split(',').map(s => s.trim()).filter(Boolean) };
    if (isHr) { body.designation = f.designation.value; body.status = f.status.value; }
    await API.put('/api/employees/' + id, body);
    toast('Saved');
    router();
  }); };
}

/* ---------------- Attendance ---------------- */
async function pageAttendance(monthArg) {
  const month = monthArg || thisMonth();
  setTitle('Attendance', 'Records are stored on your shard together with your profile');
  const rows = await API.get('/api/attendance/me?month=' + month);
  const summary = isHr ? await API.get('/api/attendance/summary?month=' + month).catch(() => null) : null;
  view.innerHTML = `
    <div class="card row"><div class="field shrink"><button id="ci">Check in</button></div><div class="field shrink"><button id="co" class="secondary">Check out</button></div>
      <div class="field"><label>Month</label><input type="month" id="m" value="${month}"></div></div>
    ${summary ? `<div class="card"><h2>Organisation summary · ${esc(summary.month)}</h2><div class="grid g4">
      <div class="stat"><div class="l">Attendance records</div><div class="v">${summary.attendanceRecords.toLocaleString()}</div></div>
      <div class="stat"><div class="l">Active employees</div><div class="v">${summary.activeEmployees.toLocaleString()}</div></div>
      <div class="stat"><div class="l">Working days so far</div><div class="v">${summary.workingDaysSoFar}</div></div>
      <div class="stat"><div class="l">Attendance rate</div><div class="v">${pct(summary.attendanceRate)}</div></div></div>
      <table style="margin-top:10px"><tr><th>Shard</th><th>Records</th><th>Employees</th></tr>${Object.entries(summary.perShard).map(([s, v]) =>
        `<tr><td>${esc(s)}</td><td>${v.attendanceRecords}</td><td>${v.activeEmployees}</td></tr>`).join('')}</table></div>` : ''}
    <div class="card"><h2>My attendance · ${esc(month)}</h2>${rows.length ? `<table><tr><th>Date</th><th>Check in</th><th>Check out</th><th>Hours</th></tr>${rows.map(a =>
      `<tr><td>${esc(a.date)}</td><td>${timeOf(a.checkIn)}</td><td>${timeOf(a.checkOut)}</td><td>${a.hours || '-'}</td></tr>`).join('')}</table>` : '<div class="empty">No records.</div>'}</div>`;
  document.getElementById('m').onchange = e => { location.hash = '#/attendance/' + e.target.value; };
  document.getElementById('ci').onclick = () => guard(async () => { const a = await API.post('/api/attendance/check-in'); toast('Checked in at ' + timeOf(a.checkIn)); router(); });
  document.getElementById('co').onclick = () => guard(async () => { const a = await API.post('/api/attendance/check-out'); toast('Checked out, ' + a.hours + ' h'); router(); });
}

/* ---------------- Leave ---------------- */
async function pageLeave() {
  setTitle('Leave', 'Balances are cached in the Redis key-value store (5 min TTL)');
  const [bal, mine, pending] = await Promise.all([API.get('/api/leaves/balance/' + me.employeeId), API.get('/api/leaves/me'),
    isMgr ? API.get('/api/leaves/pending') : Promise.resolve([])]);
  view.innerHTML = `
    <div class="grid g3">${['casual', 'sick', 'earned'].map(k => `<div class="card stat"><div class="l">${k} leave left</div><div class="v">${bal[k]}</div></div>`).join('')}</div>
    <div class="card"><h2>Apply for leave</h2><form id="ap" class="row">
      <div class="field"><label>Type</label><select name="type"><option>CASUAL</option><option>SICK</option><option>EARNED</option></select></div>
      <div class="field"><label>From</label><input type="date" name="from" required></div>
      <div class="field"><label>To</label><input type="date" name="to" required></div>
      <div class="field" style="flex:2"><label>Reason</label><input name="reason"></div>
      <div class="field shrink"><button>Apply</button></div></form></div>
    ${isMgr ? `<div class="card"><h2>Awaiting your decision (${pending.length})</h2>${pending.length ? `<div class="tablewrap"><table><tr><th>Employee</th><th>Type</th><th>Dates</th><th>Days</th><th>Reason</th><th></th></tr>${pending.map(l =>
      `<tr><td><a href="#/employee/${l.employeeId}">${esc(l.employeeName)}</a></td><td>${esc(l.type)}</td><td>${esc(l.from)} → ${esc(l.to)}</td><td>${l.days}</td><td>${esc(l.reason || '')}</td>
       <td><button class="sm ok" data-a="1" data-e="${l.employeeId}" data-l="${l.id}">Approve</button> <button class="sm danger" data-a="0" data-e="${l.employeeId}" data-l="${l.id}">Reject</button></td></tr>`).join('')}</table></div>` : '<div class="empty">Nothing pending.</div>'}</div>` : ''}
    <div class="card"><h2>My requests</h2>${mine.length ? `<table><tr><th>Type</th><th>Dates</th><th>Days</th><th>Status</th><th></th></tr>${mine.map(l =>
      `<tr><td>${esc(l.type)}</td><td>${esc(l.from)} → ${esc(l.to)}</td><td>${l.days}</td><td>${stageBadge(l.status)}</td>
       <td>${l.status === 'PENDING' ? `<button class="sm secondary" data-c="${l.id}">Cancel</button>` : ''}</td></tr>`).join('')}</table>` : '<div class="empty">No requests yet.</div>'}</div>`;
  const f = document.getElementById('ap');
  f.onsubmit = e => { e.preventDefault(); guard(async () => {
    const l = await API.post('/api/leaves', { type: f.type.value, from: f.from.value, to: f.to.value, reason: f.reason.value });
    toast(`Requested ${l.days} working day(s)`);
    router();
  }); };
  view.querySelectorAll('[data-a]').forEach(b => b.onclick = () => guard(async () => {
    await API.post(`/api/leaves/${b.dataset.e}/${b.dataset.l}/decision`, { approve: b.dataset.a === '1', comment: '' });
    toast(b.dataset.a === '1' ? 'Approved' : 'Rejected');
    router();
  }));
  view.querySelectorAll('[data-c]').forEach(b => b.onclick = () => guard(async () => { await API.post(`/api/leaves/${b.dataset.c}/cancel`); toast('Cancelled'); router(); }));
}

/* ---------------- Payroll ---------------- */
async function pagePayroll() {
  setTitle('Payroll', 'Payroll runs on every shard in parallel; payslips are co-located with the employee');
  const slips = await API.get('/api/payroll/me');
  view.innerHTML = `
    ${isHr ? `<div class="card"><h2>Run payroll</h2><form id="run" class="row">
      <div class="field"><label>Month</label><input type="month" name="month" value="${thisMonth()}"></div>
      <div class="field"><label>Department (optional)</label><input name="department" placeholder="All departments"></div>
      <div class="field shrink"><button>Run payroll</button></div></form>
      <p class="small muted">Rate limited to 2 runs per minute per user (token bucket at the gateway).</p><div id="runout"></div></div>` : ''}
    <div class="card"><h2>My payslips</h2>${slips.length ? `<table><tr><th>Month</th><th>Gross</th><th>Net</th><th>LOP days</th><th></th></tr>${slips.map(p =>
      `<tr><td>${esc(p.month)}</td><td>${money(p.gross)}</td><td><b>${money(p.net)}</b></td><td>${p.lopDays}</td><td><a href="#/payslip/${p.employeeId}/${p.month}">View</a></td></tr>`).join('')}</table>`
      : '<div class="empty">No payslips yet. HR runs payroll monthly.</div>'}</div>`;
  const f = document.getElementById('run');
  if (f) f.onsubmit = e => { e.preventDefault(); guard(async () => {
    document.getElementById('runout').innerHTML = '<p class="muted">Running...</p>';
    const r = await API.post('/api/payroll/run', { month: f.month.value, department: f.department.value || null });
    document.getElementById('runout').innerHTML = `<div class="grid g4">
      <div class="stat"><div class="l">Employees paid</div><div class="v">${r.employeesProcessed.toLocaleString()}</div></div>
      <div class="stat"><div class="l">Total net pay</div><div class="v">${money(r.totalNetPay)}</div></div>
      <div class="stat"><div class="l">Working days</div><div class="v">${r.workingDays}</div></div>
      <div class="stat"><div class="l">Duration</div><div class="v">${(r.durationMs / 1000).toFixed(1)} s</div></div></div>
      <p class="small">Per shard: ${Object.entries(r.perShard).map(([s, n]) => `${esc(s)}: ${n}`).join(' · ')}
      ${r.failedShards.length ? `<span class="badge bad">failed: ${esc(r.failedShards.join(', '))}</span>` : ''}</p>`;
    toast('Payroll complete');
  }); };
}

async function pagePayslip(employeeId, month) {
  const p = await API.get(`/api/payroll/${employeeId}/${month}`);
  setTitle('Payslip · ' + p.month, p.employeeName + ' · ' + p.department);
  view.innerHTML = `<div class="card" style="max-width:720px"><div class="kv">
    <div>Employee</div><div>${esc(p.employeeName)} (<code>${esc(p.employeeId)}</code>)</div>
    <div>Working days</div><div>${p.workingDays} (present ${p.presentDays}, approved leave ${p.leaveDays}, loss of pay ${p.lopDays})</div>
    <div>Basic</div><div>${money(p.basic)}</div><div>HRA</div><div>${money(p.hra)}</div><div>Allowances</div><div>${money(p.allowances)}</div>
    <div><b>Gross</b></div><div><b>${money(p.gross)}</b></div>
    <div>Provident fund (12%)</div><div>- ${money(p.pf)}</div><div>Income tax</div><div>- ${money(p.tax)}</div>
    <div>Loss of pay</div><div>- ${money(p.lop)}</div><div>Professional tax</div><div>- ${money(p.otherDeductions)}</div>
    <div><b>Net pay</b></div><div><b>${money(p.net)}</b></div></div>
    <div class="row" style="margin-top:14px"><div class="field shrink"><button id="share">Create 24-hour share link</button></div><div class="field" id="link"></div></div></div>`;
  document.getElementById('share').onclick = () => guard(async () => {
    const r = await API.post(`/api/payroll/${employeeId}/${month}/share`);
    const url = location.origin + r.shortUrl;
    document.getElementById('link').innerHTML = `<a href="${esc(url)}" target="_blank">${esc(url)}</a> <span class="small muted">(expires in ${r.expiresInHours} h)</span>`;
  });
}

/* ---------------- Recruitment ---------------- */
async function pageRecruitment(tab) {
  const t = tab || 'INTERNAL';
  setTitle('Recruitment', 'Internal postings get a public short link; the crawler imports jobs from simulated portals');
  const jobs = await API.get('/api/recruitment/jobs?status=OPEN' + (t === 'ALL' ? '' : '&source=' + t));
  view.innerHTML = `
    <div class="grid g2">
      <div class="card"><h2>Post a job</h2><form id="job">
        <div class="row"><div class="field"><label>Title</label><input name="title" required></div><div class="field"><label>Location</label><input name="location" value="Chennai" required></div></div>
        <div class="row"><div class="field"><label>Department</label><input name="department" placeholder="auto-detect"></div><div class="field shrink"><button>Publish</button></div></div>
        <div class="field"><label>Description</label><textarea name="description" rows="2"></textarea></div></form></div>
      <div class="card"><h2>Web crawler</h2><p class="small muted">BFS over 3 simulated job portals · depth ≤ 3 · robots.txt honoured · 200 ms politeness · duplicates removed by fingerprint</p>
        <button id="crawl">Start crawl</button><div id="cs" style="margin-top:10px"></div></div>
    </div>
    <div class="card"><div class="row"><div class="tabs shrink">${['INTERNAL', 'CRAWLED', 'ALL'].map(x => `<button class="sm ${x === t ? 'on' : ''}" data-t="${x}">${x}</button>`).join('')}</div>
      <div class="field"><input id="jq" placeholder="Find a job title (autocomplete)"></div></div>
      <div class="tablewrap"><table><tr><th>Title</th><th>Company</th><th>Department</th><th>Location</th><th>Link</th><th></th></tr>${jobs.map(j =>
        `<tr><td>${esc(j.title)}</td><td>${esc(j.company)}</td><td>${esc(j.department)}</td><td>${esc(j.location)}</td>
         <td>${j.shortCode ? `<a href="/s/${esc(j.shortCode)}" target="_blank">/s/${esc(j.shortCode)}</a>` : j.sourceUrl ? `<a href="${esc(j.sourceUrl.replace('http://mock-portals', 'http://' + location.hostname + ':9000'))}" target="_blank">source</a>` : ''}</td>
         <td>${j.source === 'INTERNAL' ? `<button class="sm secondary" data-cand="${j.id}">Candidates</button> <button class="sm danger" data-close="${j.id}">Close</button>` : ''}</td></tr>`).join('')}</table></div></div>
    <div class="card" id="cands"><h2>Candidates</h2><div class="empty">Pick a job to see its pipeline.</div></div>`;
  view.querySelectorAll('[data-t]').forEach(b => b.onclick = () => { location.hash = '#/recruitment/' + b.dataset.t; });
  autocomplete(document.getElementById('jq'), s => API.get('/api/recruitment/jobs/suggest?q=' + encodeURIComponent(s)),
    h => `<b>${esc(h.title)}</b> <span class="muted small">${esc(h.location)} · ${esc(h.source)}</span>`, h => toast(h.title + ' · ' + h.location));
  const f = document.getElementById('job');
  f.onsubmit = e => { e.preventDefault(); guard(async () => {
    const j = await API.post('/api/recruitment/jobs', { title: f.title.value, location: f.location.value, department: f.department.value || null, description: f.description.value });
    toast('Published. Public link: /s/' + j.shortCode);
    router();
  }); };
  view.querySelectorAll('[data-close]').forEach(b => b.onclick = () => guard(async () => { await API.post(`/api/recruitment/jobs/${b.dataset.close}/close`); toast('Closed'); router(); }));
  view.querySelectorAll('[data-cand]').forEach(b => b.onclick = () => showCandidates(b.dataset.cand));
  const showCrawl = async () => {
    const s = await API.get('/api/recruitment/crawl/status').catch(() => null);
    if (!s) return;
    document.getElementById('cs').innerHTML = `<div class="kv small"><div>Status</div><div>${stageBadge(s.status)}</div>
      <div>Pages fetched / failed</div><div>${s.pagesFetched || 0} / ${s.pagesFailed || 0}</div><div>Blocked by robots.txt</div><div>${s.blockedByRobots || 0}</div>
      <div>Jobs found / duplicates</div><div>${s.jobsFound || 0} / ${s.duplicatesDropped || 0}</div><div>Throughput</div><div>${s.pagesPerSecond || 0} pages/s</div>
      ${s.imported !== undefined ? `<div>Imported (new)</div><div>${s.imported} (${s.duplicatesSkipped} already known)</div>` : ''}</div>`;
  };
  showCrawl();
  pollTimer = setInterval(showCrawl, 1000);
  document.getElementById('crawl').onclick = () => guard(async () => { await API.post('/api/recruitment/crawl'); toast('Crawl started'); showCrawl(); });
}

async function showCandidates(jobId) {
  const list = await API.get('/api/recruitment/candidates?jobId=' + jobId);
  const next = { APPLIED: ['SCREENING', 'REJECTED'], SCREENING: ['INTERVIEW', 'REJECTED'], INTERVIEW: ['OFFER', 'REJECTED'], OFFER: ['HIRED', 'REJECTED'] };
  document.getElementById('cands').innerHTML = `<h2>Candidates (${list.length})</h2>${list.length ? `<table><tr><th>Name</th><th>Email</th><th>Stage</th><th>Move to</th><th></th></tr>${list.map(c =>
    `<tr><td>${esc(c.name)}</td><td>${esc(c.email)}</td><td>${stageBadge(c.stage)}</td>
     <td>${(next[c.stage] || []).map(s => `<button class="sm ${s === 'REJECTED' ? 'danger' : 'secondary'}" data-mv="${c.id}" data-s="${s}">${s}</button>`).join(' ')}</td>
     <td class="small">${c.offerLink ? `<a href="${esc(c.offerLink)}" target="_blank">offer link</a>` : ''} ${c.employeeId ? `<a href="#/employee/${c.employeeId}">employee</a>` : ''}</td></tr>`).join('')}</table>`
    : '<div class="empty">No applications yet. Share the job\'s short link.</div>'}`;
  document.querySelectorAll('[data-mv]').forEach(b => b.onclick = () => guard(async () => {
    const c = await API.post(`/api/recruitment/candidates/${b.dataset.mv}/stage`, { stage: b.dataset.s });
    toast(c.name + ' → ' + c.stage + (c.employeeId ? ' (employee record created)' : ''));
    showCandidates(jobId);
  }));
}

/* ---------------- Performance ---------------- */
async function pagePerformance() {
  const { cycle } = await API.get('/api/performance/cycle');
  setTitle('Performance', 'Cycle ' + cycle);
  const [mine, team] = await Promise.all([API.get('/api/performance/' + me.employeeId), isMgr ? API.get('/api/performance/team?cycle=' + cycle) : Promise.resolve([])]);
  const cur = mine.find(r => r.cycle === cycle) || { goals: [], status: 'DRAFT' };
  const locked = cur.status === 'SUBMITTED';
  let goals = cur.goals.length ? cur.goals.map(g => ({ ...g })) : [{ title: '', weight: 100, progress: 0 }];
  view.innerHTML = `
    <div class="card"><h2>My goals · ${esc(cycle)} ${stageBadge(cur.status)} ${cur.rating ? `<span class="badge ok">rating ${cur.rating}/5</span>` : ''}</h2>
      <form id="goals"><div id="grows"></div>
        <div class="row"><div class="field shrink"><button type="button" class="secondary" id="addg" ${locked ? 'disabled' : ''}>Add goal</button></div>
          <div class="field shrink"><button ${locked ? 'disabled' : ''}>Save goals</button></div><div class="field small muted" id="wsum"></div></div></form>
      ${cur.comments ? `<p><b>Manager comments:</b> ${esc(cur.comments)}</p>` : ''}</div>
    ${isMgr ? `<div class="card"><h2>My team (${team.length})</h2>${team.length ? `<div class="tablewrap"><table><tr><th>Name</th><th>Goals</th><th>Status</th><th>Review</th></tr>${team.slice(0, 200).map(m =>
      `<tr><td><a href="#/employee/${m.employeeId}">${esc(m.name)}</a><div class="small muted">${esc(m.designation)}</div></td>
       <td class="small">${(m.goals || []).map(g => `${esc(g.title)} (${g.weight}% · ${g.progress}% done)`).join('<br>') || '-'}</td>
       <td>${stageBadge(m.status)} ${m.rating ? m.rating + '/5' : ''}</td>
       <td>${m.status !== 'SUBMITTED' ? `<div class="row"><select class="shrink" id="r${m.employeeId}">${[5, 4, 3, 2, 1].map(r => `<option>${r}</option>`).join('')}</select>
         <input id="c${m.employeeId}" placeholder="comments"><button class="sm shrink" data-rev="${m.employeeId}">Submit</button></div>` : ''}</td></tr>`).join('')}</table></div>` : '<div class="empty">No direct reports.</div>'}</div>` : ''}`;
  const rows = document.getElementById('grows');
  const readGoals = () => { goals = [...rows.querySelectorAll('.goal')].map(r => ({ title: r.querySelector('.gt').value, weight: +r.querySelector('.gw').value, progress: +r.querySelector('.gp').value })); };
  const showSum = () => { readGoals(); document.getElementById('wsum').textContent = 'Total weight ' + goals.reduce((a, g) => a + g.weight, 0) + '% (must be 100%)'; };
  const drawGoals = () => {
    rows.innerHTML = goals.map((g, i) => `<div class="row goal"><div class="field" style="flex:3"><label>Goal ${i + 1}</label><input class="gt" value="${esc(g.title)}" ${locked ? 'disabled' : ''}></div>
      <div class="field"><label>Weight %</label><input class="gw" type="number" min="0" max="100" value="${g.weight}" ${locked ? 'disabled' : ''}></div>
      <div class="field"><label>Progress %</label><input class="gp" type="number" min="0" max="100" value="${g.progress}" ${locked ? 'disabled' : ''}></div></div>`).join('');
    showSum();
  };
  drawGoals();
  rows.addEventListener('input', showSum);
  document.getElementById('addg').onclick = () => { readGoals(); goals.push({ title: '', weight: 0, progress: 0 }); drawGoals(); };
  document.getElementById('goals').onsubmit = e => { e.preventDefault(); guard(async () => {
    readGoals();
    await API.put(`/api/performance/${me.employeeId}/${cycle}/goals`, { goals: goals.filter(g => g.title.trim()) });
    toast('Goals saved');
    router();
  }); };
  view.querySelectorAll('[data-rev]').forEach(b => b.onclick = () => guard(async () => {
    const id = b.dataset.rev;
    await API.post(`/api/performance/${id}/${cycle}/review`, { rating: +document.getElementById('r' + id).value, comments: document.getElementById('c' + id).value });
    toast('Review submitted');
    router();
  }));
}
/* ---------------- Notifications ---------------- */
async function pageNotifications() {
  setTitle('Notifications', 'Stored on your shard, newest first');
  const r = await API.get('/api/notifications?limit=50');
  view.innerHTML = `<div class="card"><div class="row"><h2>${r.unread} unread</h2><div class="shrink"><button class="secondary sm" id="all">Mark all read</button></div></div>
    ${r.items.length ? `<table>${r.items.map(n => `<tr><td>${stageBadge(n.type)}</td><td>${n.read ? esc(n.message) : '<b>' + esc(n.message) + '</b>'}</td>
      <td class="small muted">${when(n.createdAt)}</td><td>${n.read ? '' : `<button class="sm secondary" data-r="${n.id}">Read</button>`}</td></tr>`).join('')}</table>` : '<div class="empty">No notifications.</div>'}</div>`;
  document.getElementById('all').onclick = () => guard(async () => { await API.post('/api/notifications/read-all'); router(); });
  view.querySelectorAll('[data-r]').forEach(b => b.onclick = () => guard(async () => { await API.post(`/api/notifications/${b.dataset.r}/read`); router(); }));
}

/* ---------------- System (HR) ---------------- */
async function pageSystem() {
  setTitle('System', 'Live view of the gateway ring, HR nodes, MongoDB shards and component metrics (refreshes every 2 s)');
  view.innerHTML = `<div id="sys"><p class="muted">Loading...</p></div>
    <div class="card"><h2>Component benchmarks</h2><div class="row">${['snowflake', 'kv', 'autocomplete', 'hashing'].map(b =>
      `<div class="shrink"><button class="secondary" data-bench="${b}">${b}</button></div>`).join('')}</div><pre class="json" id="benchout">Run a benchmark to see results.</pre></div>`;
  view.querySelectorAll('[data-bench]').forEach(b => b.onclick = () => guard(async () => {
    document.getElementById('benchout').textContent = 'Running ' + b.dataset.bench + '...';
    const r = await API.post('/api/system/bench/' + b.dataset.bench);
    document.getElementById('benchout').textContent = JSON.stringify(r, null, 2);
  }));
  const draw = async () => {
    const [gw, st] = await Promise.all([fetch('/gw/stats').then(r => r.json()), API.get('/api/system/stats')]);
    const maxCount = Math.max(1, ...st.shards.map(s => (s.counts && s.counts.employees) || 0));
    document.getElementById('sys').innerHTML = `
      <div class="grid g4">
        <div class="card stat"><div class="l">Gateway requests</div><div class="v">${gw.gateway.totalRequests.toLocaleString()}</div><div class="small muted">${gw.gateway.retriesAfterNodeFailure} failovers</div></div>
        <div class="card stat"><div class="l">Rate limiter rejections</div><div class="v">${gw.rateLimiter.rejected ?? 0}</div><div class="small muted">${pct(gw.rateLimiter.rejectionRate)} of checked</div></div>
        <div class="card stat"><div class="l">KV cache hit ratio (${esc(st.servedBy)})</div><div class="v">${pct(st.cache.hitRatio)}</div><div class="small muted">${st.cache.hits} hits / ${st.cache.misses} misses</div></div>
        <div class="card stat"><div class="l">Short-link redirects</div><div class="v">${gw.gateway.redirects}</div><div class="small muted">avg ${Number(gw.gateway.avgRedirectMs).toFixed(2)} ms</div></div>
      </div>
      <div class="grid g2">
        <div class="card"><h2>HR nodes (gateway ring)</h2><table><tr><th>Node</th><th>Health</th><th>Ring share</th><th>Requests</th><th>Avg ms</th></tr>${gw.nodes.map(n =>
          `<tr><td>${esc(n.name)}</td><td>${n.healthy ? '<span class="badge ok">UP</span>' : '<span class="badge bad">DOWN</span>'}</td>
           <td><div class="bar"><div style="width:${(n.ringOwnership * 100).toFixed(1)}%"></div></div><span class="small">${pct(n.ringOwnership)}</span></td>
           <td>${n.requests.toLocaleString()} <span class="small muted">(${pct(n.requestShare)})</span></td><td>${n.avgLatencyMs}</td></tr>`).join('')}</table></div>
        <div class="card"><h2>MongoDB shards ${st.rebalancing ? '<span class="badge warn">rebalancing</span>' : ''}</h2><table><tr><th>Shard</th><th>State</th><th>Employees</th><th>Attendance</th><th>Ring share</th></tr>${st.shards.map(s =>
          `<tr><td>${esc(s.name)}</td><td>${!s.healthy ? '<span class="badge bad">DOWN</span>' : s.active ? '<span class="badge ok">active</span>' : '<span class="badge">standby</span>'}</td>
           <td>${s.counts ? s.counts.employees.toLocaleString() : '-'}<div class="bar"><div style="width:${s.counts ? 100 * s.counts.employees / maxCount : 0}%"></div></div></td>
           <td>${s.counts ? s.counts.attendance.toLocaleString() : '-'}</td><td>${pct(s.ringOwnership)}</td></tr>`).join('')}</table>
          ${st.shards.some(s => !s.active && s.healthy) ? `<div style="margin-top:10px">${st.shards.filter(s => !s.active && s.healthy).map(s =>
            `<button class="sm" data-add="${esc(s.name)}">Add ${esc(s.name)} + rebalance</button>`).join(' ')}</div>` : ''}<pre class="json" id="rebal" style="display:none"></pre></div>
      </div>
      <div class="grid g2">
        <div class="card"><h2>Node counters (from Redis heartbeats)</h2><table><tr><th>Node</th><th>Alive</th><th>Requests</th><th>IDs generated</th><th>Trie entries</th><th>Cache hit/miss</th></tr>${st.nodes.map(n =>
          `<tr><td>${esc(n.name)}</td><td>${n.alive ? '<span class="badge ok">yes</span>' : '<span class="badge bad">no</span>'}</td><td>${n.stats.requests || 0}</td>
           <td>${n.stats.idsGenerated || 0}</td><td>${n.stats.trieEntries || 0}</td><td>${n.stats.cacheHits || 0} / ${n.stats.cacheMisses || 0}</td></tr>`).join('')}</table></div>
        <div class="card"><h2>Rate limiter rules</h2><div class="kv small">${Object.entries(gw.rateLimiter.rules || {}).map(([k, v]) => `<div>${esc(k)}</div><div>${esc(v)}</div>`).join('')}
          <div>Rejected by rule</div><div>${esc(JSON.stringify(gw.rateLimiter.rejectedByRule || {}))}</div><div>Baseline mode</div><div>${gw.baseline}</div></div></div>
      </div>`;
    document.querySelectorAll('[data-add]').forEach(b => b.onclick = () => guard(async () => {
      clearInterval(pollTimer);
      b.disabled = true;
      b.textContent = 'Rebalancing...';
      const r = await API.post('/api/system/shards', { shard: b.dataset.add });
      toast(`Moved ${r.employeesMoved} of ${r.employeesScanned} employees (${r.employeesMovedPercent.toFixed(1)}%; ideal ${r.idealPercent.toFixed(1)}%)`);
      pollTimer = setInterval(() => draw().catch(() => {}), 2000);
      draw().then(() => { const p = document.getElementById('rebal'); p.style.display = 'block'; p.textContent = JSON.stringify(r, null, 2); });
    }));
  };
  await draw();
  pollTimer = setInterval(() => draw().catch(() => {}), 2000);
}

router();
