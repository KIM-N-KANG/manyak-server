export const endpoints = [
  ['detail', 'GET /stories/{id}', 29.5], ['originals', 'GET /stories/originals', 8.6],
  ['chatDetail', 'GET /chats/{id}', 8.5], ['stories', 'GET /stories', 8.3],
  ['trials', 'GET /users/me/trials', 8], ['guestConsents', 'GET /guests/consents', 6.8],
  ['policies', 'GET /credits/policies', 3.7], ['turn', 'POST /chats/{id}/turns/stream', 3.2],
  ['me', 'GET /auth/me', 3.2], ['choices', 'POST /chats/{id}/turns/{n}/choices', 2.9],
  ['create', 'POST /chats', 2], ['tags', 'GET /stories/simple/tags', 1.5],
  ['genres', 'GET /stories/genres', 1.5], ['consents', 'GET /users/me/consents', 1.3],
  ['myChats', 'GET /users/me/chats', 0.9], ['myStories', 'GET /users/me/stories', 0.9],
  ['search', 'GET /stories/search', 0],
];
export const names = Object.fromEntries(endpoints.map(([key, name]) => [key, name]));
export const browseKeys = ['detail', 'originals', 'stories', 'guestConsents', 'policies', 'tags', 'genres'];
export const memberKeys = ['chatDetail', 'trials', 'me', 'consents', 'myChats', 'myStories'];
export function weighted(keys, random = Math.random()) {
  const rows = endpoints.filter(([key, , weight]) => keys.includes(key) && weight > 0);
  if (!rows.length) throw new Error('No weighted endpoints');
  let cursor = random * rows.reduce((sum, row) => sum + row[2], 0);
  for (const [key, , weight] of rows) { cursor -= weight; if (cursor < 0) return key; }
  return rows[rows.length - 1][0];
}
export function completed(body) {
  let result = null;
  for (const block of String(body || '').replace(/\r\n/g, '\n').split(/\n\n/)) {
    const lines = block.split('\n');
    const event = lines.find(line => line.startsWith('event:'));
    const data = lines.filter(line => line.startsWith('data:')).map(line => line.slice(5).trimStart()).join('\n');
    if (event && event.slice(6).trim() === 'error') return null;
    if (event && event.slice(6).trim() === 'completed') {
      try { result = JSON.parse(data); } catch (_) { return null; }
    }
  }
  return result && Number.isInteger(result.turnId) && result.turnId > 0 && typeof result.aiOutput === 'string' ? result : null;
}
export function seconds(text) {
  const match = /^(\d+(?:\.\d+)?)(ms|s|m|h)$/.exec(String(text));
  if (!match) throw new Error(`Invalid duration: ${text}`);
  return Number(match[1]) * {ms: 0.001, s: 1, m: 60, h: 3600}[match[2]];
}
export function profile(env) {
  const kind = env.PROFILE || 'smoke';
  const stages = [];
  const add = (name, target, duration) => stages.push({name, target, duration, seconds: seconds(duration)});
  if (kind === 'ramp') {
    for (const target of [10, 25, 50, 100, 200]) {
      add(`ramp_${target}`, target, env.RAMP_TIME || '30s');
      add(`hold_${target}`, target, env.HOLD_TIME || '4m');
    }
    add('cooldown', 0, '10s');
  } else if (kind === 'spike') {
    const target = Number(env.VUS || 200);
    add('rise', target, '10s'); add('hold', target, env.HOLD_TIME || '4m'); add('fall', 0, '10s');
  } else if (kind === 'soak' || kind === 'smoke') {
    add(kind, Number(env.VUS || (kind === 'soak' ? 50 : 1)), env.DURATION || (kind === 'soak' ? '2h' : '30s'));
  } else throw new Error('PROFILE must be smoke/ramp/spike/soak');
  if (stages.some(s => !Number.isInteger(s.target) || s.target < 0 || s.seconds <= 0)) throw new Error('Invalid VUS/duration');
  return {kind, stages, maxVus: Math.max(...stages.map(s => s.target)), startVUs: kind === 'smoke' || kind === 'soak' ? stages[0].target : 0};
}
export function stageAt(stages, elapsed) {
  let end = 0;
  for (const stage of stages) { end += stage.seconds; if (elapsed < end) return stage.name; }
  return stages[stages.length - 1].name;
}
