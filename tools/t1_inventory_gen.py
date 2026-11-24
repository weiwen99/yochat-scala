import os,re,csv
from pathlib import Path
ref=Path(os.environ.get('YOCHAT_REFERENCE', Path.home() / 'projects' / 'yochat-reference'))
out=Path('docs/plans/2026/11/0001-yochat-rewrite/test-parity-tests.csv')
parity=Path('docs/plans/2026/11/0001-yochat-rewrite/test-parity.md').read_text()
reqs={}
for line in parity.splitlines():
    if not line.startswith('| `'): continue
    cells=[c.strip().strip('`') for c in line.strip().strip('|').split('|')]
    if len(cells)>=9 and re.fullmatch(r'[A-Z0-9]{16}',cells[0]):
        reqs[cells[0]]={'id':cells[0],'requirement':cells[1],'new':cells[4],'disposition':cells[5],'coverage':cells[7]}
maps={
'app_lock.rs':['B9Y4E73VMQ8RS99X','YQW27DXZJSHAR0Z0'],'keyfile.rs':['4DYMP7NVJDKNPTKR'],'store_at_rest.rs':['ZTSEXMZQ2VHS21R6'],
'invite.rs':['2276A5JCK1KGNS1A','6J1EMT8Y9HZ68STG'],'reply.rs':['GXF8WYYMWT40CFSM','37F049FH3B007X48'],'exchange.rs':['1VXJQ68HXHM0JR0Q','YP7HYFDC5BSKK1BP'],
'relationship.rs':['1VXJQ68HXHM0JR0Q'],'safety.rs':['FXAWEFJ6Q0S22E5S'],'invite_lifecycle.rs':['6J1EMT8Y9HZ68STG'],
'ordered_apply.rs':['BXC7RNH3YCQZ9BQY','S02F3SXHZY2DHA87'],'claimed_time.rs':['BXC7RNH3YCQZ9BQY'],'state_roundtrip.rs':['C2TDVVB9MGCY30KM'],
'tests.rs':['S02F3SXHZY2DHA87'],'fallback_resend.rs':['VBRZ8N5AC8QR0N3B'],'retransmit_dispatch.rs':['VBRZ8N5AC8QR0N3B'],'sustained_undecryptable.rs':['2MTCJFK2TX0E9ACY'],
'admission.rs':['RYGFPP529TDA9R6X','MXHYRG96W0VKB031'],'inbound_discard.rs':['MXHYRG96W0VKB031'],'inbound_gate.rs':['RYGFPP529TDA9R6X','MXHYRG96W0VKB031'],
'delivery.rs':['JW13382752N6T1XR','DCNS38550F4AY1K5','N2K56BGH73GX0YCB'],'ordering.rs':['S02F3SXHZY2DHA87'],'presentation_order.rs':['JW13382752N6T1XR'],
'resend.rs':['VBRZ8N5AC8QR0N3B'],'rebuild.rs':['2MTCJFK2TX0E9ACY','Y7C40JC6A0EWEDT5'],'send_retry.rs':['VBRZ8N5AC8QR0N3B'],'journal_crash.rs':['C2TDVVB9MGCY30KM'],
'journal_faults.rs':['C2TDVVB9MGCY30KM'],'journal_parity.rs':['C2TDVVB9MGCY30KM'],
'invite.e2e.ts':['2276A5JCK1KGNS1A'],'exchange.e2e.ts':['1VXJQ68HXHM0JR0Q'],'identity.e2e.ts':['4DYMP7NVJDKNPTKR'],'identity-restart.e2e.ts':['4DYMP7NVJDKNPTKR'],
'backend-restart.e2e.ts':['C2TDVVB9MGCY30KM'],'backend-restart-checkpoint.e2e.ts':['C2TDVVB9MGCY30KM'],'idle-lock.e2e.ts':['YQW27DXZJSHAR0Z0'],
'log-secrets.e2e.ts':['865PCSHV46Q4X2XF'],'diagnostics.e2e.ts':['DVFN7QEAWP32QD9G'],'late-arrival.e2e.ts':['BXC7RNH3YCQZ9BQY'],
'resend.e2e.ts':['VBRZ8N5AC8QR0N3B'],'rebuild.e2e.ts':['2MTCJFK2TX0E9ACY'],'manual-maintenance.e2e.ts':['VBRZ8N5AC8QR0N3B']}
ui_map={'identity-flow.test.ts':['4DYMP7NVJDKNPTKR','B9Y4E73VMQ8RS99X'],'invite-flow.test.ts':['2276A5JCK1KGNS1A','37F049FH3B007X48'],'exchange-flow.test.ts':['1VXJQ68HXHM0JR0Q','YP7HYFDC5BSKK1BP'],'invite-copy.test.ts':['2276A5JCK1KGNS1A'],'idle-lock.test.ts':['YQW27DXZJSHAR0Z0'],'chat-flow.test.ts':['JW13382752N6T1XR','N2K56BGH73GX0YCB'],'log-secrets.test.ts':['865PCSHV46Q4X2XF'],'diagnostics.test.ts':['DVFN7QEAWP32QD9G']}
records=[]
def disposition(mapping):
    if not mapping: return 'OMIT_CANDIDATE'
    statuses={reqs[m]['disposition'] for m in mapping if m in reqs}
    if 'REWRITE' in statuses: return 'REWRITE_CANDIDATE'
    if 'PORT' in statuses: return 'PORT_CANDIDATE'
    if 'GAP' in statuses: return 'GAP_CANDIDATE'
    return 'PENDING_ADJUDICATION'
