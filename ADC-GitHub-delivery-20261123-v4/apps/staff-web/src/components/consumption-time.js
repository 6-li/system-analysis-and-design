export function clinicInstant(value){
 if(typeof value!=='string'||!/^\d{4}-\d\d-\d\dT\d\d:\d\d(?::\d\d)?$/.test(value))return ''
 const normalized=value.length===16?value+':00':value
 const parsed=Date.parse(normalized+'+08:00')
 if(!Number.isFinite(parsed)||new Date(parsed+8*3600000).toISOString().slice(0,19)!==normalized)return ''
 return normalized+'+08:00'
}
export function courseCost(quantity){
 if(typeof quantity!=='string'||!/^\d{1,15}(\.\d{1,3})?$/.test(quantity))return ''
 const [whole,decimal='']=quantity.split('.'),mills=BigInt(whole)*1000n+BigInt(decimal.padEnd(3,'0'))
 if(mills<=0n)return ''
 const cents=(mills*200n+500n)/1000n
 return (cents/100n)+'.'+String(cents%100n).padStart(2,'0')
}
