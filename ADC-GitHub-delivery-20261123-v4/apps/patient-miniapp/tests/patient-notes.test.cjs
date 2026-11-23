const test=require('node:test'),assert=require('node:assert/strict');
const {createAppointmentRequests}=require('../miniprogram/services/appointment-requests.js');
const base={visitId:'51',clinicId:'81001',clinicName:'TEST clinic',dentistId:'79001',registeredAt:'2026-11-22T01:00:00Z',projects:[],invoice:null};
async function client(value){const c=createAppointmentRequests({get:async()=>value,post:async()=>({accessToken:'SYNTHETIC-token',identityVerified:false})});await c.login('TEST');return c;}
test('explicit doctor notes and historical missing explanations are preserved',async()=>{
 const notes=[{patientFindings:'TEST doctor findings',aftercareInstructions:'TEST doctor instructions'}];
 assert.deepEqual((await (await client({...base,clinicalNotes:notes})).visitDetail('30','51')).clinicalNotes,notes);
 assert.deepEqual((await (await client({...base,clinicalNotes:[]})).visitDetail('30','51')).clinicalNotes,[]);
})
test('invalid note types and excessive text fail rather than become patient advice',async()=>{
 for(const notes of [[{patientFindings:{text:'bad'},aftercareInstructions:null}],[{patientFindings:'x'.repeat(4001),aftercareInstructions:null}],{}])await assert.rejects((await client({...base,clinicalNotes:notes})).visitDetail('30','51'));
})
