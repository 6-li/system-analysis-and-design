// Resolve wall-clock input using the clinic's IANA zone, never the browser's default zone.
export function localCandidates(local,zone){
 if(!/^\d{4}-\d{2}-\d{2}T\d{2}:\d{2}(:\d{2})?$/.test(local))return []
 const wanted=local.length===16?local+':00':local
 const fmt=new Intl.DateTimeFormat('sv-SE',{timeZone:zone,year:'numeric',month:'2-digit',day:'2-digit',hour:'2-digit',minute:'2-digit',second:'2-digit',hourCycle:'h23'})
 const wall=t=>fmt.format(new Date(t)).replace(' ','T')
 const nominal=Date.parse(wanted+'Z');if(!Number.isFinite(nominal))return []
 const offsets=new Set();for(let h=-36;h<=36;h+=3){const t=nominal+h*3600000;offsets.add(Date.parse(wall(t)+'Z')-t)}
 return [...offsets].map(offset=>nominal-offset).filter(t=>wall(t)===wanted).sort((a,b)=>a-b).map(t=>new Date(t).toISOString())
}
export function localText(instant,zone){return new Intl.DateTimeFormat('sv-SE',{timeZone:zone,year:'numeric',month:'2-digit',day:'2-digit',hour:'2-digit',minute:'2-digit',second:'2-digit',hourCycle:'h23'}).format(new Date(instant)).replace(' ','T')}
export function offsetText(instant,zone){const wall=localText(instant,zone);const minutes=Math.round((Date.parse(wall+'Z')-Date.parse(instant))/60000);const sign=minutes<0?'-':'+';const n=Math.abs(minutes);return wall+sign+String(Math.floor(n/60)).padStart(2,'0')+':'+String(n%60).padStart(2,'0')}
