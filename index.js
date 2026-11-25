const { createHttpClient } = require('../../services/http.js');
const { createAppointmentRequests } = require('../../services/appointment-requests.js');
const {demoDentists}=require('../../services/demo-dentists.js');
const {appointmentDisplay,instantDisplay}=require('../../services/appointment-display.js');
const {appointmentReminders,readReminderSnapshot}=require('../../services/appointment-reminders.js');

const storageKey = 'adc-mini-preliminary-pending';
const demoClinics = [
  { clinicId: '81001', clinicName: 'ADC 近邻门诊（演示）', distanceLabel: '1.2 公里' },
  { clinicId: '81002', clinicName: 'ADC 中心门诊（演示）', distanceLabel: '3.8 公里' },
];
Page({
  data: { available: false, busy: false, signedIn: false, screen: 'home', message: '', pending: '',
    patientAccounts:false,accountName:'',accountPassword:'',ownRequests:[],requestCursor:'',requestNotice:'',appointmentDetail:null,
    patient: '', applicant: '', contact: '', clinic: '', clinicName: '', date: '', need: '',
    clinics: [], choosingClinic: false, recommended: false, syntheticLogin: false, receivedRequests: [],
    appointmentPatients: [], formalAppointments: [], selectedPatient: '', appointmentCursor: '', appointmentNotice: '',
    appointmentReminders:[],reminderState:'idle',reminderBusy:false,
    changeEnabled: false, cancelEnabled: false, requestTarget: '',requestProfileReady:false,bookingProfileReady:false,bookingProfileBound:false,requestPatientName:'',originalAppointmentToken:'',
    historyDetail:null,historyDetailNotice:'',historyPatients: [], historyRows: [], historyPatient: '', historyCursor: '', historyNotice: '',
    selfProfile: null, profileNotice: '',slotSelectionEnabled:false,bookingProjects:[],bookingSlots:[],bookingProject:'',bookingProjectName:'',selectedSlot:null,selectionConfirmed:false,slotNotice:'',bookingDoctors:[],bookingDoctor:'',bookingDoctorName:'',doctorNotice:'' },
  onLoad() {
    const config = getApp().globalData?.miniRequestConfig;
    if (!config?.baseUrl) return;
    this.syntheticLogin = config.syntheticLogin === true;
    this.patientAccounts=config.patientAccounts===true;
    this.accountOrigin=config.baseUrl;
    this.courseClinicIds = Array.isArray(config.courseClinicIds) ? config.courseClinicIds : null;
    this.client = createAppointmentRequests(createHttpClient(config));
    this.setData({ available: true, patientAccounts:this.patientAccounts, syntheticLogin: this.syntheticLogin, changeEnabled: config.changeRequestsEnabled === true,
      cancelEnabled: config.cancelRequestsEnabled === true,
      slotSelectionEnabled:config.slotSelectionEnabled===true,
      pending: this.patientAccounts?'':wx.getStorageSync(storageKey) || '' });
  },
  onShow(){
    this._remindersVisible=true;
    if(this.data.signedIn){this.startAppointmentReminderTimer();void this.refreshAppointmentReminders();}
  },
  onHide(){this._remindersVisible=false;this.stopAppointmentReminderTimer();this.clearAppointmentReminders();},
  onUnload(){this._remindersVisible=false;this.stopAppointmentReminderTimer();this.clearAppointmentReminders();this.accountEpoch=(this.accountEpoch||0)+1;},
  startAppointmentReminderTimer(){
    if(!this._remindersVisible||!this.data.signedIn||this._reminderTimer)return;
    this._reminderTimer=setInterval(()=>{if(this._remindersVisible&&this.data.signedIn)void this.refreshAppointmentReminders();},30000);
    if(typeof this._reminderTimer?.unref==='function')this._reminderTimer.unref();
  },
  stopAppointmentReminderTimer(){if(this._reminderTimer){clearInterval(this._reminderTimer);this._reminderTimer=null;}},
  clearAppointmentReminders(){
    this._reminderTicket=(this._reminderTicket||0)+1;
    this.setData({appointmentReminders:[],reminderState:'idle',reminderBusy:false});
  },
  async refreshAppointmentReminders(){
    if(!this.data.signedIn||this.data.reminderBusy||typeof this.client?.appointmentPatients!=='function'||typeof this.client?.appointments!=='function')return;
    const epoch=this.accountEpoch||0,ticket=this._reminderTicket=(this._reminderTicket||0)+1;
    const current=()=>this.data.signedIn&&epoch===(this.accountEpoch||0)&&ticket===this._reminderTicket;
    this.setData({appointmentReminders:[],reminderState:'loading',reminderBusy:true});
    try{
      const snapshot=await readReminderSnapshot(this.client);
      const clinics=appointmentReminders(snapshot.appointments,Date.now()).length&&typeof this.client.clinics==='function'?await this.client.clinics().catch(()=>[]):[];
      if(!current())return;
      this.setData({appointmentReminders:appointmentReminders(snapshot.appointments,Date.now(),clinics),reminderState:'ready'});
    }catch{if(current())this.setData({appointmentReminders:[],reminderState:'error'});}
    finally{if(current())this.setData({reminderBusy:false});}
  },
  editAccount(e){if(['accountName','accountPassword'].includes(e.currentTarget.dataset.field))this.setData({[e.currentTarget.dataset.field]:e.detail.value});},
  async registerAccount(){
    if(this.data.busy||!this.client)return;
    this.setData({busy:true,message:''});
    try{await this.client.register(this.data.accountName,this.data.accountPassword);this.setData({message:'注册成功，请输入密码登录。'});}
    catch(error){this.setData({message:error?.status===409?'该账号名已被使用，请更换。':'注册未完成，请检查账号格式、密码或连接。'});}
    finally{this.setData({busy:false,accountPassword:''});}
  },
  async logout(){
    if(this.data.busy)return;this.accountEpoch=(this.accountEpoch||0)+1;
    this.stopAppointmentReminderTimer();this.clearAppointmentReminders();
    const revocation=this.client?.logout?this.client.logout():Promise.resolve();
    this.client?.clearSession();this.pendingStorageKey=null;
    this.setData({signedIn:false,accountPassword:'',pending:'',receivedRequests:[],ownRequests:[],requestCursor:'',selfProfile:null,bookingProfileReady:false,bookingProfileBound:false,requestPatientName:'',
      formalAppointments:[],appointmentDetail:null,appointmentPatients:[],historyDetail:null,historyDetailNotice:'',historyRows:[],historyPatients:[],patient:'',applicant:'',contact:'',need:'',selectedSlot:null,screen:'home',message:''});
    this.setData({busy:true});
    try{await revocation;}catch{this.setData({message:'本机已退出；服务器退出未确认，请勿在共享设备保存登录信息。'});}finally{this.setData({busy:false});}
  },
  async refreshRequests(e){
    if(!this.patientAccounts||!this.data.signedIn)return;
    const epoch=this.accountEpoch||0;
    const cursor=e?.currentTarget?.dataset?.more?this.data.requestCursor:null;
    try{const result=await this.client.ownRequests(cursor);if(epoch!==(this.accountEpoch||0)||!this.data.signedIn)return;const labels={PENDING_VERIFICATION:'待前台核验',PENDING_BOOKING:'待前台确认预约',PENDING_PROCESSING:'待前台处理',BOOKED:'已生成正式预约',CHANGED:'改约已通过',CANCELLED:'取消已通过',REJECTED:'申请已拒绝'};
      this.setData({ownRequests:[...(cursor?this.data.ownRequests:[]),...result.items.map(row=>({...row,statusLabel:labels[row.status]}))],requestCursor:result.nextCursor||'',requestNotice:''});}
    catch{if(epoch===(this.accountEpoch||0)&&this.data.signedIn)this.setData({requestNotice:'申请列表读取未完成，请重试。'});}
  },
  edit(e) {
    const field = e.currentTarget.dataset.field;
    if (['patient', 'applicant', 'contact', 'date', 'need'].includes(field)) {
      this.setData({ [field]: e.detail.value });
      if(['patient','contact','date','need'].includes(field)){this.slotLoadVersion=(this.slotLoadVersion||0)+1;this.setData({selectedSlot:null,selectionConfirmed:false,...(field==='date'?{bookingSlots:[],bookingProjects:[],bookingProject:'',bookingProjectName:''}:{})});}
    }
  },
  async login() {
    if (this.data.busy || !this.client) return;
    this.stopAppointmentReminderTimer();this.clearAppointmentReminders();
    this.accountEpoch=(this.accountEpoch||0)+1;
    this.setData({ busy: true, signedIn: false, receivedRequests: [], appointmentPatients: [],
      formalAppointments: [], selectedPatient: '', appointmentCursor: '', appointmentNotice: '' });
    try {
      if(this.patientAccounts){
        await this.client.passwordLogin(this.data.accountName,this.data.accountPassword);
        this.pendingStorageKey=storageKey+':'+encodeURIComponent(this.accountOrigin)+':'+this.data.accountName.toLowerCase();
        this.setData({signedIn:true,screen:'home',message:'',pending:wx.getStorageSync(this.pendingStorageKey)||'',ownRequests:[],requestCursor:''});
        await this.refreshRequests();return;
      }
      const code = this.syntheticLogin ? 'SYNTHETIC-code' : await new Promise((resolve, reject) => wx.login({
        success: result => resolve(result.code), fail: reject,
      }));
      await this.client.login(code);
      this.setData({ signedIn: true, screen: 'home', message: '' });
    } catch (error) {
      const hints={DOMAIN:'手机拒绝了服务器域名请求（DOMAIN）',TLS:'手机的HTTPS证书或TLS连接未通过（TLS）',CONNECTION:'手机到服务器的连接未成功（CONNECTION）',UNKNOWN:'手机网络请求未完成（NETWORK）'};
      const detail=error?.code==='TIMEOUT'?'服务器连接超时（TIMEOUT）':error?.networkCategory?hints[error.networkCategory]:Number.isInteger(error?.status)?'服务器返回 HTTP '+error.status:error?.code==='INVALID_RESPONSE'?'登录返回内容不符合预期（INVALID_RESPONSE）':'请重新编译并核对调试配置';
      let debug='';
      if(this.syntheticLogin&&error?.networkCategory==='DOMAIN'){
        try{const state=typeof wx!=='undefined'&&typeof wx.getAppBaseInfo==='function'?wx.getAppBaseInfo().enableDebug:undefined;debug='（手机调试：'+(state===true?'已开启':state===false?'未开启':'未知')+'）';}catch{debug='（手机调试：未知）';}
      }
      this.setData({ message: this.syntheticLogin?'演示登录未完成：'+detail+debug+'。':'登录未完成。' });
    } finally { this.setData({ busy: false,accountPassword:'' });
      if(this.data.signedIn){this.startAppointmentReminderTimer();void this.refreshAppointmentReminders();}
    }
  },
  async loadBookingDoctors(){
    if(!this.data.clinic||!this.data.signedIn)return;
    const clinic=this.data.clinic,ticket=this.doctorLoadVersion=(this.doctorLoadVersion||0)+1;
    try{
      const list=this.syntheticLogin?demoDentists(this.data.clinic):(await this.client.bookingOptions(this.data.clinic,this.data.date||new Date().toISOString().slice(0,10))).dentists;
      if(!Array.isArray(list))throw Error('DIRECTORY_UNAVAILABLE');
        if(ticket!==this.doctorLoadVersion||clinic!==this.data.clinic)return;
      this.setData({bookingDoctors:list,doctorNotice:list.length?'':'该门诊暂无已提供的医生目录。'});
      }catch{if(ticket===this.doctorLoadVersion&&clinic===this.data.clinic)this.setData({bookingDoctors:[],bookingDoctor:'',bookingDoctorName:'',doctorNotice:'医生目录暂时无法读取，请重试。'});}
  },
  chooseBookingDoctor(e){
    if(this.data.busy)return;const doctor=this.data.bookingDoctors[Number(e.detail.value)];if(!doctor)return;
    this.slotLoadVersion=(this.slotLoadVersion||0)+1;
    this.setData({bookingDoctor:doctor.dentistId,bookingDoctorName:doctor.dentistLabel,bookingSlots:[],selectedSlot:null,selectionConfirmed:false,slotNotice:'请选择日期和预约项目后查询这位医生的时段。'});
  },
  async openBooking() {
    if (!this.data.signedIn || this.data.busy) return;
    const epoch=this.accountEpoch||0;
    this.setData({ screen: 'booking', message: '', busy: true,bookingProfileReady:false,bookingProfileBound:false,requestPatientName:'' });
    try {
      if(this.patientAccounts){
        this.setData({patient:'',applicant:'',contact:''});
        try{const profile=await this.client.selfProfile();
          if(epoch!==(this.accountEpoch||0)||!this.data.signedIn)return;
          if(profile.status==='VERIFIED')this.setData({patient:profile.identityInformation,applicant:profile.patientName,contact:profile.contactInformation,requestPatientName:profile.patientName,bookingProfileBound:true,bookingProfileReady:true});
          else if(profile.status==='UNLINKED')this.setData({bookingProfileReady:true});
          else throw Error('PROFILE_REQUIRED');
        }catch{if(epoch===(this.accountEpoch||0))this.setData({message:'本人资料读取未完成，请返回首页重试，暂不提交预约。'});return;}
      }
      if (this.syntheticLogin && !this.data.clinic) this.recommendDemoClinic();
      else if (!this.syntheticLogin && !this.data.clinics.length) this.setData({ clinics: await this.client.clinics() });
        if(this.data.clinic&&!this.data.bookingDoctors.length)await this.loadBookingDoctors();
    } catch {
      this.setData({ clinics: [], message: '诊所列表暂时无法读取，请稍后再试。' });
    } finally { this.setData({ busy: false }); }
  },
  async loadBookingSlots(){
    if(this.data.busy||!this.data.signedIn||!this.data.clinic||!this.data.date)return;
    if(!this.data.bookingDoctor){this.setData({slotNotice:'请先选择医生。'});return;}
    this.setData({busy:true,bookingSlots:[],selectedSlot:null,selectionConfirmed:false,slotNotice:''});
    const ticket=this.slotLoadVersion=(this.slotLoadVersion||0)+1;
    try{const result=await this.client.bookingOptions(this.data.clinic,this.data.date,this.data.bookingProject||undefined,this.data.screen==='change'?this.data.requestTarget:undefined);
      if(ticket!==this.slotLoadVersion)return;
      const slots=result.slots.filter(s=>s.dentistId===this.data.bookingDoctor).map((s,index)=>({...s,index,label:s.startsAt.slice(11,16)+' 到诊 · '+this.data.bookingDoctorName,estimatedEnd:s.endsAt.slice(11,16),estimatedMinutes:s.durationBasis.adoptedDurationMinutes}));
      this.setData({originalAppointmentToken:result.expectedAppointmentToken||'',bookingProjects:result.projects,bookingSlots:slots,slotNotice:!this.data.bookingProject?'请选择诊疗项目。':slots.length?'查询不预占名额，前台办理时仍会复核。':'当天没有可用的正式接诊安排，请换日期。'});
    }catch{if(ticket===this.slotLoadVersion)this.setData({bookingProjects:[],bookingSlots:[],slotNotice:'时段读取失败，请重试；本次未预约，不会自动提交。'});}
    finally{this.setData({busy:false});}
  },
  async chooseBookingProject(e){const project=this.data.bookingProjects[Number(e.detail.value)];if(!project||this.data.busy)return;this.setData({bookingProject:project.projectId,bookingProjectName:project.projectName});await this.loadBookingSlots();},
  chooseBookingSlot(e){if(this.data.busy)return;const slot=this.data.bookingSlots[Number(e.currentTarget.dataset.index)];if(!slot||slot.availabilityStatus!=='AVAILABLE'||slot.dentistId!==this.data.bookingDoctor)return;this.setData({selectedSlot:slot,selectionConfirmed:false});},
  confirmBookingSelection(e){this.setData({selectionConfirmed:Array.isArray(e.detail.value)&&e.detail.value.includes('confirmed')});},
  async openProfile() {
    if (!this.data.signedIn || this.data.busy) return;
    this.setData({ screen: 'profile', busy: true, selfProfile: null, profileNotice: '' });
    try {
      const profile = await this.client.selfProfile();
      this.setData(profile.status === 'VERIFIED' ? { selfProfile: profile } :
        { profileNotice: '尚未关联已核验的本人档案，请由前台核验。提交预约申请不会自动完成身份关联。' });
    } catch {
      this.setData({ selfProfile: null, profileNotice: '本人资料暂时无法读取，请核对授权或稍后重试。' });
    } finally { this.setData({ busy: false }); }
  },
  openHome() {
    if (!this.data.busy){this.setData({ screen: 'home', choosingClinic: false, historyDetail:null,historyDetailNotice:'',historyRows: [], historyPatients: [] });void this.refreshAppointmentReminders();}
  },
  async openVisitHistory() {
    if (!this.data.signedIn || this.data.busy) return;
    this.setData({ screen: 'history', busy: true, message: '', historyDetail:null,historyDetailNotice:'',historyPatients: [], historyRows: [],
      historyPatient: '', historyCursor: '', historyNotice: '' });
    try {
      const patients = await this.client.appointmentPatients('VISIT_HISTORY_READ');
      this.setData({ historyPatients: patients });
      if (patients.length === 1) await this.readVisitHistory(patients[0].patientId);
      else if (!patients.length) this.setData({ historyNotice: '暂无获授权的就诊记录查询对象。' });
    } catch {
      this.setData({ historyNotice: '暂时无法读取历史就诊，请稍后重试。' });
    } finally { this.setData({ busy: false }); }
  },
  async openHistoryDetail(event) {
    const visitId=event.currentTarget.dataset.id;
    if(this.data.busy||!this.data.historyRows.some(row=>row.visitId===visitId))return;
    this.setData({screen:'history-detail',busy:true,historyDetail:null,historyDetailNotice:''});
    try {
      const detail=await this.client.visitDetail(this.data.historyPatient,visitId);
      this.setData({historyDetail:{...detail,dateLabel:instantDisplay(detail.registeredAt),dentistLabel:demoDentists(detail.clinicId).find(x=>x.dentistId===detail.dentistId)?.dentistLabel||'医生编号 '+detail.dentistId}});
    } catch {this.setData({historyDetailNotice:'暂时无法读取就诊详情，请返回列表重试。'});}
    finally {this.setData({busy:false});}
  },
  backToHistory(){if(!this.data.busy)this.setData({screen:'history',historyDetail:null,historyDetailNotice:''});},
  async selectHistoryPatient(event) {
    const id = event.currentTarget.dataset.id;
    if (this.data.busy || !this.data.historyPatients.some(patient => patient.patientId === id)) return;
    await this.readVisitHistory(id);
  },
  async loadMoreHistory() {
    const { busy, historyPatient, historyCursor } = this.data;
    if (!busy && historyPatient && historyCursor) await this.readVisitHistory(historyPatient, historyCursor);
  },
  async readVisitHistory(patientId, cursor) {
    this.setData({ busy: true, historyPatient: patientId, historyNotice: '',
      ...(cursor ? {} : { historyRows: [], historyCursor: '' }) });
    try {
      const result = await this.client.visitHistory(patientId, cursor);
      const clinics = this.syntheticLogin ? demoClinics : await this.client.clinics().catch(() => []);
      const rows = result.items.map(item => ({
        ...item,
        dateLabel: new Date(item.registeredAt).toLocaleDateString(),
        clinicLabel: clinics.find(clinic => clinic.clinicId === item.clinicId)?.clinicName || '诊所 ' + item.clinicId,
        projectLabel: item.performedProjects.join('、') || '暂无已实施项目记录',
      }));
      const historyRows = cursor ? [...this.data.historyRows, ...rows] : rows;
      this.setData({ historyRows, historyCursor: result.nextCursor || '',
        historyNotice: historyRows.length ? '' : '暂无历史就诊记录。' });
    } catch {
      this.setData({ historyRows: [], historyCursor: '', historyNotice: '暂时无法读取历史就诊，请检查授权或稍后重试。' });
    } finally { this.setData({ busy: false }); }
  },
  async openAppointments() {
    if (!this.data.signedIn || this.data.busy) return;
    void this.refreshAppointmentReminders();
    this.setData({ screen: 'appointments', message: '', choosingClinic: false, busy: true,
      appointmentPatients: [], formalAppointments: [], selectedPatient: '', appointmentCursor: '', appointmentNotice: '' });
    try {
      if(this.patientAccounts)await this.refreshRequests();
      const patients = await this.client.appointmentPatients();
      this.setData({ appointmentPatients: patients });
      if (patients.length === 1) await this.readAppointments(patients[0].patientId);
      else if (!patients.length) this.setData({ appointmentNotice: '暂无可查询正式预约的患者身份。' });
    } catch {
      this.setData({ appointmentNotice: '暂时无法读取正式预约，请稍后重试。' });
    } finally { this.setData({ busy: false }); }
  },
  async selectAppointmentPatient(e) {
    const id = e.currentTarget.dataset.id;
    if (this.data.busy || !this.data.appointmentPatients.some(patient => patient.patientId === id)) return;
    this.setData({ busy: true });
    try { await this.readAppointments(id); }
    finally { this.setData({ busy: false }); }
  },
  async loadMoreAppointments() {
    const { busy, selectedPatient, appointmentCursor } = this.data;
    if (busy || !selectedPatient || !appointmentCursor) return;
    this.setData({ busy: true });
    try { await this.readAppointments(selectedPatient, appointmentCursor); }
    finally { this.setData({ busy: false }); }
  },
  async readAppointments(patientId, cursor) {
    if (!cursor) this.setData({ formalAppointments: [], selectedPatient: patientId, appointmentCursor: '' });
    try {
      const result = await this.client.appointments(patientId, cursor);
      const clinics=typeof this.client.clinics==='function'?await this.client.clinics().catch(()=>[]):[];
      const ownAppointment = this.data.appointmentPatients.some(patient => patient.patientId === patientId && patient.kind === 'SELF');
      const rows = result.items.map(item => ({
        appointmentId: item.appointmentId,
        clinicId:item.clinicId,dentistId:item.dentistId,
        ...appointmentDisplay(item,clinics,this.syntheticLogin?demoDentists(item.clinicId):[]),
        canRequestChange: this.data.changeEnabled && item.factVerification === 'VERIFIED' && item.formalStatus === '待到诊'
          && ownAppointment,
        canRequestCancel: this.data.cancelEnabled && item.factVerification === 'VERIFIED' && item.formalStatus === '待到诊'
          && ownAppointment,
      }));
      const formalAppointments = cursor ? [...this.data.formalAppointments, ...rows] : rows;
      this.setData({ formalAppointments, appointmentCursor: result.nextCursor || '',
        appointmentNotice: formalAppointments.length ? '' : '该患者暂无正式预约。' });
    } catch {
      this.setData({ formalAppointments: [], appointmentCursor: '',
        appointmentNotice: '暂时无法读取该患者的正式预约，请重新选择患者后再试。' });
    }
  },
  openAppointmentDetail(e){
    if(!this.data.signedIn||this.data.busy)return;
    const row=this.data.formalAppointments.find(item=>item.appointmentId===e.currentTarget.dataset.id);
    if(row)this.setData({screen:'appointment-detail',appointmentDetail:{...row}});
  },
  openChange(e) {
    return this.openAppointmentRequest(e, 'change', 'canRequestChange');
  },
  openCancel(e) {
    return this.openAppointmentRequest(e, 'cancel', 'canRequestCancel');
  },
  async openAppointmentRequest(e, screen, permission) {
    const id = e.currentTarget.dataset.id;
    if (this.data.busy || this.data.pending || !this.data.signedIn || this.data.screen !== 'appointments'
        || !this.data.formalAppointments.some(row => row.appointmentId === id && row[permission])) return;
    this.setData({ screen, requestTarget: id, patient: '', applicant: '', contact: '', date: '', need: '',
      message: '',requestProfileReady:false,requestPatientName:'' });
    if(!this.patientAccounts)return;
    const epoch=this.accountEpoch||0;
    this.setData({busy:true});
    try {
      const profile=await this.client.selfProfile();
      if(epoch!==(this.accountEpoch||0)||!this.data.signedIn||this.data.screen!==screen||this.data.requestTarget!==id)return;
      if(profile.status!=='VERIFIED'||profile.patientId!==this.data.selectedPatient){this.setData({message:'当前账号的本人资料与所选预约不一致，请返回本人资料核对。'});return;}
      const original=this.data.formalAppointments.find(row=>row.appointmentId===id);
      this.setData({clinic:original.clinicId,clinicName:original.clinicLabel||('诊所 '+original.clinicId),bookingDoctors:[],bookingDoctor:'',bookingDoctorName:'',bookingProjects:[],bookingProject:'',bookingSlots:[],selectedSlot:null,selectionConfirmed:false,originalAppointmentToken:'',
        patient:profile.identityInformation,applicant:profile.patientName,contact:profile.contactInformation,
        requestPatientName:profile.patientName,requestProfileReady:true,message:screen==='change'?'已带入本人资料，请选择医生、日期及可用到达时间。':'已带入本人资料，请确认取消申请。'});
      if(screen==='change')await this.loadBookingDoctors();
    }catch(error){
      if(epoch===(this.accountEpoch||0))this.setData({message:error?.status===401?'登录已失效，请退出后重新登录。':'本人资料读取未完成，请返回首页重试，暂不提交申请。'});
    }finally{if(epoch===(this.accountEpoch||0))this.setData({busy:false});}
  },
  openClinics() { this.setData({ choosingClinic: !this.data.choosingClinic }); },
  recommendDemoClinic() {
    if (!this.syntheticLogin || !this.data.signedIn) return;
    const clinics=this.courseClinicIds?demoClinics.filter(row=>this.courseClinicIds.includes(row.clinicId)):demoClinics;
    const [nearest] = clinics;if(!nearest)return;
    this.setData({ clinics, recommended: true, clinic: nearest.clinicId, clinicName: nearest.clinicName,bookingDoctors:demoDentists(nearest.clinicId) });
  },
  async selectClinic(e) {
    const id = String(e.currentTarget.dataset.id ?? '');
    const chosen = this.data.clinics.find(item => item.clinicId === id);
    if (chosen){this.slotLoadVersion=(this.slotLoadVersion||0)+1;this.setData({ clinic: chosen.clinicId, clinicName: chosen.clinicName, choosingClinic: false,bookingProjects:[],bookingSlots:[],bookingProject:'',bookingProjectName:'',selectedSlot:null,selectionConfirmed:false,bookingDoctors:[],bookingDoctor:'',bookingDoctorName:'' });await this.loadBookingDoctors();}
  },
  async submit() {
    const { patient, applicant, contact, clinic, date, need, busy, pending, signedIn } = this.data;
    const change = this.data.screen === 'change';
    const cancel = this.data.screen === 'cancel';
    if (busy || pending || !signedIn || (!change && !cancel && this.data.screen !== 'booking')) return;
    if(!change&&!cancel&&this.patientAccounts&&!this.data.bookingProfileReady){this.setData({message:'请先成功读取当前账号的本人资料，再提交预约。'});return;}
    if((change||cancel)&&this.patientAccounts&&!this.data.requestProfileReady){this.setData({message:'请先成功读取当前账号的本人资料，再提交申请。'});return;}
    const fields = cancel ? [patient, applicant, contact]
      : change ? [patient, applicant, contact, date] : [patient, contact, clinic, date, need];
    if (!fields.every(value => value.trim())) {
      this.setData({ message: '请填齐预约需求资料。' });
      return;
    }
    if (!cancel && (!/^\d{4}-\d{2}-\d{2}$/.test(date.trim()) ||
        !Number.isFinite(Date.parse(date.trim() + 'T00:00:00Z')) ||
        new Date(date.trim() + 'T00:00:00Z').toISOString().slice(0, 10) !== date.trim())) {
      this.setData({ message: '请选择有效的预约日期。' });
      return;
    }
    if(!cancel&&this.data.slotSelectionEnabled&&(!this.data.selectedSlot||!this.data.selectionConfirmed||this.data.selectedSlot.dentistId!==this.data.bookingDoctor)){
      this.setData({message:'请选择可预约时段并确认预约安排。'});return;
    }
    this.setData({ busy: true });
    let submitStage='生成请求编号';
    try {
      const key = await new Promise((resolve, reject) => wx.getRandomValues({ length: 16, fail: reject,
        success: ({ randomValues }) => resolve(Array.from(new Uint8Array(randomValues),
          value => value.toString(16).padStart(2, '0')).join('')),
      }));
      submitStage='保存本机回执';
      wx.setStorageSync(this.pendingStorageKey || storageKey, key);
      this.setData({ pending: key });
      const identity = { patientIdentification: patient.trim(), applicantInformation: change || cancel ? applicant.trim() : patient.trim(),
        contactInformation: contact.trim() };
      const preferred = { date: date.trim() };
      const request = change || cancel
        ? { type: change ? 'CHANGE' : 'CANCEL', actingMode: 'SELF', patientId: this.data.selectedPatient,
          originalAppointmentId: this.data.requestTarget, identity, ...(change ? { preferred } : {}),
          ...(need.trim() ? { [change ? 'changeReason' : 'cancellationReason']: need.trim() } : {}) }
        : { type: 'NEW', actingMode: 'SELF', intendedClinicId: clinic.trim(), identity, preferred,
          treatmentNeed: need.trim(), bookingIntent: { visitType: 'INITIAL' } };
      if(!cancel&&this.data.slotSelectionEnabled){const s=this.data.selectedSlot;request.selectedScheme={effectiveScheduleRef:s.effectiveScheduleRef,startsAt:s.startsAt,endsAt:s.endsAt,treatmentRequest:change?'按原预约需求就诊':need.trim(),durationBasis:s.durationBasis};request.patientConfirmed=true;if(change)request.expectedAppointmentToken=this.data.originalAppointmentToken;}
      submitStage='发送申请';
      const receipt = await this.client.submit(key, request);
      submitStage='处理申请回执';
      this.showReceipt(receipt);
    } catch (error) {
      if(error.code==='MINI_SELECTED_SLOT_UNAVAILABLE'){
        this.clearPending();this.setData({selectedSlot:null,selectionConfirmed:false,bookingSlots:[],message:'所选时段已变化或约满，请重新查询并选择。原填写内容已保留。'});return;
      }
      if ([400, 401, 403, 422].includes(error.status)) this.clearPending();
      const status=Number.isInteger(error?.status)?' HTTP '+error.status:'';
      const safeCodes=['INVALID_REQUEST','INVALID_RESPONSE','NETWORK_ERROR','TIMEOUT','HTTP_400','HTTP_401','HTTP_403','HTTP_422'];
      const code=safeCodes.includes(error?.code)?' '+error.code:'';
      const advice=error?.status===401?'请重新登录；若仍失败，请核对本人资料中的身份信息。':error?.status===400?'请保留填写内容并反馈此状态码。':'';
      this.setData({ message: (this.data.pending ? '接收结果待核对，请查询原回执。' : '申请未提交。')+'（'+submitStage+status+code+'）'+advice });
    } finally { this.setData({ busy: false }); }
  },
  async lookup() {
    if (!this.data.signedIn || !this.data.pending || this.data.busy) return;
    this.setData({ busy: true });
    try { this.showReceipt(await this.client.recover(this.data.pending)); }
    catch { this.setData({ message: '暂时无法查回原回执，请勿重发。' }); }
    finally { this.setData({ busy: false }); }
  },
  clearPending() {
    wx.removeStorageSync(this.pendingStorageKey || storageKey);
    this.setData({ pending: '' });
  },
  showReceipt(receipt) {
    if (receipt.receiptStatus === 'CONFIRMED_RECEIVED') {
      this.clearPending();
      const previous = this.data.receivedRequests.filter(item => item !== receipt.requestId);
      this.setData({ screen: 'appointments', receivedRequests: [receipt.requestId, ...previous],
        message: '已接收预约需求，等待接待人员核验。申请编号 ' + receipt.requestId });
      if(this.patientAccounts)this.refreshRequests();
    } else if (receipt.receiptStatus === 'CONFIRMED_REJECTED') {
      this.clearPending();
      this.setData({ message: '本次申请未获接收。' });
    } else this.setData({ message: '接收结果待核对，请查询原回执。' });
  },
});
