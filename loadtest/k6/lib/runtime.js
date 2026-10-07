import http from 'k6/http';
import {check, sleep, fail} from 'k6';
import exec from 'k6/execution';
import {SharedArray} from 'k6/data';
import {Trend, Rate, Counter} from 'k6/metrics';
import {endpoints, names, browseKeys, memberKeys, weighted, completed, profile, stageAt} from './model.js';

const plan = profile(__ENV);
const base = (__ENV.BASE_URL || 'http://localhost:8080').replace(/\/$/, '');
const tokens = new SharedArray('tokens', () => __ENV.TOKENS_FILE ? JSON.parse(open(__ENV.TOKENS_FILE)) : []);
const providedStories = new SharedArray('stories', () => __ENV.STORIES_FILE ? JSON.parse(open(__ENV.STORIES_FILE)) : []);
const tokenIds = new Set();
for (const user of tokens) {
  if (!user || typeof user.publicId !== 'string' || typeof user.token !== 'string' || !user.token || tokenIds.has(user.publicId)) throw new Error('Invalid or duplicate token identity');
  tokenIds.add(user.publicId);
}
const errors = new Rate('request_errors');
const reads = new Trend('read_duration', true);
const firstByte = new Trend('chat_first_byte', true);
const fullTime = new Trend('chat_full_time', true);
const overhead = new Trend('chat_server_overhead', true);
const cells = {};
const thresholds = {request_errors: ['rate<0.01'], read_duration: ['p(95)<500']};
for (const stage of plan.stages) {
  cells[stage.name] = {};
  for (const [key] of endpoints) {
    const prefix = `stage_${stage.name}_${key}`;
    cells[stage.name][key] = {duration: new Trend(`${prefix}_duration`, true), error: new Rate(`${prefix}_error`), count: new Counter(`${prefix}_requests`)};
  }
}
export const options = {
  scenarios: {load: {executor: 'ramping-vus', startVUs: plan.startVUs,
    stages: plan.stages.map(({target, duration}) => ({target, duration})), gracefulRampDown: '2m', gracefulStop: '2m'}},
  thresholds, summaryTrendStats: ['min', 'avg', 'p(95)', 'p(99)', 'max', 'count'],
  // No URL/UUID tags or response/token logging.
  systemTags: ['name', 'method', 'status', 'scenario', 'expected_response'],
  setupTimeout: __ENV.SETUP_TIMEOUT || '15m',
};
function headers(user) {
  const id = user ? user.publicId : `15960000-0000-4000-9000-${String(exec.vu.idInTest || 1).padStart(12, '0')}`;
  return {'Content-Type': 'application/json', 'X-Manyak-Device-Id': `k6-${id}`,
    'X-Manyak-Session-Id': id, ...(user ? {Authorization: `Bearer ${user.token}`} : {})};
}
function parse(response) { try { return response.json(); } catch (_) { return null; } }
function send(key, method, path, user, body = null, measured = true) {
  const stage = stageAt(plan.stages, (Date.now() - exec.scenario.startTime) / 1000);
  const response = http.request(method, `${base}/api/v1${path}`, body === null ? null : JSON.stringify(body), {
    headers: {...headers(user), ...(key === 'turn' ? {Accept: 'text/event-stream'} : {})},
    tags: {name: names[key], stage, phase: measured ? 'load' : 'setup'}, timeout: __ENV.HTTP_TIMEOUT || '120s',
  });
  const data = key === 'turn' ? completed(response.body) : parse(response);
  const valid = response.status === (key === 'create' ? 201 : 200) &&
    (key === 'turn' ? !!data : key === 'create' ? !!(data && data.id) : data !== null);
  check(response, {[names[key]]: () => valid}, {name: names[key], phase: measured ? 'load' : 'setup'});
  if (measured) {
    errors.add(!valid);
    const cell = cells[stage][key];
    cell.duration.add(response.timings.duration); cell.error.add(!valid); cell.count.add(1);
    if (method === 'GET') reads.add(response.timings.duration);
    if (key === 'turn') {
      firstByte.add(response.timings.waiting); fullTime.add(response.timings.duration);
      // Keep negative values: wrong mock budgets must remain visible.
      overhead.add(response.timings.duration - Number(__ENV.MOCK_CHAT_SECONDS || 10.4) * 1000);
    }
  }
  return {response, data, valid};
}
export function setupFor(mode) {
  if (!['browse', 'search'].includes(mode) && tokens.length < plan.maxVus) fail('Need at least max VUs distinct tokens');
  const r = http.get(`${base}/api/v1/stories?filter=original&limit=50`, {
    headers: headers(null), tags: {name: names.originals, phase: 'setup'}, timeout: '30s'});
  const list = parse(r);
  const stories = providedStories.length ? [...providedStories] : (list && list.items || []).map(row => row.id);
  if (r.status !== 200 || !stories.length || stories.some(id => typeof id !== 'string')) fail('No readable original stories; check official account and STORIES_FILE');
  return {stories};
}
let state;
function context(data, mode) {
  if (!state) {
    const user = tokens[exec.vu.idInTest - 1];
    state = {user, story: data.stories[(exec.vu.idInTest - 1) % data.stories.length], chat: null, turn: null, choiceChat: null, choiceTurn: null};
    if (!['browse', 'search'].includes(mode)) {
      const auth = send('me', 'GET', '/auth/me', user, null, false);
      if (!auth.valid) exec.test.abort('Seed authentication failed');
      if (!create(state, false)) exec.test.abort('Cannot initialize owned chat');
      // Initial completed turn enables choices to keep mixed request weights intact.
      if (mode === 'mixed' && !turn(state, false)) exec.test.abort('Cannot initialize completed turn');
    }
  }
  return state;
}
function create(ctx, measured = true) {
  const r = send('create', 'POST', '/chats', ctx.user, {storyId: ctx.story}, measured);
  if (r.valid) {ctx.chat = r.data.id; ctx.turn = null;}
  return r.valid;
}
function turn(ctx, measured = true) {
  if (!ctx.chat) return false;
  const r = send('turn', 'POST', `/chats/${ctx.chat}/turns/stream`, ctx.user,
    {userInput: __ENV.USER_INPUT || '주변을 살피고 동료에게 다음 행동을 묻는다.', realtimeImage: __ENV.REALTIME_IMAGE === 'true'}, measured);
  if (r.valid) {ctx.turn = r.data.turnId; ctx.choiceChat = ctx.chat; ctx.choiceTurn = r.data.turnId;}
  return r.valid;
}
function choices(ctx) {
  if (!ctx.choiceChat || !ctx.choiceTurn) return false;
  return send('choices', 'POST', `/chats/${ctx.choiceChat}/turns/${ctx.choiceTurn}/choices`, ctx.user).valid;
}
function action(key, ctx) {
  const paths = {detail: `/stories/${ctx.story}`, originals: '/stories/originals', stories: '/stories',
    guestConsents: '/guests/consents', policies: '/credits/policies', tags: '/stories/simple/tags',
    genres: '/stories/genres', chatDetail: `/chats/${ctx.chat}`, trials: '/users/me/trials', me: '/auth/me',
    consents: '/users/me/consents', myChats: '/users/me/chats', myStories: '/users/me/stories',
    search: `/stories/search?q=${encodeURIComponent(__ENV.SEARCH_QUERY || '아카데미')}`};
  if (key === 'create') return create(ctx);
  if (key === 'turn') return turn(ctx);
  if (key === 'choices') return choices(ctx);
  return send(key, 'GET', paths[key], browseKeys.includes(key) || key === 'search' ? null : ctx.user).valid;
}
export function run(mode, data) {
  const ctx = context(data, mode);
  if (mode === 'chat') {
    if (create(ctx) && turn(ctx)) choices(ctx);
  } else {
    const key = mode === 'search' ? 'search' : weighted(mode === 'browse' ? browseKeys : mode === 'member-read' ? memberKeys : endpoints.map(row => row[0]));
    // choices targets the last completed turn even if another chat was just created.
    action(key, ctx);
  }
  sleep(Number(__ENV.THINK_TIME || (mode === 'chat' ? 2 : 1)));
}
export function handleSummary(data, mode = __ENV.SCENARIO || 'mixed') {
  const stages = {};
  for (const stage of plan.stages) {
    const endpoint = {};
    let failures = 0, requests = 0;
    for (const [key, name] of endpoints) {
      const prefix = `stage_${stage.name}_${key}`;
      const duration = data.metrics[`${prefix}_duration`];
      const error = data.metrics[`${prefix}_error`];
      const ev = error && error.values;
      const counter = data.metrics[`${prefix}_requests`];
      const count = counter ? counter.values.count : 0;
      if (!count) continue;
      endpoint[name] = {milliseconds: duration && duration.values, requests: count, errorRate: ev.rate};
      failures += ev.rate * count; requests += count; // Rate true = failed request.
    }
    stages[stage.name] = {targetVus: stage.target, duration: stage.duration, requests, errorRate: requests ? failures / requests : null, endpoints: endpoint};
  }
  const summary = {profile: plan.kind, scenario: mode, stages,
    chat: Object.fromEntries(['chat_first_byte', 'chat_full_time', 'chat_server_overhead'].map(key => [key, data.metrics[key] && data.metrics[key].values])),
    overheadGoalMs: 1000, mockChatSeconds: Number(__ENV.MOCK_CHAT_SECONDS || 10.4),
    thresholds: Object.fromEntries(Object.entries(data.metrics).filter(([, metric]) => metric.thresholds).map(([key, metric]) => [key, metric.thresholds]))};
  const json = JSON.stringify(summary, null, 2);
  return {stdout: `K6_SUMMARY_JSON\n${json}\n`, [__ENV.SUMMARY_FILE || '/tmp/k6-summary.json']: json};
}
