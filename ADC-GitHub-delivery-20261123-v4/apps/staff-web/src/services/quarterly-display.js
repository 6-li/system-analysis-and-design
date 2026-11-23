export function quarterlyIssueLabel(value){
 if(typeof value!=='string')return ''
 const prefix='QUARTER_EXPENSE_V2:'
 if(!value.startsWith(prefix))return value
 try{const v=JSON.parse(value.slice(prefix.length));return '运营费用待核实 · '+v.quarter+' · 诊所 '+v.clinicId+' · 来源 '+v.factId+' / '+v.factVersion}catch{return '运营费用来源信息待核对'}
}
