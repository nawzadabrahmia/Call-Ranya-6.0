import subprocess, sys
import os
os.chdir(os.path.dirname(os.path.abspath(__file__)))
T='tools'
CP=':'.join(['nodec','shimc',T+'/sqlite-jdbc.jar',T+'/jsonc','appc',T+'/android.jar',T+'/kotlinc/lib/kotlinx-coroutines-core-jvm.jar',T+'/kotlinc/lib/kotlin-stdlib.jar'])
nodes={}
def start(n,code,ip):
    p=subprocess.Popen(['java','-cp',CP,'NodeKt',n,code,ip],stdin=subprocess.PIPE,stdout=subprocess.PIPE,stderr=subprocess.STDOUT,text=True,bufsize=1)
    while True:
        l=p.stdout.readline()
        if not l: raise SystemExit('died '+n)
        if l.startswith('READY'): nodes[n]=(p,l.split()[2]); return
def cmd(n,c):
    p=nodes[n][0]; p.stdin.write(c+'\n'); p.stdin.flush(); out=[]
    while True:
        l=p.stdout.readline()
        if not l or l.strip()=='DONE': break
        if 'JAVA_TOOL' in l or 'SLF4J' in l: continue
        out.append(l.rstrip())
    print('\n'.join(out)); sys.stdout.flush()
start('A','11111111','127.0.0.1'); start('B','22222222','127.0.0.2'); start('C','33333333','127.0.0.3')
cmd('A','contact 33333333 '+nodes['C'][1]); cmd('C','contact 11111111 '+nodes['A'][1])
print('--- A creates: message + check-in to C (C never on A\'s network), SOS, announcement')
cmd('A','send 33333333 hello C, this is A from the camp office')
cmd('A','checkin 33333333 all good')
cmd('A','sos trapped near the school'); cmd('A','ann Water at gate 3 at 10:00')
print('--- B (data mule) meets A'); cmd('B','sync 127.0.0.1')
print('--- B walks to C\'s village, meets C'); cmd('C','sync 127.0.0.2')
print('--- B meets C again later (picks up receipts)'); cmd('B','sync 127.0.0.3')
print('--- B returns to A'); cmd('A','sync 127.0.0.2')
print('--- C meets A directly: nothing should be delivered twice'); cmd('C','sync 127.0.0.1')
for n in 'ABC': cmd(n,'csv')
for p,_ in nodes.values(): p.kill()
