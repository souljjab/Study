// 몰입 — 첨부 디자인(Focus App, Nocturne)을 그대로 옮긴 화면. 상태와 차단은 Go 엔진이 맡는다.
import { html, render, useState, useEffect, useRef } from './vendor/preact-htm.js';

// ── 백엔드 연결: Windows 창은 WebView2 바인딩(window.api), 개발 서버는 HTTP ──
const native = typeof window.api === 'function';
async function call(action, payload = {}) {
  if (native) return window.api(action, JSON.stringify(payload));
  const r = await fetch('/api', { method: 'POST', body: JSON.stringify({ action, payload }) });
  return r.json();
}

// ── 디자인과 같은 헬퍼 ──
const SITE_CATS = ['기타', 'SNS', '영상', '커뮤니티', '쇼핑', '뉴스', '게임'];
const pad = (n) => String(n).padStart(2, '0');
const fmt = (s) => (s >= 3600 ? `${Math.floor(s / 3600)}:${pad(Math.floor((s % 3600) / 60))}:${pad(s % 60)}` : `${pad(Math.floor(s / 60))}:${pad(s % 60)}`);
const dur = (s) => { const h = Math.floor(s / 3600), m = Math.floor((s % 3600) / 60); return h ? `${h}시간 ${m}분` : `${m}분`; };
const sw = (on) => on
  ? { bg: 'var(--color-accent-800)', border: 'var(--color-accent)', knob: 'var(--color-accent-300)', left: '18px', bigLeft: '23px', glow: '0 0 10px var(--color-accent)' }
  : { bg: 'var(--color-neutral-900)', border: 'var(--color-divider)', knob: 'var(--color-neutral-500)', left: '2px', bigLeft: '3px', glow: 'none' };
const tile = (on) => on
  ? { border: 'var(--color-accent)', bg: 'var(--color-accent-900)', color: 'var(--color-accent-200)' }
  : { border: 'var(--color-divider)', bg: 'transparent', color: 'var(--color-neutral-400)' };
const DAYS = ['일', '월', '화', '수', '목', '금', '토'];
const fadeRule = 'linear-gradient(to right,transparent,var(--color-divider) 48px,var(--color-divider) calc(100% - 48px),transparent) no-repeat top / 100% 1px';

function Switch({ on, onClick, label, big }) {
  const s = sw(on);
  if (big) {
    return html`<button onClick=${onClick} aria-label=${label} style=${`width:44px;height:24px;flex:none;border-radius:999px;border:1px solid ${s.border};background:${s.bg};position:relative;cursor:pointer;padding:0;`}>
      <span style=${`position:absolute;top:3px;left:${s.bigLeft};width:16px;height:16px;border-radius:50%;background:${s.knob};box-shadow:${s.glow};transition:left .15s;`}></span>
    </button>`;
  }
  return html`<button onClick=${onClick} aria-label=${label} style=${`width:36px;height:20px;flex:none;border-radius:999px;border:1px solid ${s.border};background:${s.bg};position:relative;cursor:pointer;padding:0;display:block;`}>
    <span style=${`position:absolute;top:2px;left:${s.left};width:14px;height:14px;border-radius:50%;background:${s.knob};transition:left .15s;`}></span>
  </button>`;
}

// 목록 이름: 입력 중엔 로컬, 포커스를 잃거나 Enter면 저장
function TitleInput({ value, onCommit, size = 28, maxWidth = 420 }) {
  const [v, setV] = useState(value);
  useEffect(() => setV(value), [value]);
  return html`<input class="title-input" value=${v} onInput=${(e) => setV(e.target.value)}
    onBlur=${() => v.trim() && v !== value && onCommit(v)} onKeyDown=${(e) => e.key === 'Enter' && e.target.blur()}
    aria-label="목록 이름"
    style=${`font-family:var(--font-heading);font-weight:500;font-size:${size}px;letter-spacing:-0.015em;color:var(--color-text);background:transparent;border:1px solid transparent;border-radius:var(--radius-md);padding:0 var(--space-2);margin-left:calc(var(--space-2) * -1);width:100%;max-width:${maxWidth}px;`} />`;
}

// "9:30", "930", "0930", "09:30" → "09:30". 올바른 시각이 아니면 null.
export function normalizeTime(raw) {
  const t = String(raw).trim();
  const m = t.match(/^(\d{1,2}):(\d{2})$/) || t.match(/^(\d{1,2})(\d{2})$/);
  if (!m) return null;
  const h = Number(m[1]), mi = Number(m[2]);
  return h <= 23 && mi <= 59 ? `${pad(h)}:${pad(mi)}` : null;
}

// 24시간제로 고정한 시간 입력칸(기기 언어에 따라 오전/오후로 바뀌지 않게 직접 입력받는다).
function TimeText({ value, onCommit, onInvalid }) {
  const [v, setV] = useState(value);
  useEffect(() => setV(value), [value]);
  const commit = () => {
    const n = normalizeTime(v);
    if (n === null) { setV(value); onInvalid(); return; }
    setV(n);
    if (n !== value) onCommit(n);
  };
  return html`<input class="input" value=${v} inputmode="numeric" maxlength="5" placeholder="09:00" aria-label="시간 (24시간제)"
    onInput=${(e) => setV(e.target.value.replace(/[^0-9:]/g, ''))} onBlur=${commit} onKeyDown=${(e) => e.key === 'Enter' && e.target.blur()}
    style="width:74px;min-height:36px;font-size:18px;font-variant-numeric:tabular-nums;text-align:center;padding:2px 8px;" />`;
}

