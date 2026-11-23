import { createApp } from 'vue';
import { createRouter, createWebHistory } from 'vue-router';
import BillingWorkbench from './views/BillingWorkbench.vue';
import App from './App.vue';
import StatusView from './views/StatusView.vue';
import NotFoundView from './views/NotFoundView.vue';
import DurationWorkspace from './views/DurationWorkspace.vue';
import Appointments from './views/Appointments.vue';
import ScheduleWorkspace from './views/ScheduleWorkspace.vue';
import ScheduleResolutions from './views/ScheduleResolutions.vue';
import PatientMaintenance from './views/PatientMaintenance.vue';
import VisitHealth from './views/VisitHealth.vue';
import VisitConsent from './views/VisitConsent.vue';
import DentistSchedule from './views/DentistSchedule.vue';
import VisitTreatment from './views/VisitTreatment.vue';
import TreatmentInvoice from './views/TreatmentInvoice.vue';
import InvoiceQuestion from './views/InvoiceQuestion.vue';
import InvoiceSettlement from './views/InvoiceSettlement.vue';
import StockInquiry from './views/StockInquiry.vue';
import PurchaseRequirement from './views/PurchaseRequirement.vue';
import SupplyReceipt from './views/SupplyReceipt.vue';
import SupplyConsumption from './views/SupplyConsumption.vue';
import QuarterlyPreview from './views/QuarterlyPreview.vue';
import QuarterlyIssue from './views/QuarterlyIssue.vue';
import QuarterlyCalculation from './views/QuarterlyCalculation.vue';
import QuarterlyFinancialReview from './views/QuarterlyFinancialReview.vue';
const router = createRouter({ history:createWebHistory(), routes:[
 {path:'/staff/billing-workbench',component:BillingWorkbench}, {path:'/',component:StatusView}, {path:'/headquarters/duration',component:DurationWorkspace},
 {path:'/staff/my-schedule',component:DentistSchedule},
 {path:'/staff/schedule-workspace',component:ScheduleWorkspace}, {path:'/staff/schedule-resolutions',component:ScheduleResolutions},
 {path:'/staff/patients',component:PatientMaintenance}, {path:'/staff/appointments',component:Appointments},
 {path:'/staff/visit-health',component:VisitHealth}, {path:'/staff/visit-consent',component:VisitConsent}, {path:'/staff/visit-treatment',component:VisitTreatment}, {path:'/staff/treatment-invoice',component:TreatmentInvoice}, {path:'/staff/invoice-question',component:InvoiceQuestion}, {path:'/staff/invoice-settlement',component:InvoiceSettlement}, {path:'/staff/stock-inquiry',component:StockInquiry}, {path:'/staff/purchase-requirements',component:PurchaseRequirement}, {path:'/staff/supply-receipt',component:SupplyReceipt}, {path:'/staff/supply-consumption',component:SupplyConsumption}, {path:'/staff/quarterly-preview',component:QuarterlyPreview}, {path:'/staff/quarterly-issue',component:QuarterlyIssue}, {path:'/staff/quarterly-calculation',component:QuarterlyCalculation}, {path:'/staff/quarterly-financial-review',component:QuarterlyFinancialReview}, {path:'/:pathMatch(.*)*',component:NotFoundView}
] });
createApp(App).use(router).mount('#app');