def add(layer,path,name,mapping,reason=''):
    rel=str(path.relative_to(ref)); mapping=[m for m in mapping if m in reqs]
    records.append({'layer':layer,'source':rel,'test':name,'requirement_ids':','.join(mapping),'requirement_disposition':','.join(reqs[m]['disposition'] for m in mapping),'requirement_coverage':','.join(reqs[m]['coverage'] for m in mapping),'proposed_disposition':disposition(mapping),'new_evidence':'; '.join(reqs[m]['new'] for m in mapping),'reason':reason,'decision':'','signed_by':'','signed_at':''})
for root,dirs,files in os.walk(ref/'crates'):
    for f in files:
        if not f.endswith('.rs'): continue
        p=Path(root)/f; text=p.read_text(errors='ignore')
        rust=re.findall(r'#\[(?:tokio::)?test\]\s*(?:async\s+)?fn\s+(\w+)',text)
        kani=set(re.findall(r'#\[kani::proof\][\s\S]{0,400}?fn\s+(\w+)',text))
        names=rust+list(kani)
        if 'proptest!' in text: names.append('proptest-block')
        mapping=maps.get(f,[])
        for n in names:
            add('kani' if n in kani else 'rust',p,n,mapping,'' if mapping else 'not in S1 candidate file map')
for p in sorted((ref/'e2e/specs').glob('*.e2e.ts')):
    for n in re.findall(r'\b(?:it|test)\s*\(\s*["\'`]([^"\'`]+)',p.read_text(errors='ignore')):
        add('e2e',p,n,maps.get(p.name,[]),'' if maps.get(p.name) else 'not in S1 E2E candidate list')
for p in sorted((ref/'ui/tests').glob('*.test.ts')):
    for n in re.findall(r'\b(?:it|test)\s*\(\s*["\'`]([^"\'`]+)',p.read_text(errors='ignore')):
        add('ui',p,n,ui_map.get(p.name,[]),'' if p.name in ui_map else 'S1 UI file not identified')
native=['background','basic','multiremote','admission-pressure','always-on','approval','approval-transfer','blocking','blocking-sustained','contact-bootstrap','contact-cold-boundary','contact-removal','contact-takeover','device-alerts','device-alias','diagnostics','history-reading','idle-lock','manual-maintenance','native-focus','own-enrollment','phrase-enrollment','profile','reading','scale','ui-send','resend','multiremote-history-10000','instances-attach-smoke','instances-two-users','instances-same-user-abandon','backend-restart-setup','backend-restart-restore','notifications-system','notifications-system-click']
for n in native:
    add('native',ref/'tools/run_native_suite.py',n,[], 'native harness; replacement/human decision required')
# stable IDs
for r in records:
    if r['layer']=='kani':
        r['proposed_disposition']='GAP_CANDIDATE'
        r['reason']='Kani has no Scala equivalent; accept gap and property-test replacement' if not r['reason'] else r['reason']
for i,r in enumerate(records,1): r['row_id']=f'T1-{i:04d}'
fields=['row_id','layer','source','test','requirement_ids','requirement_disposition','requirement_coverage','proposed_disposition','new_evidence','reason','decision','signed_by','signed_at']
with out.open('w',newline='') as fh:
    w=csv.DictWriter(fh,fieldnames=fields); w.writeheader(); w.writerows(records)
from collections import Counter
print('rows',len(records)); print(Counter(r['layer'] for r in records)); print(Counter(r['proposed_disposition'] for r in records)); print('mapped',sum(bool(r['requirement_ids']) for r in records))
