#!/usr/bin/env python3
"""Reviewed code-only course update. Does not read secrets, run SQL, or alter configuration."""
from pathlib import Path
from datetime import datetime
import hashlib,json,os,shutil,subprocess,time,urllib.request,urllib.error
PREVIOUS_JAR='d192260e6ed1e3d63ac9443fa9603bc63b2b98aee0964433ad774c655aecd211'
PREVIOUS_INDEX='3f785cb23dc19e35ed1a27568c89c8b126183e6e518b534f3568c5e3acd258a8'
def digest(p):return hashlib.sha256(p.read_bytes()).hexdigest()
def verify(root):
 root=root.resolve();entries=json.loads((root/'manifest.json').read_text(encoding='utf-8'))['entries'];names=[e['file'] for e in entries]
 if len(names)!=len(set(names)) or not {'install.py','backend.jar','staff/index.html','RUNBOOK.md'}.issubset(names):raise ValueError('INVALID_PACKAGE_CONTENTS')
 for e in entries:
  name=e['file'];p=(root/name).resolve()
  allowed=name in {'install.py','backend.jar','staff/index.html','RUNBOOK.md'} or name.startswith('staff/assets/') and len(Path(name).parts)==3 and Path(name).suffix in {'.js','.css'}
  if not allowed or not p.is_relative_to(root) or (root/name).is_symlink() or not p.is_file() or digest(p)!=e['sha256']:raise ValueError('INVALID_PACKAGE_PATH_OR_HASH')
 return entries

def preflight(base):
 jar=base/'backend.jar';staff=base/'staff';index=staff/'index.html'
 for p in [base,jar,staff,staff/'assets',index]:
  if p.is_symlink():raise ValueError('SYMLINK_TARGET_DENIED')
 if not jar.is_file() or not index.is_file() or not (staff/'assets').is_dir():raise ValueError('PRIOR_INSTALL_REQUIRED')
 if any(p.is_symlink() for p in staff.rglob('*')):raise ValueError('SYMLINK_ASSET_DENIED')
 if digest(jar)!=PREVIOUS_JAR or digest(index)!=PREVIOUS_INDEX:raise ValueError('PRIOR_VERSION_DIFFERS_STOP_NO_OVERWRITE')
 if (base/'backend.jar.staff-experience.pending').exists() or (staff/'index.html.staff-experience.pending').exists():raise ValueError('PENDING_UPDATE_REQUIRES_REVIEW')
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

def main():
 if os.geteuid()!=0:raise SystemExit('Run with sudo python3')
 os.umask(0o077);root=Path(__file__).resolve().parent;entries=verify(root)
 if not Path('/etc/adc-course/ui-unification.env').is_file():raise SystemExit('APPROVED_PRIOR_UI_CONFIG_REQUIRED')
 base=Path('/opt/adc-course');jar,staff,index=preflight(base)
 for e in entries:
  if e['file'].startswith('staff/assets/'):
   target=staff/'assets'/Path(e['file']).name
   if target.exists() and digest(target)!=e['sha256']:raise SystemExit('ASSET_NAME_CONFLICT_STOP')
 evidence=Path('/var/lib/adc-course/staff-experience-'+datetime.now().strftime('%Y%m%d%H%M%S%f'));evidence.mkdir(mode=0o700)
 shutil.copy2(jar,evidence/'backend-before.jar');shutil.copytree(staff,evidence/'staff-before')
 jar_pending=base/'backend.jar.staff-experience.pending';index_pending=staff/'index.html.staff-experience.pending'
 try:
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
  rollback=subprocess.run(['systemctl','restart','adc-course'],check=False).returncode
  (evidence/'rollback.json').write_text(json.dumps({'restoredPreviousCode':True,'restartExit':rollback,'databaseChanged':False}))
  raise SystemExit('UPDATE_FAILED_PREVIOUS_CODE_RESTORED; inspect evidence: '+str(evidence))
 (evidence/'startup.json').write_text(json.dumps({'requiresLogin':True,'businessVerified':False,'databaseChanged':False,'configurationChanged':False,'passwordsChanged':False}))
 print('ADC_STAFF_EXPERIENCE_READY');print('Evidence: '+str(evidence));print('Startup only. Verify current role, authorized navigation, dentist appointment-to-visit lookup and billing layout. No SQL, credentials or business facts changed.')
if __name__=='__main__':main()
