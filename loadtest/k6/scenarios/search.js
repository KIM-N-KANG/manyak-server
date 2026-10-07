import {options, setupFor, run, handleSummary as summary} from '../lib/runtime.js';
export {options};
export function handleSummary(data) {return summary(data, 'search');}
export function setup() { return setupFor('search'); }
export default function(data) { run('search', data); }
