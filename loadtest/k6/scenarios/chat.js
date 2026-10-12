import {options, setupFor, run, handleSummary as summary} from '../lib/runtime.js';
export {options};
export function handleSummary(data) {return summary(data, 'chat');}
export function setup() { return setupFor('chat'); }
export default function(data) { run('chat', data); }
