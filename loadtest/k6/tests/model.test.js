import test from 'node:test';
import assert from 'node:assert/strict';
import {endpoints, weighted, completed, profile, stageAt} from '../lib/model.js';

test('weights retain the supplied proportions and normalize the full set', () => {
  assert.equal(Number(endpoints.reduce((sum, row) => sum + row[2], 0).toFixed(1)), 90.8);
  assert.equal(weighted(['detail', 'originals'], 0), 'detail');
  assert.equal(weighted(['detail', 'originals'], 0.99), 'originals');
});
test('SSE requires persisted completed turn; a later error invalidates HTTP 200', () => {
  const good = 'event: completed\r\ndata: {"turnId":13,"aiOutput":"본문"}\r\n\r\n';
  assert.equal(completed(good).turnId, 13);
  assert.equal(completed(good + 'event: error\ndata: {}\n\n'), null);
  assert.equal(completed('event: token\ndata: {"text":"a"}\n\n'), null);
  assert.equal(completed('event: completed\ndata: {"turnId":0}\n\n'), null);
});
test('ramp has every configured hold and explicit transition stages', () => {
  const p = profile({PROFILE: 'ramp', HOLD_TIME: '4m'});
  assert.deepEqual(p.stages.filter(s => s.name.startsWith('hold')).map(s => s.target), [10,25,50,100,200]);
  assert.equal(stageAt(p.stages, 29), 'ramp_10');
  assert.equal(stageAt(p.stages, 30), 'hold_10');
  assert.equal(stageAt(p.stages, 270), 'ramp_25');
  assert.throws(() => profile({VUS: 'oops'}));
});
