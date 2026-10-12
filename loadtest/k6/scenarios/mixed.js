import {options, setupFor, run, handleSummary as summary} from '../lib/runtime.js';
export {options};
export function handleSummary(data) {return summary(data, 'mixed');}
export function setup() { return setupFor('mixed'); }
export default function(data) { run('mixed', data); }
