import {options, setupFor, run, handleSummary as summary} from '../lib/runtime.js';
export {options};
export function handleSummary(data) {return summary(data, 'member-read');}
export function setup() { return setupFor('member-read'); }
export default function(data) { run('member-read', data); }
