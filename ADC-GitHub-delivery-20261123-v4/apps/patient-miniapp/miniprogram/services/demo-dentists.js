// Explicit SYNTHETIC course directory, matching the existing fixture. Never a real qualification source.
const rows=[{dentistId:'79001',dentistLabel:'演示牙医甲',clinics:['81001','81002']},
 {dentistId:'79002',dentistLabel:'演示牙医乙',clinics:['81001']},{dentistId:'79003',dentistLabel:'演示牙医丙',clinics:['81001']}];
module.exports={demoDentists:clinic=>rows.filter(d=>d.clinics.includes(clinic)).map(({dentistId,dentistLabel})=>({dentistId,dentistLabel}))};
