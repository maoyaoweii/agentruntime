#!/usr/bin/env python3
"""真实 Android/ADB 回归；不以 Robolectric 或静态断言替代设备执行。"""
import argparse, base64, json, os, pathlib, shlex, subprocess, time
ROOT = pathlib.Path(__file__).resolve().parents[1]
ADB = os.environ.get('ADB', '/workspace/android-sdk/platform-tools/adb')
SERIAL = os.environ.get('ANDROID_SERIAL', 'emulator-5554')
PKG = 'com.agentruntime.agent'
OUT = ROOT / 'test-results/device'
OUT.mkdir(parents=True, exist_ok=True)

def adb(*args, timeout=90, check=False, binary=False):
    p = subprocess.run([ADB, '-s', SERIAL, *args], capture_output=True, timeout=timeout)
    if check and p.returncode:
        raise RuntimeError(p.stderr.decode(errors='replace'))
    return p.stdout if binary else p.stdout.decode(errors='replace').strip()

def shell(*args, **kw):
    return adb('shell', shlex.join([str(x) for x in args]), **kw)

def screenshot(name):
    data = adb('exec-out', 'screencap', '-p', binary=True)
    if not data.startswith(b'\x89PNG'):
        raise RuntimeError('screencap did not return PNG')
    path = OUT / (name + '.png'); path.write_bytes(data); return str(path)

def dump(name):
    shell('uiautomator', 'dump', '/sdcard/window.xml', timeout=120)
    data = shell('cat', '/sdcard/window.xml')
    (OUT / (name + '.xml')).write_text(data)
    return data

def task(name, steps, allowed=None, post=None):
    return {'task_version':'1.0','task_id':'device_' + name,'goal':'设备回归 ' + name,
            'allowed_apps': allowed or [],'preconditions':[],
            'steps':[{'skill':s,'args':a} for s,a in steps],
            'postconditions':post or [],
            'limits':{'max_steps':64,'max_replans':0,'timeout_seconds':240}}

def execute(name, *, spec=None, goal=None, extras=None, auto=False, timeout=300):
    adb('logcat', '-b', 'crash', '-c')
    shell('run-as', PKG, 'rm', '-f', 'files/last_result.json')
    cmd=['am','start','-n',PKG+'/com.agentruntime.ui.TaskExecActivity',
         '--ez','auto_confirm',str(auto).lower(),'--ez','allow_replan','false',
         '--ez','finish_after_run','true']
    if spec:
        (OUT / (name + '.task.json')).write_text(json.dumps(spec,ensure_ascii=False,indent=2))
        cmd += ['--es','task_json_b64',base64.b64encode(json.dumps(spec,ensure_ascii=False).encode()).decode()]
    if goal:
        cmd += ['--es','goal_b64',base64.b64encode(goal.encode()).decode()]
    for k,v in (extras or {}).items():
        cmd += ['--ez' if isinstance(v,bool) else '--ei' if isinstance(v,int) else '--es',k,str(v).lower() if isinstance(v,bool) else str(v)]
    start=time.monotonic(); launch=shell(*cmd)
    result=None
    while time.monotonic()-start < timeout:
        raw=shell('run-as',PKG,'cat','files/last_result.json',timeout=30)
        try:
            result=json.loads(raw); break
        except (json.JSONDecodeError,ValueError):
            if time.monotonic()-start > 15:
                crash=adb('logcat','-b','crash','-d','-t','80',timeout=30)
                if PKG in crash and 'FATAL EXCEPTION' in crash:
                    (OUT/(name+'.crash.txt')).write_text(crash)
                    result={'status':'APP_CRASH','launch':launch}
                    break
            time.sleep(2)
    if result is None: result={'status':'HARNESS_TIMEOUT','launch':launch}
    record={'case':name,'host_elapsed_s':round(time.monotonic()-start,2),'auto_confirm':auto,'result':result}
    (OUT / (name + '.json')).write_text(json.dumps(record,ensure_ascii=False,indent=2))
    print(json.dumps(record,ensure_ascii=False),flush=True)
    if result.get('status') in {'APP_CRASH','HARNESS_TIMEOUT'}:
        raise RuntimeError('停止设备批测：'+name+' '+result['status'])
    return record

def collect():
    (OUT/'logcat.txt').write_text(adb('logcat','-d','-t','2000','-v','threadtime',timeout=120))
    (OUT/'audit.jsonl').write_text(shell('run-as',PKG,'sh','-c','cat files/audit/*.jsonl'))

def main():
    ap=argparse.ArgumentParser();ap.add_argument('phase',choices=['baseline','permissions','smoke','chat','negative','collect']);args=ap.parse_args()
    if args.phase=='baseline':
        execute('capability_before',extras={'capability_info':True})
        execute('screenshot_without_permission',spec=task('shot_no_permission',[('sys.screenshot',{})]))
    elif args.phase=='permissions':
        shell('settings','put','secure','enabled_accessibility_services',PKG+'/com.agentruntime.service.AgentAccessibilityService')
        shell('settings','put','secure','accessibility_enabled','1')
        shell('appops','set',PKG,'SYSTEM_ALERT_WINDOW','allow')
        shell('ime','enable',PKG+'/com.agentruntime.ime.AgentImeService')
        time.sleep(5)
        execute('capability_after',extras={'capability_info':True})
        execute('environment',extras={'env_info':True})
    elif args.phase=='smoke':
        execute('observe',goal='观察当前设备环境')
        execute('toolkit',spec=task('toolkit',[('sys.toolkit',{})]))
        execute('clipboard',spec=task('clipboard',[('sys.clipboard_copy',{'text':'设备剪贴板测试 Android runtime'})]))
        execute('screenshot',spec=task('screenshot',[('sys.screenshot',{})]))
        execute('coordinate_smoke',spec=task('coordinate_smoke',[('sys.coordinate_smoke',{})]))
        execute('onnx_runtime',extras={'local_backend':'onnx','local_info':True})
    elif args.phase=='chat':
        shell('am','force-stop','com.agenttestapp')
        execute('open_chat',spec=task('open_chat',[('agent_test.open_chat',{'contact_alias':'TestUser'})],['com.agenttestapp']))
        screenshot('chat_open')
        execute('send_blocked',spec=task('send_blocked',[('agent_test.send_text',{'text':'MUST_NOT_SEND_WITHOUT_CONFIRMATION'})],['com.agenttestapp']),auto=False)
        shell('am','force-stop','com.agenttestapp')
        execute('chat_send',spec=task('chat_send',[('agent_test.open_chat',{'contact_alias':'TestUser'}),('agent_test.send_text',{'text':'DEVICE_E2E_中文发送成功'})],['com.agenttestapp']),auto=True)
        screenshot('chat_message');dump('chat_message')
        shell('am','force-stop','com.agenttestapp')
        execute('send_file',spec=task('send_file',[('agent_test.open_chat',{'contact_alias':'TestUser'}),('agent_test.send_file',{'contact_alias':'TestUser','file_alias':'report.pdf'})],['com.agenttestapp']),auto=True)
        screenshot('chat_file')
        shell('am','force-stop','com.agenttestapp')
        execute('read_wifi',spec=task('read_wifi',[('agent_test.open_settings',{}),('agent_test.read_wifi',{})],['com.agenttestapp']))
    elif args.phase=='negative':
        execute('unknown_skill',spec=task('unknown_skill',[('not.allowed',{})]))
        execute('unsafe_url',spec=task('unsafe_url',[('web.open_and_read',{'url':'javascript:alert(1)','max_chars':100})]))
    collect()

if __name__=='__main__': main()
