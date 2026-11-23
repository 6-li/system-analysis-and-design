const { TechnicalHttpError } = require('./http.js');
const decimalId = value => typeof value === 'string' && /^[1-9][0-9]{0,18}$/.test(value);

function createAppointmentRequests(http) {
  let token;
  const root = '/api/v1/mini';
  function headers() {
    if (!token) throw new TechnicalHttpError('INVALID_REQUEST');
    return { Authorization: 'Bearer ' + token };
  }
  function validKey(key) {
    if (typeof key !== 'string' || !/^[a-zA-Z0-9_-]{1,160}$/.test(key)) {
      throw new TechnicalHttpError('INVALID_REQUEST');
    }
    return key;
  }
  function receipt(data, key) {
    if (data.submissionKey !== key || data.operation !== 'op008' ||
        !['UNKNOWN', 'CONFIRMED_RECEIVED', 'CONFIRMED_REJECTED'].includes(data.receiptStatus) ||
        (data.receiptStatus === 'CONFIRMED_RECEIVED' &&
          (typeof data.requestId !== 'string' || !data.requestId.trim()))) {
      throw new TechnicalHttpError('INVALID_RESPONSE');
    }
    return data;
  }
  return {
    async register(username,password) {
      if(typeof username!=='string'||!/^[A-Za-z0-9_]{4,32}$/.test(username)||typeof password!=='string'||password.length<8||password.length>128)throw new TechnicalHttpError('INVALID_REQUEST');
      const result=await http.post(root+'/accounts',{username,password});
      if(typeof result?.accountId!=='string'||result.identityVerified!==false)throw new TechnicalHttpError('INVALID_RESPONSE');
      return {accountId:result.accountId};
    },
    async passwordLogin(username,password) {
      token=undefined;
      if(typeof username!=='string'||!/^[A-Za-z0-9_]{4,32}$/.test(username)||typeof password!=='string'||password.length<8||password.length>128)throw new TechnicalHttpError('INVALID_REQUEST');
      const result=await http.post(root+'/sessions/password',{username,password});
      if(result?.identityVerified!==false||typeof result.accessToken!=='string'||!result.accessToken.trim()||/\s/.test(result.accessToken))throw new TechnicalHttpError('INVALID_RESPONSE');
      token=result.accessToken;
      return {identityVerified:false};
    },
    clearSession(){token=undefined;},
    async logout(){try{await http.post(root+'/sessions/logout',{},headers());}finally{token=undefined;}},
    async ownRequests(cursor) {
      const result=await http.get(root+'/requests'+(cursor?'?cursor='+encodeURIComponent(cursor):''),headers());
      if(!Array.isArray(result?.items)||result.items.some(row=>typeof row.requestId!=='string'||!['PENDING_VERIFICATION','PENDING_BOOKING','PENDING_PROCESSING','BOOKED','CHANGED','CANCELLED','REJECTED'].includes(row.status)))throw new TechnicalHttpError('INVALID_RESPONSE');
      return result;
    },
    async bookingOptions(clinicId,date,projectId,originalAppointmentId) {
      if(!decimalId(clinicId)||!/^\d{4}-\d{2}-\d{2}$/.test(date))throw new TechnicalHttpError('INVALID_REQUEST');
      if(originalAppointmentId&&!decimalId(originalAppointmentId))throw new TechnicalHttpError('INVALID_REQUEST');
      const result=await http.get(root+(originalAppointmentId?'/booking-change-options':'/booking-options')+'?clinicId='+encodeURIComponent(clinicId)+'&date='+encodeURIComponent(date)+(projectId?'&projectId='+encodeURIComponent(projectId):'')+(originalAppointmentId?'&originalAppointmentId='+encodeURIComponent(originalAppointmentId):''),headers());
      if(result?.clinicId!==clinicId||result.date!==date||result.reservesCapacity!==false||!result.timeZone||!Array.isArray(result.projects)||!Array.isArray(result.slots))throw new TechnicalHttpError('INVALID_RESPONSE');
      if(result.slots.some(s=>!['AVAILABLE','FULL'].includes(s.availabilityStatus)||!Number.isFinite(Date.parse(s.startsAt))||!Number.isFinite(Date.parse(s.endsAt))||Date.parse(s.startsAt)>=Date.parse(s.endsAt)||!s.effectiveScheduleRef||!s.durationBasis))throw new TechnicalHttpError('INVALID_RESPONSE');
      return result;
    },
    async selfProfile() {
      const result = await http.get(root + '/me', headers());
      if (result?.status === 'UNLINKED') return { status: 'UNLINKED' };
      if (result?.status !== 'VERIFIED' || !decimalId(result.patientId) ||
          !['patientName', 'identityInformation', 'contactInformation'].every(key => typeof result[key] === 'string')) {
        throw new TechnicalHttpError('INVALID_RESPONSE');
      }
      return result;
    },
    async appointmentPatients(permission) {
      const result = await http.get(root + '/authorizations', headers());
      if (!Array.isArray(result.items)) throw new TechnicalHttpError('INVALID_RESPONSE');
      const patients = new Map();
      for (const item of result.items) {
        if (item?.status === 'ACTIVE' && decimalId(item.patientId) &&
            ['SELF', 'GUARDIAN'].includes(item.kind) && (!permission ||
              (Array.isArray(item.permissions) && item.permissions.includes(permission)))) {
          if (!patients.has(item.patientId) || item.kind === 'SELF') {
            patients.set(item.patientId, { patientId: item.patientId, kind: item.kind });
          }
        }
      }
      return [...patients.values()];
    },
    async appointments(patientId, cursor) {
      if (!decimalId(patientId) || (cursor && !decimalId(cursor))) throw new TechnicalHttpError('INVALID_REQUEST');
      const query = '?patientId=' + patientId + (cursor ? '&cursor=' + cursor : '');
      const result = await http.get(root + '/appointments' + query, headers());
      if (!Array.isArray(result.items) || (result.nextCursor != null && !decimalId(result.nextCursor)) ||
          result.items.some(item => item?.patientId !== patientId || !decimalId(item.appointmentId) ||
            !['VERIFIED', 'PENDING_VERIFICATION'].includes(item.factVerification) ||
            typeof item.startsAt !== 'string' || typeof item.endsAt !== 'string' ||
            typeof item.formalStatus !== 'string')) throw new TechnicalHttpError('INVALID_RESPONSE');
      return result;
    },
    async visitDetail(patientId, visitId) {
      if(!decimalId(patientId)||!decimalId(visitId))throw new TechnicalHttpError('INVALID_REQUEST');
      const value=await http.get(root+'/visit-history/'+visitId+'?patientId='+patientId,headers());
      const money=x=>typeof x==='string'&&/^\d+\.\d{2}$/.test(x);
      if(value.visitId!==visitId||!decimalId(value.clinicId)||!decimalId(value.dentistId)||typeof value.clinicName!=='string'||!Number.isFinite(Date.parse(value.registeredAt))||!Array.isArray(value.projects)||value.projects.some(x=>typeof x.item!=='string'||typeof x.unit!=='string'||typeof x.quantity!=='string'))throw new TechnicalHttpError('INVALID_RESPONSE');
      if(value.clinicalNotes!==undefined&&(!Array.isArray(value.clinicalNotes)||value.clinicalNotes.some(row=>!row||['patientFindings','aftercareInstructions'].some(key=>row[key]!==null&&(typeof row[key]!=='string'||Array.from(row[key]).length>4000)))))throw new TechnicalHttpError('INVALID_RESPONSE');
      if(value.invoice!==null){const b=value.invoice;if(!b||!decimalId(b.invoiceId)||!Number.isSafeInteger(b.version)||b.version<1||!money(b.total)||!Array.isArray(b.lines)||b.lines.some(x=>typeof x.item!=='string'||!money(x.unitPrice)||!money(x.amount))||!Array.isArray(b.payments))throw new TechnicalHttpError('INVALID_RESPONSE');}
      return value;
    },
    async visitHistory(patientId, cursor) {
      if (!decimalId(patientId) || (cursor && !decimalId(cursor))) throw new TechnicalHttpError('INVALID_REQUEST');
      const query = '?patientId=' + patientId + (cursor ? '&cursor=' + cursor : '');
      const result = await http.get(root + '/visit-history' + query, headers());
      if (!Array.isArray(result.items) || (result.nextCursor != null && !decimalId(result.nextCursor)) ||
          result.items.some(item => item?.patientId !== patientId || !decimalId(item.visitId) ||
            !decimalId(item.clinicId) || !Number.isFinite(Date.parse(item.registeredAt)) ||
            !Array.isArray(item.performedProjects) || item.performedProjects.some(name => typeof name !== 'string'))) {
        throw new TechnicalHttpError('INVALID_RESPONSE');
      }
      return result;
    },
    async clinics() {
      const result = await http.get(root + '/booking/clinics', headers());
      if (!Array.isArray(result.items) || result.items.some(item =>
        typeof item?.clinicId !== 'string' || typeof item?.clinicName !== 'string')) {
        throw new TechnicalHttpError('INVALID_RESPONSE');
      }
      return result.items;
    },
    async login(code) {
      token = undefined;
      if (typeof code !== 'string' || !code.trim()) throw new TechnicalHttpError('INVALID_REQUEST');
      const result = await http.post(root + '/sessions/wechat', { code });
      if (result.identityVerified !== false || typeof result.accessToken !== 'string' ||
          !result.accessToken.trim() || /\s/.test(result.accessToken)) throw new TechnicalHttpError('INVALID_RESPONSE');
      token = result.accessToken;
      return { identityVerified: false };
    },
    async submit(key, request) {
      validKey(key);
      if (!request || !['NEW', 'CHANGE', 'CANCEL'].includes(request.type)) {
        throw new TechnicalHttpError('INVALID_REQUEST');
      }
      try {
        return receipt(await http.post(root + '/requests', request,
          { ...headers(), 'Idempotency-Key': key }), key);
      } catch (error) {
        if (!['NETWORK_ERROR', 'TIMEOUT', 'INVALID_RESPONSE'].includes(error.code) &&
            !(error.status >= 500)) throw error;
        return { submissionKey: key, operation: 'op008', receiptStatus: 'UNKNOWN' };
      }
    },
    async recover(key) {
      return receipt(await http.get(root + '/submission-receipts/' + validKey(key) + '?operation=op008', headers()), key);
    },
  };
}

module.exports = { createAppointmentRequests };
