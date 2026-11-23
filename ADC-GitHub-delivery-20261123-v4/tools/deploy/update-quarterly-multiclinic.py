#!/usr/bin/env python3
"""Reviewed quarterly update; one synthetic clinic seed, no schema or grants."""
from pathlib import Path
from datetime import datetime
import hashlib,json,os,shutil,subprocess,time,urllib.request,urllib.error
PREVIOUS_JAR='550655364ac2d30d85dd95a456c71d47d1cd56bbd7c0adb406c292d96efc0d2c'
PREVIOUS_INDEX='4474586a3c2d2451b55b60940cf39bc21eb9c440c66c2efefcecd3ce90ecb26a'
def digest(p):return hashlib.sha256(p.read_bytes()).hexdigest()
def verify(root):
 root=root.resolve();entries=json.loads((root/'manifest.json').read_text(encoding='utf-8'))['entries'];names=[e['file'] for e in entries]
 if len(names)!=len(set(names)) or not {'install.py','backend.jar','staff/index.html','RUNBOOK.md','quarterly-expenses.json','clinic-81002.sql'}.issubset(names):raise ValueError('INVALID_PACKAGE_CONTENTS')
 for e in entries:
  name=e['file'];p=(root/name).resolve()
  allowed=name in {'install.py','backend.jar','staff/index.html','RUNBOOK.md','quarterly-expenses.json','clinic-81002.sql'} or name.startswith('staff/assets/') and len(Path(name).parts)==3 and Path(name).suffix in {'.js','.css'}
  if not allowed or not p.is_relative_to(root) or (root/name).is_symlink() or not p.is_file() or digest(p)!=e['sha256']:raise ValueError('INVALID_PACKAGE_PATH_OR_HASH')
 return entries

def preflight(base):
 jar=base/'backend.jar';staff=base/'staff';index=staff/'index.html'
 for p in [base,jar,staff,staff/'assets',index]:
  if p.is_symlink():raise ValueError('SYMLINK_TARGET_DENIED')
 if not jar.is_file() or not index.is_file() or not (staff/'assets').is_dir():raise ValueError('PRIOR_INSTALL_REQUIRED')
 if any(p.is_symlink() for p in staff.rglob('*')):raise ValueError('SYMLINK_ASSET_DENIED')
 if digest(jar)!=PREVIOUS_JAR or digest(index)!=PREVIOUS_INDEX:raise ValueError('PRIOR_VERSION_DIFFERS_STOP_NO_OVERWRITE')
 if (base/'backend.jar.quarterly-multiclinic.pending').exists() or (staff/'index.html.quarterly-multiclinic.pending').exists():raise ValueError('PENDING_UPDATE_REQUIRES_REVIEW')
 return jar,staff,index

def ready():
 opener=urllib.request.build_opener(urllib.request.ProxyHandler({}))
 for _ in range(40):
  try:opener.open('http://127.0.0.1:8080/api/v1/staff/my-visits/by-appointment/1',timeout=3)
  except urllib.error.HTTPError as e:
   if e.code==401:return True
  except (OSError,urllib.error.URLError):pass
  time.sleep(1)
 return False

def database(sql):
 env={'PATH':'/usr/sbin:/usr/bin:/sbin:/bin','LANG':'C.UTF-8','PGPASSFILE':'/dev/null','PGCONNECT_TIMEOUT':'5','PGOPTIONS':'-c statement_timeout=20000 -c log_statement=none -c log_min_error_statement=panic'}
 return subprocess.run(['sudo','-n','-E','-u','postgres','psql','-X','-w','-qAt','-h','/var/run/postgresql','-p','55432','-d','adc_course_validation','-v','ON_ERROR_STOP=1'],env=env,input=sql,text=True,capture_output=True,timeout=60)

