import {options, setupFor, run, handleSummary as summary} from '../lib/runtime.js';
export {options};
export function handleSummary(data) {return summary(data, 'browse');}
export function setup() { return setupFor('browse'); }
export default function(data) { run('browse', data); }
