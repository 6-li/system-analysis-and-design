import test from 'node:test'
import assert from 'node:assert/strict'
import {workspaceContext,staffRoles} from '../src/services/staff-workspace.js'
const doc=(role,links=[])=>({querySelector:s=>s.startsWith('meta')?{content:role}:{querySelectorAll:()=>links.map(([href,text])=>({getAttribute:()=>href,textContent:text}))}})
test('workspace role is server metadata rather than a route or account guess',()=>{for(const [code,name] of Object.entries(staffRoles))assert.equal(workspaceContext(doc(code)).role,name);assert.equal(workspaceContext(doc('administrator')).role,'岗位未提供')})
test('workspace navigation is only the safe links in the server authorized shell',()=>{const c=workspaceContext(doc('dentist',[['/staff/visit-consent','诊疗同意'],['/staff/visit-health','健康资料'],['/','切换岗位'],['javascript:alert(1)','unsafe'],['//other.example/staff/a','unsafe']]));assert.deepEqual(c.links.map(r=>r.href),['/staff/visit-consent','/staff/visit-health']);assert.equal(c.code,'dentist')})
test('missing authenticated shell does not invent a role or navigation',()=>{const c=workspaceContext({querySelector:()=>null});assert.equal(c.role,'岗位未提供');assert.equal(c.links.length,0)})