def main():
 if os.geteuid()!=0:raise SystemExit('Run with sudo python3')
 os.umask(0o077);root=Path(__file__).resolve().parent;entries=verify(root)
 if not Path('/etc/adc-course/quarterly-usability.env').is_file():raise SystemExit('APPROVED_PRIOR_UI_CONFIG_REQUIRED')
 base=Path('/opt/adc-course');jar,staff,index=preflight(base)
 expenses=base/'quarterly-expenses.json'
 if expenses.is_symlink() or not expenses.is_file() or digest(expenses)!='bb7395806074c1f106a3c05afc9fcd0033fdba76e1c3b43d04b5a27a3415cfaa':raise SystemExit('EXPENSE_SOURCE_DIFFERS_STOP')
 if (base/'quarterly-expenses.json.quarterly-multiclinic.pending').exists():raise SystemExit('PENDING_SOURCE_REQUIRES_REVIEW')
 for e in entries:
  if e['file'].startswith('staff/assets/'):
   target=staff/'assets'/Path(e['file']).name
   if target.exists() and digest(target)!=e['sha256']:raise SystemExit('ASSET_NAME_CONFLICT_STOP')
 evidence=Path('/var/lib/adc-course/quarterly-multiclinic-'+datetime.now().strftime('%Y%m%d%H%M%S%f'));evidence.mkdir(mode=0o700)
 shutil.copy2(jar,evidence/'backend-before.jar');shutil.copytree(staff,evidence/'staff-before');shutil.copy2(expenses,evidence/'expenses-before.json')
 jar_pending=base/'backend.jar.quarterly-multiclinic.pending';index_pending=staff/'index.html.quarterly-multiclinic.pending'
 expense_pending=base/'quarterly-expenses.json.quarterly-multiclinic.pending'
 seed_attempted=False
 try:
  seed_attempted=True
  seed=database((root/'clinic-81002.sql').read_text(encoding='utf-8'))
  (evidence/'clinic-seed.json').write_text(json.dumps({'exitCode':seed.returncode,'scope':'Only clinic 81002 directory; retain on rollback'}))
  if seed.returncode:raise RuntimeError('CLINIC_SEED_UNCONFIRMED_REVIEW_TARGET')
  shutil.copyfile(root/'quarterly-expenses.json',expense_pending);os.chmod(expense_pending,0o644);expense_pending.replace(expenses)
  for e in entries:
   if e['file'].startswith('staff/assets/'):
    target=staff/'assets'/Path(e['file']).name;shutil.copyfile(root/e['file'],target);os.chmod(target,0o644)
  shutil.copyfile(root/'backend.jar',jar_pending);os.chmod(jar_pending,0o644)
  shutil.copyfile(root/'staff/index.html',index_pending);os.chmod(index_pending,0o644)
  jar_pending.replace(jar);index_pending.replace(index)
  subprocess.run(['systemctl','restart','adc-course'],check=True)
  if not ready():raise RuntimeError('STARTUP_UNCONFIRMED')
 except Exception:
  shutil.copyfile(evidence/'backend-before.jar',jar_pending);os.chmod(jar_pending,0o644);jar_pending.replace(jar)
  shutil.copyfile(evidence/'staff-before/index.html',index_pending);os.chmod(index_pending,0o644);index_pending.replace(index)
  shutil.copyfile(evidence/'expenses-before.json',expense_pending);os.chmod(expense_pending,0o644);expense_pending.replace(expenses)
  rollback=subprocess.run(['systemctl','restart','adc-course'],check=False).returncode
  (evidence/'rollback.json').write_text(json.dumps({'restoredPreviousCode':True,'restartExit':rollback,'clinicSeedAttempted':seed_attempted,'clinicalFactsChanged':False}))
  raise SystemExit('UPDATE_FAILED_PREVIOUS_CODE_RESTORED; inspect evidence: '+str(evidence))
 (evidence/'startup.json').write_text(json.dumps({'requiresLogin':True,'businessVerified':False,'clinicSeedAttempted':seed_attempted,'clinicalFactsChanged':False,'expenseSourceReplaced':True,'passwordsChanged':False}))
 print('ADC_QUARTERLY_MULTI_READY');print('Evidence: '+str(evidence));print('Startup only. Verify selected quarter/clinics, correction, calculation and finance review/export. Synthetic clinic 81002 directory added or verified; no clinical facts, schema, grants or passwords changed.')
if __name__=='__main__':main()
