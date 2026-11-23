import { defineConfig } from 'vite';
import vue from '@vitejs/plugin-vue';
const backend=process.env.ADC_TECHNICAL_BACKEND_URL || 'http://127.0.0.1:8080';
const parsed=new URL(backend);
if(parsed.protocol!=='http:' || parsed.hostname!=='127.0.0.1' || parsed.username || parsed.password || parsed.pathname!=='/' || parsed.search || parsed.hash) throw new Error('Technical proxy must target a loopback HTTP origin without credentials');
const configPaths=[
 '^/api/v1/staff/schedules(?:\\?.*)?$',
 '^/api/v1/staff/schedules/-?[0-9]+/(?:versions/[1-9][0-9]*|draft-revisions)$',
 '^/api/v1/staff/schedule-(?:checks|drafts|maintenance-issues(?:/-?[0-9]+)?)$',
 '^/api/v1/staff/duration-projects(?:/[1-9][0-9]*/versions/[1-9][0-9]*)?(?:\\?.*)?$',
 '^/api/v1/headquarters/duration-project-drafts$',
 '^/api/v1/headquarters/duration-projects/[1-9][0-9]*/versions/[1-9][0-9]*/(?:draft|publications)$',
 '^/api/v1/headquarters/duration-commands/HQCFG/v1/[a-f0-9]{32}/DURATION_(?:DRAFT_CREATE|DRAFT_EDIT|PUBLISH)/[a-f0-9-]{36}$'
];
export default defineConfig({plugins:[vue()],server:{port:5173,strictPort:true,proxy:{'/api/technical':backend,...Object.fromEntries(configPaths.map(p=>[p,backend]))}},preview:{host:'127.0.0.1'}});