function App() {
  const [S, setS] = useState(null);
  const [view, setView] = useState('focus');
  const [viewPreset, setViewPreset] = useState(null);
  const [listTab, setListTab] = useState('block');
  const [newDomain, setNewDomain] = useState('');
  const [newCat, setNewCat] = useState('기타');
  const [q, setQ] = useState('');
  const [addError, setAddError] = useState('');
  const [newAllow, setNewAllow] = useState('');
  const [allowError, setAllowError] = useState('');
  const [dialog, setDialog] = useState(null);
  const pending = useRef(null);
  const [onboarding, setOnboarding] = useState(false);
  const [obStep, setObStep] = useState(0);
  const [obCats, setObCats] = useState(['SNS', '영상', '커뮤니티']);
  const [obMode, setObMode] = useState('pomo');
  const [blockPage, setBlockPage] = useState(null); // { domain, preview }
  const [schedError, setSchedError] = useState('');
  const [rev, setRev] = useState(0); // 대화상자 취소 시 시간 입력칸을 되돌리려고 다시 그린다
  const [unlockForm, setUnlockForm] = useState(null); // 긴급 잠금 해제 입력 { reason, min, custom, error }
  const seenOnboard = useRef(false);

  const apply = (r) => { if (r && r.state) setS(r.state); return r; };

  // 상태 받기: Windows는 Go가 1초마다 밀어 주고, 개발 서버는 1초마다 가져온다.
  useEffect(() => {
    window.__push = (st) => setS(st);
    window.__event = (ev) => handleEvent(ev);
    call('state').then(apply);
    if (!native) {
      const iv = setInterval(async () => {
        const r = await (await fetch('/api/poll')).json();
        setS(r.state);
        r.events.forEach((ev) => window.__event(ev));
      }, 1000);
      return () => clearInterval(iv);
    }
  }, []);
  const stateRef = useRef(null);
  stateRef.current = S;

  useEffect(() => {
    if (S && !seenOnboard.current) {
      seenOnboard.current = true;
      if (!S.onboarded) setOnboarding(true);
    }
  }, [S]);

  // 대화상자 대기 카운트다운
  useEffect(() => {
    if (!dialog || dialog.count <= 0) return;
    const t = setTimeout(() => setDialog((d) => (d ? { ...d, count: d.count - 1 } : d)), 1000);
    return () => clearTimeout(t);
  }, [dialog]);

  if (!S) return html`<div style="min-height:100vh;background:var(--color-bg);"></div>`;

  const st = S.settings, t = S.timer;
  const enf = S.enforcing;
  const CAT_INFO = Object.fromEntries(S.cats.map((c) => [c.name, c]));
  const CATS = S.cats.map((c) => c.name);
  const presetById = (id) => S.presets.find((p) => p.id === id);
  const active = presetById(S.activePreset) || S.presets[0];
  const viewP = presetById(viewPreset ?? S.activePreset) || S.presets[0];
  const blockedCount = (p) => p.cats.reduce((a, c) => a + (CAT_INFO[c]?.n || 0), 0) + p.sites.filter((x) => x.blocked).length;
  const blocked = blockedCount(active);
  const isBreak = t.phase === 'break';
  const isWatch = t.mode === 'watch';
  const progress = isWatch ? (t.elapsed % 3600) / 3600 : (t.total ? 1 - t.remaining / t.total : 0);
  const now = new Date(S.now);

  function showLocked() {
    pending.current = null;
    setDialog({ locked: true, title: '', body: '', count: 0, total: 0 });
  }
  // 백엔드 호출. 잠금 거부면 잠금 안내를 띄운다.
  async function act(action, payload, onError) {
    const r = apply(await call(action, payload));
    if (r && r.error) {
      if (r.error === 'locked') showLocked();
      else if (onError) onError(r.error);
    }
    return r;
  }
  // 디자인의 guard: 정책에 따라 바로 실행 / 대기 후 실행 / 잠금 안내
  function guard(title, body, fn) {
    if (!enf || st.policy === 'instant') return fn();
    const locked = S.locked;
    pending.current = fn;
    setDialog({ title, body, locked, count: locked ? 0 : st.waitSec, total: st.waitSec });
  }

  function handleEvent(ev) {
    const cur = stateRef.current;
    if (ev.type === 'blocked') setBlockPage({ domain: ev.domain, preview: false });
    if (ev.type === 'quitRequest') {
      const s = cur?.settings;
      const fn = () => act('quit');
      if (!cur?.enforcing || s.policy === 'instant') return fn();
      const locked = cur.locked;
      pending.current = fn;
      setDialog({ title: '프로그램을 끝낼까요?', body: '끝내면 모든 차단이 풀려요.', locked, count: locked ? 0 : s.waitSec, total: s.waitSec });
    }
  }

  window.__event = handleEvent;

  // ── 동작 ──
  const toggleRun = () => act('toggleRun');
  function reset() {
    if (isWatch) {
      const fn = () => act('reset');
      return S.focusOn && st.autoLink ? guard('집중을 마칠까요?', '기록을 저장하고 집중 모드를 꺼요. 차단이 풀려요.', fn) : fn();
    }
    if (st.autoLink && S.focusOn) guard('타이머를 초기화할까요?', '초기화하면 집중 모드도 함께 꺼지고 차단이 풀려요.', () => act('reset'));
    else act('reset');
  }
  function skip() {
    if (t.mode !== 'pomo') return;
    if (t.phase === 'focus') guard('휴식으로 넘어갈까요?', '남은 집중 시간을 건너뛰고 휴식을 시작해요.', () => act('skip'));
    else act('skip');
  }
  function toggleFocus() {
    if (!S.focusOn) return act('toggleFocus');
    guard('집중 모드를 끌까요?', '모든 차단이 즉시 풀려요.', () => act('toggleFocus'));
  }
  function toggleCat(p, c) {
    const on = p.cats.includes(c);
    const fn = () => act('toggleCat', { pid: p.id, cat: c });
    if (!on || !S.enforcedPresets.includes(p.id)) return fn();
    guard(`${c} 차단 해제`, `${CAT_INFO[c].n}개 사이트에 바로 접속할 수 있게 돼요.`, fn);
  }
  function toggleSite(p, x) {
    const fn = () => act('toggleSite', { pid: p.id, id: x.id });
    if (!x.blocked || !S.enforcedPresets.includes(p.id)) return fn();
    guard(`${x.domain} 차단 해제`, '해제하면 이 사이트에 바로 접속할 수 있어요.', fn);
  }
  function removeSite(p, x) {
    const fn = () => act('removeSite', { pid: p.id, id: x.id });
    if (!x.blocked || !S.enforcedPresets.includes(p.id)) return fn();
    guard(`${x.domain} 삭제`, '차단 중인 사이트예요. 지우면 바로 접속할 수 있어요.', fn);
  }
  function setAll(v) {
    const fn = () => act('setAll', { pid: viewP.id, on: v });
    if (v || !S.enforcedPresets.includes(viewP.id)) return fn();
    guard('모두 해제할까요?', '이 목록의 카테고리와 사이트 차단이 한꺼번에 풀려요.', fn);
  }
  async function addSite() {
    const r = await act('addSite', { pid: viewP.id, domain: newDomain, cat: newCat }, setAddError);
    if (r && !r.error) { setNewDomain(''); setAddError(''); }
  }
  async function addAllow() {
    const fn = async () => {
      const r = await act('addAllow', { pid: viewP.id, domain: newAllow }, setAllowError);
      if (r && !r.error) { setNewAllow(''); setAllowError(''); }
    };
    if (!S.enforcedPresets.includes(viewP.id)) return fn();
    guard(`${newAllow.trim() || '주소'} 허용`, '허용 목록에 추가하면 집중 중에도 열려요.', fn);
  }
  async function addPreset() {
    const r = await act('addPreset');
    if (r && r.state) { const ps = r.state.presets; setViewPreset(ps[ps.length - 1].id); setListTab('block'); }
  }
  function setActive(id) {
    const fn = () => act('setActive', { pid: id });
    if (id === S.activePreset) return;
    guard('적용할 목록을 바꿀까요?', '목록을 바꾸면 지금 막고 있던 일부 사이트가 풀릴 수 있어요.', fn);
  }
  const setSetting = (patch) => act('settings', { patch });
  // ── 긴급 잠금 해제 ──
  function openUnlock() {
    pending.current = null;
    setDialog(null);
    setUnlockForm({ reason: '', min: 10, custom: '', error: '' });
  }
  async function submitUnlock() {
    const f = unlockForm;
    const min = f.min > 0 ? f.min : parseInt(f.custom, 10) || 0;
    const r = await act('emergencyUnlock', { reason: f.reason, min }, (e) => setUnlockForm((x) => ({ ...x, error: e })));
    if (r && !r.error) setUnlockForm(null);
  }
  const endUnlock = () => act('endUnlock');
  // ── 예약 ──
  const schedGuard = (sc, title, body, fn) => (sc.active ? guard(title, body, fn) : fn());
  const addSchedule = (tpl = {}) => act('addSchedule', { name: tpl.name || '', sched: tpl.name ? { days: tpl.days, start: tpl.start, end: tpl.end } : null }, setSchedError).then((r) => r && !r.error && setSchedError(''));
  function updateSchedule(sc, patch, harmless) {
    const fn = () => act('updateSchedule', { id: sc.id, sched: patch }, setSchedError).then((r) => r && !r.error && setSchedError(''));
    if (harmless) return fn();
    schedGuard(sc, '예약을 바꿀까요?', '지금 적용 중인 예약이에요. 바꾸면 일부 차단이 바로 풀릴 수 있어요.', fn);
  }
  const removeSchedule = (sc) => schedGuard(sc, `${sc.name} 삭제`, '지금 적용 중인 예약이에요. 지우면 바로 풀려요.', () => act('removeSchedule', { id: sc.id }));
  function finishOnboarding() {
    act('finishOnboarding', { cats: obCats, mode: obMode, start: true });
    setOnboarding(false); setObStep(0); setView('focus');
  }
  function skipOnboarding() { act('skipOnboarding'); setOnboarding(false); setObStep(0); }
  function closeBlockPage() {
    const wasReal = blockPage && !blockPage.preview;
    setBlockPage(null);
    if (wasReal && native) call('hideWindow');
  }
  function bpAllow() {
    const d = blockPage?.domain || 'youtube.com';
    guard(`${d} 허용`, '허용 목록에 추가하면 집중 중에도 열려요.', async () => {
      const r = await act('addAllow', { domain: d }, (e) => setAllowError(e));
      if (r && !r.error) setBlockPage(null);
    });
  }

  // ── 화면 값 ──
  const navItem = (key, label, icon, badge = '') => ({
    key, label, icon, badge,
    bg: view === key ? 'var(--color-accent-900)' : 'transparent',
    color: view === key ? 'var(--color-accent-200)' : 'var(--color-neutral-400)',
  });
  const activeScheds = S.schedules.filter((x) => x.active);
  const schedEnd = activeScheds.map((x) => x.end).sort().pop();
  const rankNext = (n) => (n.startsWith('오늘') ? '0' : n.startsWith('내일') ? '1' : '2') + n.slice(-5);
  const upcoming = S.schedules.filter((x) => x.next).sort((a, b) => rankNext(a.next).localeCompare(rankNext(b.next)))[0];
  const schedSummary = activeScheds.length ? `지금 차단 중 · ${schedEnd}까지` : upcoming ? `다음 · ${upcoming.next}` : '없음';
  const nav = [navItem('focus', '집중', 'ph-timer'), navItem('list', '차단 목록', 'ph-list-checks', String(S.presets.length)), navItem('schedule', '예약', 'ph-calendar-check', S.schedules.length ? String(S.schedules.length) : ''), navItem('stats', '기록', 'ph-chart-bar'), navItem('settings', '설정', 'ph-gear-six')];
  const unlocking = S.unlockUntil > 0;
  const unlockLeft = Math.max(0, Math.round((S.unlockUntil - S.now) / 1000));
  const currentUnlock = unlocking ? S.unlocks.find((u) => u.ongoing) : null;
  const unlockWait = S.nextUnlockAt > S.now; // 1시간에 한 번 제한으로 기다리는 중
  const unlockWaitAt = unlockWait ? (() => { const d = new Date(S.nextUnlockAt); return `${pad(d.getHours())}:${pad(d.getMinutes())}`; })() : '';
  const canUnlock = !unlocking && (S.locked || S.enforcing);
  const unlockBtnLabel = unlockWait ? `긴급 해제 · ${unlockWaitAt}부터` : '긴급 잠금 해제';
  const sideStatus = unlocking ? '긴급 해제 중' : S.focusOn ? (enf ? '집중 모드 켜짐' : '휴식 중 · 차단 해제') : activeScheds.length ? '예약 차단 중' : '집중 모드 꺼짐';
  const sideTime = isWatch ? (t.elapsed ? fmt(t.elapsed) : '—') : (t.running || t.remaining < t.total ? fmt(t.remaining) : '—');
  const sideMeta = unlocking
    ? `${fmt(unlockLeft)} 뒤 다시 차단`
    : !S.focusOn && activeScheds.length
    ? `${activeScheds[0].name} · ${schedEnd}까지`
    : enf ? `${active.name} · ${blocked}개 차단 중` : `${active.name} · ${blocked}개 대기`;
  const dotColor = enf ? 'var(--color-accent)' : 'var(--color-neutral-600)';
  const todayLabel = `${now.getMonth() + 1}월 ${now.getDate()}일 ${DAYS[now.getDay()]}요일`;
  const totalSessions = S.sessions.length;
  const todaySummary = `오늘 ${dur(S.todayFocus)} 집중 · 세션 ${totalSessions}회`;
  const playLabel = t.running ? '일시정지' : (isWatch ? t.elapsed > 0 : t.remaining < t.total && t.remaining > 0) ? '계속' : '집중 시작';
  const timerSub = t.mode === 'pomo'
    ? (isBreak ? `${Math.round(t.total / 60)}분 쉬고 다시 시작해요` : `세션 ${t.session} / 4 · 집중 ${st.focusMin}분`)
    : isWatch ? '정지하면 기록에 저장돼요' : `${t.freeMin}분 집중`;
  const timeVal = isWatch ? t.elapsed : t.remaining;
  const focusDesc = S.unlockUntil > 0
    ? '긴급 잠금 해제 중이에요. 정한 시간이 지나면 다시 막아요.'
    : S.focusOn
    ? (enf ? `${blocked}개 사이트를 막고 있어요. 끄면 바로 모두 풀려요.` : '휴식 시간이라 차단이 잠시 풀렸어요.')
    : activeScheds.length
      ? `${activeScheds[0].name} 예약으로 차단 중이에요. ${schedEnd}에 저절로 풀려요.`
      : '타이머를 시작하면 자동으로 켜져요. 지금 바로 켜서 직접 차단할 수도 있어요.';
  const catCard = (p) => (c) => {
    const on = p.cats.includes(c);
    return { name: c, ...CAT_INFO[c], ...tile(on), on, state: on ? '차단' : '허용', toggle: () => toggleCat(p, c) };
  };
  const ql = q.trim().toLowerCase();
  const visible = viewP.sites.filter((x) => x.domain.includes(ql));
  const week = S.week;
  const maxW = Math.max(...week, 60);
  const topMax = S.top.length ? S.top[0].count : 1;

  // ── 화면 ──
  const sidebar = html`
  <aside style="display:flex;flex-direction:column;gap:var(--space-6);padding:var(--space-8) var(--space-4);background:linear-gradient(180deg,var(--color-surface),var(--color-bg));position:sticky;top:0;height:100vh;">
    <div style="display:flex;align-items:center;gap:var(--space-3);padding:0 var(--space-2);">
      <div style="width:28px;height:28px;border-radius:var(--radius-md);display:grid;place-items:center;box-shadow:inset 0 0 0 1px var(--color-accent);color:var(--color-accent);"><i class="ph ph-moon-stars" style="font-size:16px;"></i></div>
      <div style="font-family:var(--font-heading);font-weight:500;font-size:16px;">몰입</div>
    </div>
    <nav style="display:flex;flex-direction:column;gap:var(--space-1);">
      ${nav.map((n) => html`
        <button onClick=${() => { setView(n.key); if (n.key === 'list') setViewPreset(viewPreset ?? S.activePreset); }} style=${`display:flex;align-items:center;gap:var(--space-3);padding:var(--space-3);border:0;border-radius:var(--radius-md);background:${n.bg};color:${n.color};font:inherit;font-size:14px;cursor:pointer;text-align:left;`}>
          <i class=${`ph ${n.icon}`} style="font-size:18px;"></i>
          <span style="flex:1;">${n.label}</span>
          <span style="font-size:12px;opacity:.6;">${n.badge}</span>
        </button>`)}
    </nav>
    <div style="margin-top:auto;display:flex;flex-direction:column;gap:var(--space-2);padding:var(--space-4);border-radius:var(--radius-md);box-shadow:var(--shadow-sm);">
      <div style="display:flex;align-items:center;gap:var(--space-2);font-size:12px;">
        <span style=${`width:7px;height:7px;border-radius:50%;background:${dotColor};box-shadow:0 0 8px ${dotColor};`}></span>
        <span>${sideStatus}</span>
      </div>
      <div style="font-size:22px;font-variant-numeric:tabular-nums;letter-spacing:-0.01em;">${sideTime}</div>
      <div class="text-muted" style="font-size:12px;">${sideMeta}</div>
    </div>
  </aside>`;

  const focusView = html`
    <header style="display:flex;align-items:flex-end;gap:var(--space-4);margin-bottom:var(--space-8);flex-wrap:wrap;">
      <div style="flex:1;min-width:0;">
        <div class="card-kicker" style="margin-bottom:var(--space-2);">${todayLabel}</div>
        <h2 style="margin:0;font-size:28px;">오늘도 한 블록씩.</h2>
      </div>
      <div class="text-muted" style="font-size:13px;">${todaySummary}</div>
    </header>
    <div style="display:grid;grid-template-columns:repeat(auto-fit,minmax(340px,1fr));gap:var(--space-6);align-items:start;">
      <section class="card elev-sm" style="padding:var(--space-8);gap:var(--space-6);background:radial-gradient(120% 70% at 20% 0%,var(--color-accent-900),var(--color-surface) 65%);">
        <div style="display:flex;align-items:center;gap:var(--space-3);flex-wrap:wrap;">
          <div style="display:flex;align-items:center;gap:var(--space-2);font-size:14px;"><i class="ph ph-timer" style="font-size:18px;color:var(--color-accent);"></i>집중 타이머</div>
          <div class="seg" style=${`margin-left:auto;opacity:${t.running ? '0.45' : '1'};`}>
            <label class="seg-opt"><input type="radio" name="tmode" checked=${t.mode === 'pomo'} onChange=${() => act('setMode', { mode: 'pomo' })} />뽀모도로</label>
            <label class="seg-opt"><input type="radio" name="tmode" checked=${t.mode === 'free'} onChange=${() => act('setMode', { mode: 'free' })} />자유</label>
            <label class="seg-opt"><input type="radio" name="tmode" checked=${isWatch} onChange=${() => act('setMode', { mode: 'watch' })} />스톱워치</label>
          </div>
        </div>
        <div style="display:grid;place-items:center;padding:var(--space-4) 0;">
          <div style="position:relative;width:272px;height:272px;">
            <svg width="272" height="272" viewBox="0 0 272 272" style="position:absolute;inset:0;transform:rotate(-90deg);overflow:visible;">
              <circle cx="136" cy="136" r="120" fill="none" stroke="var(--color-neutral-800)" stroke-width="4"></circle>
              <circle cx="136" cy="136" r="120" fill="none" stroke=${isBreak && !isWatch ? 'var(--color-neutral-400)' : 'var(--color-accent)'} stroke-width="4" stroke-linecap="round" stroke-dasharray="753.98" stroke-dashoffset=${String(753.98 * (1 - progress))} style="filter:drop-shadow(0 0 10px var(--color-accent-600));transition:stroke-dashoffset .4s linear;"></circle>
            </svg>
            <div style="position:absolute;inset:0;display:flex;flex-direction:column;align-items:center;justify-content:center;gap:var(--space-2);">
              <span class=${isBreak && !isWatch ? 'tag tag-neutral' : 'tag tag-accent'}>${isWatch ? '누적' : isBreak ? '휴식' : '집중'}</span>
              <div style=${`font-family:var(--font-heading);font-weight:500;font-size:${timeVal >= 3600 ? '48px' : '60px'};line-height:1;letter-spacing:-0.03em;font-variant-numeric:tabular-nums;`}>${fmt(timeVal)}</div>
              <div class="text-muted" style="font-size:12px;">${timerSub}</div>
            </div>
          </div>
        </div>
        ${t.mode === 'pomo' && html`
          <div style="display:flex;align-items:center;justify-content:center;gap:var(--space-2);">
            ${[1, 2, 3, 4].map((i) => html`<span style=${`width:22px;height:4px;border-radius:2px;background:${i < t.session ? 'var(--color-accent)' : i === t.session ? 'var(--color-accent-700)' : 'var(--color-neutral-800)'};`}></span>`)}
          </div>`}
        ${t.mode === 'free' && html`
          <div style="display:flex;justify-content:center;gap:var(--space-2);flex-wrap:wrap;">
            ${[25, 50, 90, 120].map((m) => html`
              <button class=${t.freeMin === m ? 'btn btn-primary' : 'btn btn-secondary'} onClick=${() => act('freeMin', { min: m })} disabled=${t.running}>
                ${m >= 60 ? `${Math.floor(m / 60)}시간${m % 60 ? ` ${m % 60}분` : ''}` : `${m}분`}
              </button>`)}
          </div>`}
        <div style="display:flex;justify-content:center;gap:var(--space-3);">
          <button class="btn btn-secondary btn-icon" onClick=${reset} title=${isWatch ? '정지하고 기록' : '초기화'}><i class=${`ph ${isWatch ? 'ph-stop' : 'ph-arrow-counter-clockwise'}`} style="font-size:18px;"></i></button>
          <button class="btn btn-primary" onClick=${toggleRun} style="min-width:148px;height:36px;"><i class=${`ph ${t.running ? 'ph-pause' : 'ph-play'}`} style="font-size:16px;"></i>${playLabel}</button>
          <button class="btn btn-secondary btn-icon" onClick=${skip} disabled=${t.mode !== 'pomo'} title="다음 단계로"><i class="ph ph-skip-forward" style="font-size:18px;"></i></button>
        </div>
      </section>

      <div style="display:flex;flex-direction:column;gap:var(--space-6);">
        <section class="card elev-sm" style="padding:var(--space-6);gap:var(--space-4);">
          <div style="display:flex;align-items:flex-start;gap:var(--space-4);">
            <div style="flex:1;min-width:0;">
              <div class="card-title" style="display:flex;align-items:center;gap:var(--space-2);"><i class="ph ph-shield-check" style="font-size:20px;color:var(--color-accent);"></i>집중 모드</div>
              <p class="card-body" style="margin-top:var(--space-2);">${focusDesc}</p>
            </div>
            <${Switch} big on=${S.focusOn} onClick=${toggleFocus} label="집중 모드" />
          </div>
          <div style=${`display:flex;flex-direction:column;gap:var(--space-3);padding-top:var(--space-4);background:${fadeRule};`}>
            <div style="display:flex;align-items:center;gap:var(--space-3);font-size:13px;">
              <span style="flex:1;">적용할 목록</span>
              <select class="input" value=${String(S.activePreset)} onChange=${(e) => { const id = Number(e.target.value); e.target.value = String(S.activePreset); setActive(id); }} style="width:auto;min-height:30px;padding:3px 8px;font-size:13px;">
                ${S.presets.map((p) => html`<option value=${String(p.id)}>${p.name}</option>`)}
              </select>
            </div>
            <div style="display:flex;align-items:center;gap:var(--space-3);font-size:13px;">
              <span style="flex:1;">휴식 시간</span>
              <span class="text-muted">${st.breakUnblock ? '차단 해제' : '차단 유지'}</span>
            </div>
            <div style="display:flex;align-items:center;gap:var(--space-3);font-size:13px;">
              <span style="flex:1;">예약 차단</span>
              <button class="btn btn-ghost" onClick=${() => setView('schedule')} style=${`font-size:13px;padding:0 var(--space-1);min-height:0;color:${activeScheds.length ? 'var(--color-accent-300)' : 'var(--color-neutral-400)'};`}>${schedSummary} <i class="ph ph-arrow-right"></i></button>
            </div>
            ${canUnlock && html`
              <div style="display:flex;align-items:center;gap:var(--space-3);font-size:13px;">
                <span class="text-muted" style="flex:1;font-size:12px;">${unlockWait ? '긴급 해제는 1시간에 한 번만 할 수 있어요' : '정말 급할 때만 · 최대 30분 · 사유와 시간이 기록돼요'}</span>
                <button class="btn btn-ghost" onClick=${openUnlock} disabled=${unlockWait} style="font-size:13px;padding:0 var(--space-1);min-height:0;color:var(--color-neutral-400);"><i class="ph ph-siren"></i>${unlockBtnLabel}</button>
              </div>`}
          </div>
        </section>

        <section class="card elev-sm" style="padding:var(--space-6);gap:var(--space-4);">
          <div style="display:flex;align-items:center;gap:var(--space-3);">
            <div class="card-title" style="flex:1;">빠른 차단</div>
            <button class="btn btn-ghost" onClick=${() => { setView('list'); setViewPreset(S.activePreset); }} style="font-size:13px;">목록 관리 <i class="ph ph-arrow-right"></i></button>
          </div>
          <div style="display:grid;grid-template-columns:repeat(3,minmax(0,1fr));gap:var(--space-2);">
            ${CATS.map(catCard(active)).map((c) => html`
              <button onClick=${c.toggle} style=${`display:flex;flex-direction:column;align-items:flex-start;gap:var(--space-2);padding:var(--space-3);border-radius:var(--radius-md);border:1px solid ${c.border};background:${c.bg};color:${c.color};font:inherit;cursor:pointer;text-align:left;`}>
                <i class=${`ph ${c.icon}`} style="font-size:18px;"></i>
                <span style="font-size:13px;">${c.name}</span>
                <span style="font-size:11px;opacity:.7;">${c.state}</span>
              </button>`)}
          </div>
        </section>
      </div>
    </div>`;

  const listView = html`
    <div style="display:flex;gap:var(--space-2);flex-wrap:wrap;margin-bottom:var(--space-6);">
      ${S.presets.map((p) => {
        const sel = p.id === viewP.id;
        const c = sel ? { border: 'var(--color-accent)', bg: 'var(--color-accent-900)', color: 'var(--color-accent-200)' } : { border: 'var(--color-divider)', bg: 'transparent', color: 'var(--color-neutral-300)' };
        return html`
        <button onClick=${() => { setViewPreset(p.id); setQ(''); setAddError(''); setAllowError(''); }} style=${`display:inline-flex;align-items:center;gap:var(--space-2);padding:6px 12px;border-radius:var(--radius-md);border:1px solid ${c.border};background:${c.bg};color:${c.color};font:inherit;font-size:13px;cursor:pointer;`}>
          <i class=${`ph ${p.icon}`} style="font-size:15px;"></i>${p.name}
          ${p.id === S.activePreset && html`<span style="width:6px;height:6px;border-radius:50%;background:var(--color-accent);box-shadow:0 0 6px var(--color-accent);"></span>`}
        </button>`;
      })}
      <button class="btn btn-ghost" onClick=${addPreset} style="font-size:13px;"><i class="ph ph-plus"></i>새 목록</button>
    </div>

    <header style="display:flex;align-items:flex-end;gap:var(--space-4);margin-bottom:var(--space-6);flex-wrap:wrap;">
      <div style="flex:1;min-width:240px;">
        <div class="card-kicker" style="margin-bottom:var(--space-2);">${`${blockedCount(viewP)}개 사이트 차단 · 허용 ${viewP.allow.length}개`}</div>
        <${TitleInput} value=${viewP.name} onCommit=${(v) => act('renamePreset', { pid: viewP.id, name: v })} />
      </div>
      <div style="display:flex;gap:var(--space-2);flex-wrap:wrap;">
        ${S.schedules.some((x) => x.active && x.presetId === viewP.id) && html`<span class="tag tag-accent" style="align-self:center;">예약 적용 중</span>`}
        ${viewP.id === S.activePreset
          ? html`<span class="tag tag-accent" style="align-self:center;">집중 모드에 적용 중</span>`
          : html`<button class="btn btn-secondary" onClick=${() => setActive(viewP.id)}><i class="ph ph-check"></i>이 목록 사용</button>
                 <button class="btn btn-ghost btn-icon" title="목록 삭제" onClick=${async () => { const r = await act('deletePreset', { pid: viewP.id }); if (r && !r.error) setViewPreset(S.activePreset); }} style="color:var(--color-neutral-500);"><i class="ph ph-trash" style="font-size:16px;"></i></button>`}
        <button class="btn btn-secondary" onClick=${() => setAll(false)}><i class="ph ph-lock-open"></i>모두 해제</button>
        <button class="btn btn-primary" onClick=${() => setAll(true)}><i class="ph ph-lock"></i>모두 차단</button>
      </div>
    </header>

    <h6 class="text-muted" style="margin-bottom:var(--space-3);">카테고리 묶음</h6>
    <div style="display:grid;grid-template-columns:repeat(auto-fill,minmax(220px,1fr));gap:var(--space-3);margin-bottom:var(--space-8);">
      ${CATS.map(catCard(viewP)).map((c) => html`
        <div class="card" style=${`flex-direction:row;align-items:center;gap:var(--space-3);padding:var(--space-4);box-shadow:0 0 0 1px ${c.border};`}>
          <div style=${`width:32px;height:32px;flex:none;border-radius:var(--radius-md);display:grid;place-items:center;background:var(--color-neutral-900);color:${c.color};`}><i class=${`ph ${c.icon}`} style="font-size:18px;"></i></div>
          <div style="flex:1;min-width:0;">
            <div style="font-size:14px;">${c.name} <span class="text-muted" style="font-size:12px;">${c.n}개</span></div>
            <div class="text-muted" style="font-size:12px;white-space:nowrap;overflow:hidden;text-overflow:ellipsis;">${c.ex}</div>
          </div>
          <${Switch} on=${c.on} onClick=${c.toggle} label="카테고리 차단" />
        </div>`)}
    </div>

    <div style="display:flex;align-items:center;gap:var(--space-3);margin-bottom:var(--space-4);flex-wrap:wrap;">
      <div class="seg">
        <label class="seg-opt"><input type="radio" name="ltab" checked=${listTab === 'block'} onChange=${() => setListTab('block')} /><i class="ph ph-prohibit"></i>차단 사이트 <span style="opacity:.55;">${viewP.sites.length}</span></label>
        <label class="seg-opt"><input type="radio" name="ltab" checked=${listTab === 'allow'} onChange=${() => setListTab('allow')} /><i class="ph ph-check-circle"></i>허용 목록 <span style="opacity:.55;">${viewP.allow.length}</span></label>
      </div>
      ${listTab === 'block' && html`
        <div style="position:relative;flex:0 1 220px;margin-left:auto;">
          <i class="ph ph-magnifying-glass" style="position:absolute;left:10px;top:50%;transform:translateY(-50%);color:var(--color-neutral-500);"></i>
          <input class="input" placeholder="검색" value=${q} onInput=${(e) => setQ(e.target.value)} style="padding-left:32px;" />
        </div>`}
    </div>

    ${listTab === 'block' && html`
      <div style="display:flex;gap:var(--space-3);flex-wrap:wrap;margin-bottom:var(--space-2);">
        <div style="position:relative;flex:1 1 280px;">
          <i class="ph ph-globe" style="position:absolute;left:10px;top:50%;transform:translateY(-50%);color:var(--color-neutral-500);"></i>
          <input class="input" placeholder="차단할 도메인 — 예: namu.wiki" value=${newDomain} onInput=${(e) => { setNewDomain(e.target.value); setAddError(''); }} onKeyDown=${(e) => e.key === 'Enter' && addSite()} style="padding-left:32px;" />
        </div>
        <select class="input" value=${newCat} onChange=${(e) => setNewCat(e.target.value)} style="flex:0 0 130px;width:auto;">
          ${SITE_CATS.map((c) => html`<option value=${c}>${c}</option>`)}
        </select>
        <button class="btn btn-primary" onClick=${addSite} style="min-height:36px;"><i class="ph ph-plus"></i>추가</button>
      </div>
      ${addError && html`<div style="font-size:12px;color:var(--color-accent-300);margin-bottom:var(--space-2);">${addError}</div>`}
      <table class="table" style="margin-top:var(--space-3);">
        <thead><tr><th>사이트</th><th>분류</th><th>상태</th><th style="width:80px;">차단</th><th style="width:48px;"></th></tr></thead>
        <tbody>
          ${visible.map((x) => {
            const e = S.enforcedPresets.includes(viewP.id);
            return html`
            <tr>
              <td><div style="display:flex;align-items:center;gap:var(--space-3);">
                <div style="width:26px;height:26px;flex:none;border-radius:var(--radius-sm);background:var(--color-neutral-900);display:grid;place-items:center;font-size:12px;color:var(--color-neutral-300);">${x.domain[0].toUpperCase()}</div>
                <span>${x.domain}</span>
              </div></td>
              <td class="text-muted" style="font-size:13px;">${x.cat}</td>
              <td>
                ${x.blocked && e && html`<span class="tag tag-accent">차단 중</span>`}
                ${x.blocked && !e && html`<span class="tag tag-neutral">대기</span>`}
                ${!x.blocked && html`<span class="tag" style="box-shadow:inset 0 0 0 1px var(--color-divider);color:var(--color-neutral-400);">허용</span>`}
              </td>
              <td><${Switch} on=${x.blocked} onClick=${() => toggleSite(viewP, x)} label="차단 전환" /></td>
              <td><button class="btn btn-ghost btn-icon" onClick=${() => removeSite(viewP, x)} title="삭제" style="width:30px;height:30px;color:var(--color-neutral-500);"><i class="ph ph-trash" style="font-size:16px;"></i></button></td>
            </tr>`;
          })}
        </tbody>
      </table>
      ${visible.length === 0 && html`<div class="text-muted" style="padding:var(--space-8) var(--space-2);font-size:14px;">직접 추가한 사이트가 없어요. 카테고리 묶음에 없는 사이트를 위에서 추가하세요.</div>`}`}

    ${listTab === 'allow' && html`
      <p class="text-muted" style="font-size:13px;margin-bottom:var(--space-4);">집중 중에도 항상 열리는 주소예요. 카테고리에 들어 있는 도메인을 넣으면 그 도메인만 차단에서 빠져요.</p>
      <div style="display:flex;gap:var(--space-3);flex-wrap:wrap;margin-bottom:var(--space-2);">
        <div style="position:relative;flex:1 1 280px;">
          <i class="ph ph-check-circle" style="position:absolute;left:10px;top:50%;transform:translateY(-50%);color:var(--color-neutral-500);"></i>
          <input class="input" placeholder="예: ko.wikipedia.org" value=${newAllow} onInput=${(e) => { setNewAllow(e.target.value); setAllowError(''); }} onKeyDown=${(e) => e.key === 'Enter' && addAllow()} style="padding-left:32px;" />
        </div>
        <button class="btn btn-primary" onClick=${addAllow} style="min-height:36px;"><i class="ph ph-plus"></i>허용 추가</button>
      </div>
      ${allowError && html`<div style="font-size:12px;color:var(--color-accent-300);margin-bottom:var(--space-2);">${allowError}</div>`}
      <table class="table" style="margin-top:var(--space-3);">
        <thead><tr><th>주소</th><th>범위</th><th style="width:48px;"></th></tr></thead>
        <tbody>
          ${viewP.allow.map((a) => html`
            <tr>
              <td><div style="display:flex;align-items:center;gap:var(--space-3);"><i class="ph ph-check-circle" style="font-size:18px;color:var(--color-accent);"></i>${a.domain}</div></td>
              <td class="text-muted" style="font-size:13px;">사이트 전체</td>
              <td><button class="btn btn-ghost btn-icon" onClick=${() => act('removeAllow', { pid: viewP.id, id: a.id })} title="삭제" style="width:30px;height:30px;color:var(--color-neutral-500);"><i class="ph ph-trash" style="font-size:16px;"></i></button></td>
            </tr>`)}
        </tbody>
      </table>`}`;

  const DAY_ORDER = [1, 2, 3, 4, 5, 6, 0];
  const TEMPLATES = [
    { name: '평일 오전', days: [1, 2, 3, 4, 5], start: '09:00', end: '12:00', icon: 'ph-sun-horizon', ex: '월–금 · 09:00–12:00' },
    { name: '평일 저녁 공부', days: [1, 2, 3, 4, 5], start: '19:00', end: '22:00', icon: 'ph-books', ex: '월–금 · 19:00–22:00' },
    { name: '매일 밤', days: [0, 1, 2, 3, 4, 5, 6], start: '23:00', end: '07:00', icon: 'ph-moon', ex: '매일 · 23:00–다음 날 07:00' },
  ];
  const policyName = { instant: '바로 해제', wait: '대기 후 해제', lock: '잠금' }[st.policy];
  const schedTag = (sc) => {
    if (sc.active) return html`<span class="tag tag-accent">지금 차단 중 · ${sc.end}까지</span>`;
    if (!sc.enabled) return html`<span class="tag" style="box-shadow:inset 0 0 0 1px var(--color-divider);color:var(--color-neutral-400);">꺼짐</span>`;
    if (!sc.days.length) return html`<span class="tag tag-neutral">요일을 고르세요</span>`;
    return html`<span class="tag tag-neutral">다음 · ${sc.next}</span>`;
  };
  const timeInput = (sc, key) => html`<${TimeText} key=${`${sc.id}-${key}-${sc[key]}-${rev}`} value=${sc[key]}
    onCommit=${(t) => updateSchedule(sc, { [key]: t })} onInvalid=${() => setSchedError('시간은 24시간제로 입력하세요. 예: 09:00, 23:30')} />`;
  const scheduleCard = (sc) => {
    const overnight = sc.end < sc.start;
    return html`
    <section class="card elev-sm" key=${sc.id} style=${`padding:var(--space-6);gap:var(--space-4);${sc.active ? 'box-shadow:0 0 0 1px var(--color-accent),0 0 24px var(--color-accent-900);background:radial-gradient(120% 90% at 0% 0%,var(--color-accent-900),var(--color-surface) 60%);' : ''}`}>
      <div style="display:flex;align-items:center;gap:var(--space-3);flex-wrap:wrap;">
        <i class=${`ph ${sc.active ? 'ph-shield-check' : 'ph-calendar-check'}`} style=${`font-size:20px;color:${sc.active ? 'var(--color-accent)' : 'var(--color-neutral-500)'};`}></i>
        <div style="flex:1;min-width:160px;"><${TitleInput} size=${18} maxWidth=${280} value=${sc.name} onCommit=${(v) => updateSchedule(sc, { name: v }, true)} /></div>
        ${schedTag(sc)}
        <${Switch} on=${sc.enabled} label="예약 켜기" onClick=${() => (sc.enabled ? updateSchedule(sc, { enabled: false }) : updateSchedule(sc, { enabled: true }, true))} />
        <button class="btn btn-ghost btn-icon" onClick=${() => removeSchedule(sc)} title="삭제" style="width:30px;height:30px;color:var(--color-neutral-500);"><i class="ph ph-trash" style="font-size:16px;"></i></button>
      </div>
      <div style=${`display:flex;align-items:center;gap:var(--space-6);flex-wrap:wrap;padding-top:var(--space-4);background:${fadeRule};`}>
        <div style="display:flex;align-items:center;gap:var(--space-2);">
          ${timeInput(sc, 'start')}
          <span class="text-muted">–</span>
          ${timeInput(sc, 'end')}
          ${overnight && html`<span class="text-muted" style="font-size:12px;">다음 날</span>`}
        </div>
        <div style="display:flex;gap:var(--space-1);" role="group" aria-label="요일">
          ${DAY_ORDER.map((d) => {
            const on = sc.days.includes(d); const k = tile(on);
            const next = on ? sc.days.filter((x) => x !== d) : [...sc.days, d];
            return html`<button onClick=${() => updateSchedule(sc, { days: next }, !on)} aria-pressed=${on} style=${`width:32px;height:32px;border-radius:var(--radius-md);border:1px solid ${k.border};background:${k.bg};color:${k.color};font:inherit;font-size:13px;cursor:pointer;`}>${DAYS[d]}</button>`;
          })}
        </div>
        <div style="display:flex;align-items:center;gap:var(--space-2);font-size:13px;margin-left:auto;">
          <span class="text-muted">막을 목록</span>
          <select class="input" key=${`${sc.id}-p-${sc.presetId}-${rev}`} value=${String(sc.presetId)} onChange=${(e) => updateSchedule(sc, { presetId: Number(e.target.value) })} style="width:auto;min-height:30px;padding:3px 8px;font-size:13px;">
            ${S.presets.map((p) => html`<option value=${String(p.id)}>${p.name}</option>`)}
          </select>
        </div>
      </div>
    </section>`;
  };
  const templateTiles = html`
    <div style="display:grid;grid-template-columns:repeat(auto-fill,minmax(220px,1fr));gap:var(--space-3);">
      ${TEMPLATES.map((tp) => html`
        <button onClick=${() => addSchedule(tp)} style="display:flex;flex-direction:column;align-items:flex-start;gap:var(--space-2);padding:var(--space-4);border-radius:var(--radius-md);border:1px solid var(--color-divider);background:transparent;color:var(--color-neutral-300);font:inherit;cursor:pointer;text-align:left;">
          <i class=${`ph ${tp.icon}`} style="font-size:20px;color:var(--color-accent);"></i>
          <span style="font-size:14px;color:var(--color-text);">${tp.name}</span>
          <span style="font-size:12px;opacity:.7;">${tp.ex}</span>
        </button>`)}
    </div>`;
  const scheduleView = html`
    <header style="display:flex;align-items:flex-end;gap:var(--space-4);margin-bottom:var(--space-6);flex-wrap:wrap;">
      <div style="flex:1;min-width:0;">
        <div class="card-kicker" style="margin-bottom:var(--space-2);">자동 차단</div>
        <h2 style="margin:0;font-size:28px;">예약</h2>
      </div>
      <button class="btn btn-primary" onClick=${() => addSchedule()}><i class="ph ph-plus"></i>새 예약</button>
    </header>
    <p class="text-muted" style="font-size:14px;max-width:640px;margin-bottom:var(--space-6);">정한 요일·시간이 되면 타이머 없이도 고른 목록으로 차단이 켜지고, 끝나면 저절로 풀려요. 집중 중 해제 방식(${policyName})이 예약에도 똑같이 적용돼요.</p>
    ${!st.launch && html`
      <div class="card" style="flex-direction:row;align-items:center;gap:var(--space-3);padding:var(--space-4);margin-bottom:var(--space-6);box-shadow:0 0 0 1px var(--color-divider);">
        <i class="ph ph-info" style="font-size:18px;color:var(--color-accent);"></i>
        <span style="flex:1;font-size:13px;">예약은 몰입이 실행 중일 때만 동작해요. '컴퓨터 시작 시 실행'을 켜 두는 게 좋아요.</span>
        <button class="btn btn-secondary" onClick=${() => setSetting({ launch: true })}>켜기</button>
      </div>`}
    ${schedError && html`<div style="font-size:12px;color:var(--color-accent-300);margin-bottom:var(--space-3);">${schedError}</div>`}
    ${S.schedules.length === 0
      ? html`
        <div style="display:flex;flex-direction:column;gap:var(--space-4);">
          <div class="text-muted" style="font-size:14px;">아직 예약이 없어요. 자주 쓰는 시간대로 시작해 보세요.</div>
          ${templateTiles}
        </div>`
      : html`
        <div style="display:flex;flex-direction:column;gap:var(--space-4);margin-bottom:var(--space-8);">${S.schedules.map(scheduleCard)}</div>
        <h6 class="text-muted" style="margin-bottom:var(--space-3);">빠른 추가</h6>
        ${templateTiles}`}`;

  const statNums = [
    { label: '오늘 집중', value: dur(S.todayFocus) },
    { label: '이번 주 합계', value: dur(week.reduce((a, b) => a + b, 0) * 60) },
    { label: '오늘 세션', value: `${totalSessions}회` },
    { label: '막은 접속 시도', value: `${S.todayHits}회` },
  ];
  const statsView = html`
    <header style="margin-bottom:var(--space-8);">
      <div class="card-kicker" style="margin-bottom:var(--space-2);">최근 7일</div>
      <h2 style="margin:0;font-size:28px;">기록</h2>
    </header>
    <div style="display:grid;grid-template-columns:repeat(auto-fill,minmax(200px,1fr));gap:var(--space-6);margin-bottom:var(--space-8);">
      ${statNums.map((n) => html`
        <div style="display:flex;flex-direction:column;gap:var(--space-2);">
          <div class="text-muted" style="font-size:12px;">${n.label}</div>
          <div style="font-family:var(--font-heading);font-weight:500;font-size:32px;letter-spacing:-0.02em;font-variant-numeric:tabular-nums;">${n.value}</div>
          <div style="width:24px;height:2px;background:var(--color-accent);"></div>
        </div>`)}
    </div>
    <div style="display:grid;grid-template-columns:repeat(auto-fit,minmax(320px,1fr));gap:var(--space-6);margin-bottom:var(--space-8);">
      <section class="card elev-sm" style="padding:var(--space-6);gap:var(--space-6);">
        <div class="card-title">하루 집중 시간</div>
        <div style="display:grid;grid-template-columns:repeat(7,minmax(0,1fr));gap:var(--space-3);align-items:end;height:180px;">
          ${week.map((m, i) => {
            const today = i === 6;
            const d = new Date(now); d.setDate(d.getDate() - (6 - i));
            return html`
            <div style="display:flex;flex-direction:column;align-items:center;justify-content:flex-end;gap:var(--space-2);height:100%;">
              <div style=${`font-size:11px;color:${today ? 'var(--color-accent-300)' : 'var(--color-neutral-500)'};font-variant-numeric:tabular-nums;`}>${m ? `${Math.floor(m / 60)}:${pad(m % 60)}` : '—'}</div>
              <div style=${`width:100%;max-width:28px;height:${Math.round((m / maxW) * 120)}px;min-height:2px;border-radius:4px 4px 2px 2px;background:${today ? 'var(--color-accent)' : 'var(--color-accent-800)'};box-shadow:${today ? '0 0 12px var(--color-accent-600)' : 'none'};`}></div>
              <div style=${`font-size:12px;color:${today ? 'var(--color-text)' : 'var(--color-neutral-500)'};`}>${today ? '오늘' : DAYS[d.getDay()]}</div>
            </div>`;
          })}
        </div>
      </section>
      <section class="card elev-sm" style="padding:var(--space-6);gap:var(--space-4);">
        <div class="card-title">가장 많이 막은 사이트</div>
        <div style="display:flex;flex-direction:column;gap:var(--space-4);">
          ${S.top.length === 0 && html`<div class="text-muted" style="font-size:13px;">${S.attemptsOn ? '아직 막은 접속이 없어요.' : '접속 시도 집계를 켜지 못했어요. 443·80 포트를 다른 프로그램이 쓰고 있을 수 있어요.'}</div>`}
          ${S.top.map((x) => html`
            <div style="display:flex;flex-direction:column;gap:var(--space-2);">
              <div style="display:flex;font-size:13px;"><span style="flex:1;">${x.domain}</span><span class="text-muted" style="font-variant-numeric:tabular-nums;">${x.count}회</span></div>
              <div style="height:3px;border-radius:2px;background:var(--color-neutral-800);"><div style=${`height:100%;width:${(x.count / topMax) * 100}%;border-radius:2px;background:var(--color-accent-600);`}></div></div>
            </div>`)}
        </div>
      </section>
    </div>
    <h6 class="text-muted" style="margin-bottom:var(--space-3);">오늘의 세션</h6>
    <table class="table">
      <thead><tr><th>시작</th><th>방식</th><th>집중 시간</th><th>막은 접속</th></tr></thead>
      <tbody>
        ${[...S.sessions].reverse().map((r) => html`
          <tr><td style="font-variant-numeric:tabular-nums;">${r.time}</td><td class="text-muted">${r.mode}</td><td style="font-variant-numeric:tabular-nums;">${dur(r.dur)}</td><td class="text-muted" style="font-variant-numeric:tabular-nums;">${r.blocked}회</td></tr>`)}
      </tbody>
    </table>
    ${S.sessions.length === 0 && html`<div class="text-muted" style="padding:var(--space-6) var(--space-2);font-size:14px;">오늘 끝낸 세션이 아직 없어요.</div>`}
    <div style="display:flex;align-items:baseline;gap:var(--space-3);margin:var(--space-8) 0 var(--space-3);">
      <h6 class="text-muted" style="margin:0;flex:1;">긴급 잠금 해제 기록</h6>
      <span class="text-muted" style="font-size:12px;font-variant-numeric:tabular-nums;">최근 7일 ${S.unlocks7d}회 · 전체 ${S.unlocks.length}회</span>
    </div>
    <table class="table">
      <thead><tr><th style="width:170px;">일시</th><th style="width:260px;">해제 시간</th><th>사유</th></tr></thead>
      <tbody>
        ${S.unlocks.map((u) => {
          const at = new Date(u.at * 1000);
          const when = `${at.getMonth() + 1}월 ${at.getDate()}일 (${DAYS[at.getDay()]}) ${pad(at.getHours())}:${pad(at.getMinutes())}`;
          const actual = u.actual < 60 ? `${u.actual}초` : dur(u.actual);
          const span = u.ongoing ? `${u.minutes}분 · 진행 중`
            : u.actual < u.minutes * 60 - 30 ? `${u.minutes}분 요청 · ${actual} 만에 다시 차단` : `${u.minutes}분`;
          return html`<tr><td style="font-variant-numeric:tabular-nums;">${when}</td><td class=${u.ongoing ? '' : 'text-muted'} style="font-variant-numeric:tabular-nums;">${span}</td><td style="white-space:normal;">${u.reason}</td></tr>`;
        })}
      </tbody>
    </table>
    ${S.unlocks.length === 0 && html`<div class="text-muted" style="padding:var(--space-6) var(--space-2);font-size:14px;">긴급 잠금 해제를 한 적이 없어요.</div>`}`;

  const stepper = (key, label, desc, min, max, step) => ({
    key, label, desc, value: st[key],
    dec: () => setSetting({ [key]: Math.max(min, st[key] - step) }),
    inc: () => setSetting({ [key]: Math.min(max, st[key] + step) }),
  });
  const tgl = (key, label, desc, guarded) => ({
    key, label, desc, on: st[key],
    toggle: () => (guarded && st[key] === false ? guard(...guarded, () => setSetting({ [key]: true })) : setSetting({ [key]: !st[key] })),
  });
  const Row = (r) => html`
    <div style="display:flex;align-items:center;gap:var(--space-4);padding:var(--space-3) 0;">
      <div style="flex:1;min-width:0;"><div style="font-size:14px;">${r.label}</div><div class="text-muted" style="font-size:12px;">${r.desc}</div></div>
      <${Switch} on=${r.on} onClick=${r.toggle} label=${r.label} />
    </div>`;
  const setPolicy = (p) => (p === 'lock' || st.policy === p ? setSetting({ policy: p }) : guard('해제 방식을 바꿀까요?', '집중 중에 해제 방식을 느슨하게 바꾸려면 확인이 필요해요.', () => setSetting({ policy: p })));
  const settingsView = html`
    <header style="margin-bottom:var(--space-8);">
      <h2 style="margin:0;font-size:28px;">설정</h2>
    </header>
    <div style="display:flex;flex-direction:column;gap:var(--space-8);max-width:720px;">
      <section style="display:flex;flex-direction:column;gap:var(--space-2);">
        <h6 class="text-muted" style="margin-bottom:var(--space-2);">타이머</h6>
        ${[stepper('focusMin', '뽀모도로 집중 시간', '한 세션의 길이', 5, 90, 5), stepper('breakMin', '짧은 휴식', '세션 사이 쉬는 시간', 1, 30, 1), stepper('longBreakMin', '긴 휴식', '4세션마다 한 번', 5, 60, 5)].map((r) => html`
          <div style="display:flex;align-items:center;gap:var(--space-4);padding:var(--space-3) 0;">
            <div style="flex:1;min-width:0;"><div style="font-size:14px;">${r.label}</div><div class="text-muted" style="font-size:12px;">${r.desc}</div></div>
            <div style="display:flex;align-items:center;border:1px solid var(--color-divider);border-radius:var(--radius-md);">
              <button class="btn btn-icon" onClick=${r.dec} style="width:32px;height:32px;" aria-label="줄이기"><i class="ph ph-minus"></i></button>
              <span style="min-width:52px;text-align:center;font-size:14px;font-variant-numeric:tabular-nums;">${r.value}분</span>
              <button class="btn btn-icon" onClick=${r.inc} style="width:32px;height:32px;" aria-label="늘리기"><i class="ph ph-plus"></i></button>
            </div>
          </div>`)}
      </section>
      <section style="display:flex;flex-direction:column;gap:var(--space-2);">
        <h6 class="text-muted" style="margin-bottom:var(--space-2);">집중 모드</h6>
        ${Row(tgl('autoLink', '타이머 시작 시 집중 모드 켜기', '타이머가 끝나면 집중 모드도 꺼져요'))}
        ${Row(tgl('breakUnblock', '휴식 시간에 차단 해제', '뽀모도로 휴식 동안 잠시 풀어줘요', ['휴식 시간에 차단을 풀까요?', '휴식 동안 차단이 잠시 풀려요.']))}
        <div style="display:flex;align-items:center;gap:var(--space-4);padding:var(--space-3) 0;flex-wrap:wrap;">
          <div style="flex:1;min-width:200px;"><div style="font-size:14px;">집중 중 차단 해제</div><div class="text-muted" style="font-size:12px;">${st.policy === 'instant' ? '확인 없이 바로 풀려요' : st.policy === 'wait' ? `${st.waitSec}초 기다린 뒤에 풀 수 있어요` : '타이머가 도는 동안은 풀 수 없어요'}</div></div>
          <div class="seg">
            <label class="seg-opt"><input type="radio" name="policy" checked=${st.policy === 'instant'} onChange=${() => setPolicy('instant')} />바로 해제</label>
            <label class="seg-opt"><input type="radio" name="policy" checked=${st.policy === 'wait'} onChange=${() => setPolicy('wait')} />대기 후 해제</label>
            <label class="seg-opt"><input type="radio" name="policy" checked=${st.policy === 'lock'} onChange=${() => setPolicy('lock')} />잠금</label>
          </div>
        </div>
      </section>
      <section style="display:flex;flex-direction:column;gap:var(--space-2);">
        <h6 class="text-muted" style="margin-bottom:var(--space-2);">차단 화면</h6>
        <div class="field" style="padding:var(--space-3) 0;">
          <label>차단된 사이트에 들어가면 보일 문구</label>
          <input class="input" value=${st.blockMsg} onChange=${(e) => setSetting({ blockMsg: e.target.value })} style="max-width:480px;" />
        </div>
        <div><button class="btn btn-secondary" onClick=${() => setBlockPage({ domain: 'youtube.com', preview: true })}><i class="ph ph-eye"></i>차단 화면 미리보기</button></div>
      </section>
      <section style="display:flex;flex-direction:column;gap:var(--space-2);">
        <h6 class="text-muted" style="margin-bottom:var(--space-2);">일반</h6>
        ${Row(tgl('launch', '컴퓨터 시작 시 실행', '로그인하면 트레이에서 대기해요'))}
        ${Row(tgl('sound', '세션 종료 알림음', '집중과 휴식이 바뀔 때 알려줘요'))}
        <div style="display:flex;gap:var(--space-2);flex-wrap:wrap;">
          <button class="btn btn-ghost" onClick=${() => { setObStep(0); setOnboarding(true); }}><i class="ph ph-arrow-counter-clockwise"></i>처음 설정 다시 보기</button>
          <button class="btn btn-ghost" onClick=${() => handleEvent({ type: 'quitRequest' })} style="color:var(--color-neutral-400);"><i class="ph ph-power"></i>프로그램 종료</button>
        </div>
        <div class="text-muted" style="font-size:12px;">창을 닫으면 작업 표시줄 오른쪽 트레이에서 계속 실행돼요.</div>
      </section>
    </div>`;

  const bpLabel = isWatch ? '지금까지 집중한 시간' : t.running || t.remaining < t.total ? '이번 세션 남은 시간' : '다음 세션 길이';
  const blockPageView = blockPage && html`
    <div style="position:fixed;inset:0;display:flex;flex-direction:column;background:radial-gradient(90% 70% at 15% 20%,var(--color-accent-900),var(--color-bg) 70%);color:var(--color-text);font-family:var(--font-body);z-index:20;">
      <div style="display:flex;align-items:center;gap:var(--space-3);padding:var(--space-3) var(--space-6);font-size:12px;">
        ${blockPage.preview
          ? html`<span class="tag tag-neutral">미리보기</span><span class="text-muted" style="flex:1;">차단된 사이트에 접속하려고 하면 이 화면이 떠요</span>`
          : html`<span class="tag tag-accent">차단</span><span class="text-muted" style="flex:1;">방금 ${blockPage.domain} 접속을 막았어요</span>`}
        <button class="btn btn-secondary" onClick=${closeBlockPage}><i class="ph ph-x"></i>닫기</button>
      </div>
      <div style="flex:1;display:flex;align-items:center;padding:0 12vw;">
        <div style="display:flex;flex-direction:column;gap:var(--space-6);max-width:560px;">
          <div style="width:48px;height:48px;border-radius:var(--radius-lg);display:grid;place-items:center;box-shadow:inset 0 0 0 1px var(--color-accent),0 0 24px var(--color-accent-700);color:var(--color-accent);"><i class="ph ph-shield-check" style="font-size:24px;"></i></div>
          <div>
            <div class="card-kicker" style="margin-bottom:var(--space-3);">${blockPage.domain} 차단됨</div>
            <h1 style="margin:0;font-size:40px;text-wrap:pretty;">${st.blockMsg}</h1>
          </div>
          <div style="display:flex;flex-direction:column;gap:var(--space-2);">
            <div class="text-muted" style="font-size:13px;">${bpLabel}</div>
            <div style="font-family:var(--font-heading);font-weight:500;font-size:48px;letter-spacing:-0.03em;font-variant-numeric:tabular-nums;line-height:1;">${fmt(timeVal)}</div>
            <div style="height:2px;max-width:320px;background:var(--color-neutral-800);margin-top:var(--space-3);"><div style=${`height:100%;width:${Math.round(progress * 100)}%;background:var(--color-accent);box-shadow:0 0 8px var(--color-accent);`}></div></div>
          </div>
          <div style="display:flex;gap:var(--space-3);flex-wrap:wrap;">
            <button class="btn btn-primary" onClick=${closeBlockPage} style="height:36px;"><i class="ph ph-arrow-left"></i>공부로 돌아가기</button>
            <button class="btn btn-ghost" onClick=${bpAllow} style="color:var(--color-neutral-400);">이 사이트 허용하기</button>
            ${canUnlock && html`<button class="btn btn-ghost" onClick=${() => { setBlockPage(null); openUnlock(); }} disabled=${unlockWait} style="color:var(--color-neutral-400);"><i class="ph ph-siren"></i>${unlockBtnLabel}</button>`}
          </div>
        </div>
      </div>
    </div>`;

  const obModes = [
    ['pomo', '뽀모도로', `${st.focusMin}분 집중, ${st.breakMin}분 휴식을 반복해요`, 'ph-timer'],
    ['free', '자유 설정', '원하는 시간만큼 한 번에 집중해요', 'ph-hourglass-medium'],
    ['watch', '스톱워치', '시간 제한 없이 집중한 만큼 쌓아요', 'ph-clock-counter-clockwise'],
  ];
  const onboardingView = onboarding && html`
    <div style="position:fixed;inset:0;display:grid;place-items:center;padding:var(--space-8);background:radial-gradient(80% 60% at 20% 10%,var(--color-accent-900),var(--color-bg) 70%);color:var(--color-text);font-family:var(--font-body);z-index:30;">
      <div style="width:min(560px,100%);display:flex;flex-direction:column;gap:var(--space-8);">
        <div style="display:flex;gap:var(--space-2);">
          ${[0, 1, 2].map((i) => html`<span style=${`flex:1;height:2px;border-radius:1px;background:${i <= obStep ? 'var(--color-accent)' : 'var(--color-neutral-800)'};`}></span>`)}
        </div>
        ${obStep === 0 && html`
          <div style="display:flex;flex-direction:column;gap:var(--space-6);">
            <div>
              <div class="card-kicker" style="margin-bottom:var(--space-3);">1 / 3 · 차단</div>
              <h2 style="margin:0 0 var(--space-3);font-size:30px;">무엇이 집중을 방해하나요?</h2>
              <p class="text-muted" style="font-size:14px;margin:0;">고른 카테고리는 집중하는 동안 막아요. 나중에 언제든 바꿀 수 있어요.</p>
            </div>
            <div style="display:grid;grid-template-columns:repeat(3,minmax(0,1fr));gap:var(--space-3);">
              ${CATS.map((c) => {
                const on = obCats.includes(c); const k = tile(on);
                return html`
                <button onClick=${() => setObCats(on ? obCats.filter((x) => x !== c) : [...obCats, c])} style=${`display:flex;flex-direction:column;align-items:flex-start;gap:var(--space-2);padding:var(--space-4);border-radius:var(--radius-md);border:1px solid ${k.border};background:${k.bg};color:${k.color};font:inherit;cursor:pointer;text-align:left;`}>
                  <i class=${`ph ${CAT_INFO[c].icon}`} style="font-size:20px;"></i>
                  <span style="font-size:14px;">${c}</span>
                  <span style="font-size:11px;opacity:.7;">${CAT_INFO[c].ex}</span>
                </button>`;
              })}
            </div>
          </div>`}
        ${obStep === 1 && html`
          <div style="display:flex;flex-direction:column;gap:var(--space-6);">
            <div>
              <div class="card-kicker" style="margin-bottom:var(--space-3);">2 / 3 · 타이머</div>
              <h2 style="margin:0 0 var(--space-3);font-size:30px;">어떻게 집중할까요?</h2>
              <p class="text-muted" style="font-size:14px;margin:0;">타이머를 시작하면 집중 모드가 켜지고 사이트가 차단돼요.</p>
            </div>
            <div style="display:flex;flex-direction:column;gap:var(--space-3);">
              ${obModes.map(([key, name, desc, icon]) => {
                const on = obMode === key;
                const border = on ? 'var(--color-accent)' : 'var(--color-divider)';
                return html`
                <button onClick=${() => setObMode(key)} style=${`display:flex;align-items:center;gap:var(--space-4);padding:var(--space-4);border-radius:var(--radius-md);border:1px solid ${border};background:${on ? 'var(--color-accent-900)' : 'transparent'};color:var(--color-text);font:inherit;cursor:pointer;text-align:left;`}>
                  <i class=${`ph ${icon}`} style=${`font-size:22px;color:${on ? 'var(--color-accent)' : 'var(--color-neutral-500)'};`}></i>
                  <div style="flex:1;"><div style="font-size:14px;">${name}</div><div class="text-muted" style="font-size:12px;">${desc}</div></div>
                  <span style=${`width:16px;height:16px;border-radius:50%;border:1.5px solid ${border};box-shadow:inset 0 0 0 3px var(--color-bg);background:${on ? 'var(--color-accent)' : 'transparent'};`}></span>
                </button>`;
              })}
            </div>
          </div>`}
        ${obStep === 2 && html`
          <div style="display:flex;flex-direction:column;gap:var(--space-6);">
            <div>
              <div class="card-kicker" style="margin-bottom:var(--space-3);">3 / 3 · 준비 완료</div>
              <h2 style="margin:0 0 var(--space-3);font-size:30px;">준비됐어요.</h2>
              <p class="text-muted" style="font-size:14px;margin:0;">${`${obCats.length}개 카테고리, 약 ${obCats.reduce((a, c) => a + CAT_INFO[c].n, 0)}개 사이트를 집중하는 동안 막아요.`}</p>
            </div>
            <div style="display:flex;flex-direction:column;gap:var(--space-3);font-size:14px;">
              <div style="display:flex;gap:var(--space-3);"><i class="ph ph-play" style="color:var(--color-accent);font-size:18px;"></i>타이머를 시작하면 집중 모드가 켜져요</div>
              <div style="display:flex;gap:var(--space-3);"><i class="ph ph-coffee" style="color:var(--color-accent);font-size:18px;"></i>휴식 시간엔 차단이 잠시 풀려요</div>
              <div style="display:flex;gap:var(--space-3);"><i class="ph ph-hand-pointing" style="color:var(--color-accent);font-size:18px;"></i>필요하면 언제든 직접 차단하고 해제할 수 있어요</div>
            </div>
          </div>`}
        <div style="display:flex;align-items:center;gap:var(--space-3);">
          <button class="btn btn-ghost" onClick=${skipOnboarding} style="color:var(--color-neutral-400);">건너뛰기</button>
          <span style="flex:1;"></span>
          ${obStep > 0 && html`<button class="btn btn-secondary" onClick=${() => setObStep(obStep - 1)}>이전</button>`}
          <button class="btn btn-primary" onClick=${() => (obStep < 2 ? setObStep(obStep + 1) : finishOnboarding())} disabled=${obStep === 0 && obCats.length === 0} style="min-width:120px;">${obStep < 2 ? '다음' : '첫 집중 시작'}</button>
        </div>
      </div>
    </div>`;

  const d = dialog;
  const cancelDialog = () => { pending.current = null; setDialog(null); setRev((r) => r + 1); };
  const confirmDialog = () => { const f = pending.current; pending.current = null; setDialog(null); f && f(); };
  const dialogView = d && html`
    <div class="dialog-backdrop" onClick=${cancelDialog} style="z-index:40;">
      <div class="dialog" onClick=${(e) => e.stopPropagation()}>
        <div style="display:flex;align-items:center;gap:var(--space-3);">
          <i class=${`ph ${d.locked ? 'ph-lock-key' : 'ph-hourglass-medium'}`} style="font-size:22px;color:var(--color-accent);"></i>
          <div class="dialog-title">${d.locked ? '지금은 해제할 수 없어요' : d.title}</div>
        </div>
        <div class="dialog-body">${d.locked ? (S.lockReason === 'schedule' ? `예약 시간이 끝날 때까지 차단이 잠겨 있어요. ${S.lockUntil}에 풀려요.` : `타이머가 끝날 때까지 차단이 잠겨 있어요. 남은 시간 ${fmt(timeVal)}.`) : d.body}</div>
        ${!d.locked && d.total > 0 && html`
          <div style="display:flex;flex-direction:column;gap:var(--space-2);">
            <div style="height:3px;border-radius:2px;background:var(--color-neutral-800);overflow:hidden;">
              <div style=${`height:100%;width:${((d.total - d.count) / (d.total || 1)) * 100}%;background:var(--color-accent);box-shadow:0 0 8px var(--color-accent);transition:width 1s linear;`}></div>
            </div>
            <div class="text-muted" style="font-size:12px;font-variant-numeric:tabular-nums;">${d.count > 0 ? `${d.count}초 후에 해제할 수 있어요` : '해제할 수 있어요'}</div>
          </div>`}
        <div class="dialog-actions">
          ${d.locked && canUnlock && html`<button class="btn btn-ghost" onClick=${openUnlock} disabled=${unlockWait} style="color:var(--color-neutral-400);margin-right:auto;"><i class="ph ph-siren"></i>${unlockBtnLabel}</button>`}
          ${!d.locked && html`<button class="btn btn-secondary" onClick=${confirmDialog} disabled=${d.count > 0}>해제</button>`}
          <button class="btn btn-primary" onClick=${cancelDialog}>계속 집중하기</button>
        </div>
      </div>
    </div>`;

  const uf = unlockForm;
  const reasonLen = uf ? uf.reason.trim().replace(/\s+/g, ' ').length : 0;
  const ufMin = uf ? (uf.min > 0 ? uf.min : parseInt(uf.custom, 10) || 0) : 0;
  const unlockView = uf && html`
    <div class="dialog-backdrop" onClick=${() => setUnlockForm(null)} style="z-index:45;">
      <div class="dialog" onClick=${(e) => e.stopPropagation()} style="width:min(480px,92vw);">
        <div style="display:flex;align-items:center;gap:var(--space-3);">
          <i class="ph ph-siren" style="font-size:22px;color:var(--color-accent);"></i>
          <div class="dialog-title">긴급 잠금 해제</div>
        </div>
        <div class="dialog-body">정말 필요한 경우에만 쓰세요. 한 번에 최대 30분, 1시간에 한 번만 할 수 있어요. 사유와 해제 시간은 기록 화면에 남고, 시간이 지나면 저절로 다시 차단돼요.</div>
        <div class="field">
          <label>정당한 사유</label>
          <textarea class="input" rows="3" maxlength="300" placeholder="예: 온라인 강의 자료가 유튜브에만 올라와 있어요" value=${uf.reason}
            onInput=${(e) => setUnlockForm({ ...uf, reason: e.target.value, error: '' })} style="resize:vertical;min-height:72px;font:inherit;"></textarea>
          <div style=${`font-size:12px;color:${reasonLen >= 10 ? 'var(--color-neutral-500)' : 'var(--color-accent-300)'};`}>${reasonLen}자 · 10자 이상</div>
        </div>
        <div class="field">
          <label>해제할 시간</label>
          <div style="display:flex;gap:var(--space-2);flex-wrap:wrap;align-items:center;">
            ${[5, 10, 15, 30].map((m) => html`<button class=${uf.min === m ? 'btn btn-primary' : 'btn btn-secondary'} onClick=${() => setUnlockForm({ ...uf, min: m, error: '' })}>${m}분</button>`)}
            <input class="input" type="number" min="1" max="30" placeholder="직접(분)" value=${uf.custom}
              onInput=${(e) => setUnlockForm({ ...uf, custom: e.target.value, min: 0, error: '' })} style="width:110px;" />
          </div>
        </div>
        ${ufMin > 30 && html`<div style="font-size:12px;color:var(--color-accent-300);">한 번에 최대 30분까지 해제할 수 있어요.</div>`}
        ${uf.error && html`<div style="font-size:12px;color:var(--color-accent-300);">${uf.error}</div>`}
        <div class="dialog-actions">
          <button class="btn btn-secondary" onClick=${submitUnlock} disabled=${reasonLen < 10 || ufMin < 1 || ufMin > 30}>${ufMin >= 1 && ufMin <= 30 ? `${ufMin}분 동안 해제` : '해제'}</button>
          <button class="btn btn-primary" onClick=${() => setUnlockForm(null)}>계속 집중하기</button>
        </div>
      </div>
    </div>`;
  const unlockBanner = unlocking && html`
    <div class="card" style="flex-direction:row;align-items:center;gap:var(--space-4);padding:var(--space-4) var(--space-6);margin-bottom:var(--space-6);box-shadow:0 0 0 1px var(--color-accent),0 0 24px var(--color-accent-900);flex-wrap:wrap;">
      <i class="ph ph-lock-open" style="font-size:22px;color:var(--color-accent);"></i>
      <div style="flex:1;min-width:200px;">
        <div style="font-size:14px;">긴급 잠금 해제 중 · <span style="font-variant-numeric:tabular-nums;">${fmt(unlockLeft)}</span> 뒤 다시 차단</div>
        <div class="text-muted" style="font-size:12px;">${currentUnlock ? `사유: ${currentUnlock.reason}` : ''}</div>
      </div>
      <button class="btn btn-primary" onClick=${endUnlock}><i class="ph ph-lock"></i>지금 다시 차단</button>
    </div>`;

  return html`
    <div style="display:grid;grid-template-columns:224px minmax(0,1fr);min-height:100vh;background:var(--color-bg);color:var(--color-text);font-family:var(--font-body);">
      ${sidebar}
      <main style="padding:var(--space-8) 40px 64px;max-width:1120px;width:100%;">
        ${S.hostsError && html`<div class="card" style="flex-direction:row;align-items:center;gap:var(--space-3);padding:var(--space-4);margin-bottom:var(--space-6);box-shadow:0 0 0 1px var(--color-accent);"><i class="ph ph-warning" style="font-size:18px;color:var(--color-accent);"></i><span style="font-size:13px;">차단을 적용하지 못했어요: ${S.hostsError}</span></div>`}
        ${unlockBanner}
        ${view === 'focus' && focusView}
        ${view === 'list' && listView}
        ${view === 'stats' && statsView}
        ${view === 'settings' && settingsView}
        ${view === 'schedule' && scheduleView}
      </main>
    </div>
    ${blockPageView}
    ${onboardingView}
    ${dialogView}
    ${unlockView}`;
}

// 텍스트 입력 밖에서는 브라우저 기본 메뉴를 막는다(앱처럼 보이게).
document.addEventListener('contextmenu', (e) => { if (!e.target.closest('input,textarea')) e.preventDefault(); });
render(html`<${App} />`, document.getElementById('app'));
