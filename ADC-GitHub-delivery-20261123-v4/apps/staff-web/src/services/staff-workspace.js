export const staffRoles=Object.freeze({reception:'前台接待',dentist:'接诊牙医',billing:'收费人员',procurement:'采购人员',manager:'诊所负责人',operations:'总部运营',finance:'财务人员'})
// Display-only context from the server-rendered authenticated shell. Never grants permissions.
export function workspaceContext(doc){
 const code=doc.querySelector('meta[name="adc-staff-role"]')?.content||''
 const source=doc.querySelector('body > nav[aria-label="岗位工作台"]')
 const links=source?Array.from(source.querySelectorAll('a')).map(a=>({href:a.getAttribute('href'),title:a.textContent.trim()})).filter(a=>/^\/staff\/[a-z-]+$/.test(a.href||'')&&a.title):[]
 return {role:staffRoles[code]||'岗位未提供',code:staffRoles[code]?code:'',links,source}
}
