import { defineConfig } from 'vite';
import manifest from './app.json' with {type:'json'};
const origin=manifest.permissions.find(p=>p.name==='network')?.whitelist[0];
if(!origin?.startsWith('https://')) throw Error('A production HTTPS relay origin is required.');
export default defineConfig({base:'./',define:{__G2_SERVER_ORIGIN__:JSON.stringify(origin)},build:{target:'es2022'}});
