// Exercise the real entry point with k6 host APIs substituted, without network/credentials.
import test from 'node:test';
import assert from 'node:assert/strict';
import vm from 'node:vm';
import fs from 'node:fs';
import path from 'node:path';

async function host(env = {}, failSse = false) {
  const requests = [], metrics = {}, root = path.resolve('loadtest/k6');
  const context = vm.createContext({__ENV: {PROFILE:'smoke', SCENARIO:'chat', THINK_TIME:'0', TOKENS_FILE:'fixture', ...env},
    open: () => JSON.stringify([{publicId:'15960000-0000-4000-8000-000000000001',token:'fake-test-only'}]),
    console, Date, Math});
  class Metric {
    constructor(name) {this.name=name;metrics[name]=[];}
    add(value) {metrics[this.name].push(value);}
  }
  const response = (status, data) => ({status, body: typeof data === 'string' ? data : JSON.stringify(data),
    json() {return typeof data === 'string' ? JSON.parse(data) : data;}, timings: {duration:11000, waiting:300}});
  function request(method,url,body,params) {
    requests.push({method,url,body,params});
    if (url.includes('/turns/stream')) return response(200, failSse ? 'event: error\ndata: {}\n\n' : 'event: completed\ndata: {"turnId":41,"aiOutput":"test"}\n\n');
    if (method==='POST' && url.endsWith('/chats')) return response(201,{id:'chat-uuid'});
    if (url.includes('filter=original')) return response(200,{items:[{id:'story-uuid'}]});
    return response(200,{});
  }
  const substitutes = {
    'k6/http': {default: {request,get: (url,params) => request('GET',url,null,params)}},
    k6: {check: (r, checks) => Object.values(checks).every(fn => fn(r)), sleep() {}, fail(message) {throw Error(message);}},
    'k6/execution': {default: {vu: {idInTest:1}, scenario: {startTime: Date.now()}, test: {abort(message) {throw Error(message);}}}},
    'k6/data': {SharedArray: class {constructor(_, fn) {return fn();}}},
    'k6/metrics': {Trend: Metric, Rate: Metric, Counter: Metric},
  };
  const cache = new Map();
  async function load(file) {
    if (cache.has(file)) return cache.get(file);
    let module;
    if (substitutes[file]) {
      const values = substitutes[file];
      module = new vm.SyntheticModule(Object.keys(values), function() {for (const [key,value] of Object.entries(values)) this.setExport(key,value);}, {context});
    } else module = new vm.SourceTextModule(fs.readFileSync(file,'utf8'), {context,identifier:file});
    cache.set(file,module);
    await module.link((specifier, parent) => load(substitutes[specifier] ? specifier : path.resolve(path.dirname(parent.identifier),specifier)));
    return module;
  }
  const main = await load(path.join(root,'run.js'));
  await main.evaluate();
  return {main:main.namespace,requests,metrics};
}
test('chat sends persisted turn ID, correct headers, template tags, and overhead', async () => {
  const h = await host(); const data = h.main.setup(); h.main.default(data);
  const choices = h.requests.find(r => r.url.includes('/turns/41/choices'));
  assert.ok(choices);
  assert.equal(choices.params.tags.name,'POST /chats/{id}/turns/{n}/choices');
  assert.equal(choices.params.headers.Authorization,'Bearer fake-test-only');
  assert.ok(choices.params.headers['X-Manyak-Session-Id']);
  assert.equal(h.metrics.chat_first_byte[0],300);
  assert.equal(h.metrics.chat_server_overhead[0],600);
  assert.equal(h.requests.some(r => r.url.includes('/turns/undefined')),false);
});
test('HTTP 200 SSE failure records an error and does not request choices', async () => {
  const h = await host({},true); h.main.default(h.main.setup());
  assert.equal(h.requests.some(r => r.url.includes('/choices')),false);
  assert.equal(h.metrics.request_errors.at(-1),true);
});
test('summary keeps stage/endpoint stats and true Rate values mean errors', async () => {
  const h = await host();
  const summary = h.main.handleSummary({metrics:{
    stage_smoke_detail_duration:{values:{min:1,avg:2,'p(95)':3,'p(99)':4,max:5}},
    stage_smoke_detail_error:{values:{rate:.01}},
    stage_smoke_detail_requests:{values:{count:100}},
  }});
  const result = JSON.parse(summary['/tmp/k6-summary.json']);
  assert.equal(result.stages.smoke.requests,100);
  assert.equal(result.stages.smoke.errorRate,.01);
  assert.equal(result.stages.smoke.endpoints['GET /stories/{id}'].milliseconds['p(99)'],4);
  assert.ok(summary.stdout.startsWith('K6_SUMMARY_JSON'));
});
test('browse runs anonymously without any token file', async () => {
  const h = await host({SCENARIO:'browse',TOKENS_FILE:''}); h.main.default(h.main.setup());
  assert.ok(h.requests.every(r => !r.params.headers.Authorization));
  assert.ok(h.requests.every(r => r.method==='GET'));
});
test('search passes query as a query parameter but keeps the template metric name', async () => {
  const h = await host({SCENARIO:'search',TOKENS_FILE:'',SEARCH_QUERY:'아카데미 학교'}); h.main.default(h.main.setup());
  const request = h.requests.at(-1);
  assert.equal(request.params.tags.name,'GET /stories/search');
  assert.ok(request.url.endsWith(encodeURIComponent('아카데미 학교')));
});
