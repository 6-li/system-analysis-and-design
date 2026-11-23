// Playwright-owned child processes only. Never starts database or business services.
const {spawn}=require('node:child_process');const path=require('node:path');const fs=require('node:fs');
const root=path.resolve(__dirname,'..');const mode=process.argv[2];let executable,args,env={...process.env};
if(mode==='backend'){
 const jdk=process.env.ADC_JAVA_HOME || (fs.existsSync('C:/JAVA/jdk-21/bin/java.exe') ? 'C:/JAVA/jdk-21' : process.env.JAVA_HOME);
 if(!jdk)throw new Error('JDK21 unavailable for technical server');
 executable=path.join(jdk,'bin/java.exe');
 const jar=path.join(root,'backend/target/adc-backend-0.0.1-SNAPSHOT.jar');
 if(!fs.existsSync(jar))throw new Error('Build backend package before browser tests');
 args=['-jar',jar,'--server.address=127.0.0.1','--server.port=18080'];
}else if(mode==='frontend'){
 executable=process.execPath;args=[path.join(root,'apps/staff-web/node_modules/vite/bin/vite.js'),'--host','127.0.0.1','--port','15173','--strictPort'];
 env.ADC_TECHNICAL_BACKEND_URL='http://127.0.0.1:18080';
}else throw new Error('Expected backend or frontend');
const child=spawn(executable,args,{cwd:path.join(root,'apps/staff-web'),env,stdio:'inherit',windowsHide:true});
child.on('error',()=>{console.error('Technical test server could not start');process.exitCode=1});
child.on('exit',code=>{process.exitCode=code===null?1:code});
for(const signal of ['SIGINT','SIGTERM'])process.on(signal,()=>{child.kill(signal)});
