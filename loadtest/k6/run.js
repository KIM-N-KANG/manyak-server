import {options, setupFor, run, handleSummary} from './lib/runtime.js';
export {options, handleSummary};
const mode = __ENV.SCENARIO || 'mixed';
if (!['browse', 'member-read', 'chat', 'search', 'mixed'].includes(mode)) throw new Error('Invalid SCENARIO');
export function setup() {return setupFor(mode);}
export default function(data) {run(mode, data);}
