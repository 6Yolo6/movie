"""Independent completion of four remaining SSR checks after frozen Entry V3 STOP.
Default/import has no runtime I/O. Reuses 67 PASS detail/list and two movie SSR proofs;
execution reads all selected rows/runtime afresh and sends only four anonymous SSR GETs.
"""
import argparse,datetime as dt,json,os,re,sys
from pathlib import Path
import EntryAcceptanceV3 as V3
E=V3.E;ROOT=Path(__file__).resolve().parent
BINDING_FILE=ROOT/'entry-ssr-completion-binding.json'
PRIOR_V3_SHA='515fdb2615a3cd7ced1b4afeabaa5c2c10f33509985932292bf5089e489e8305'
PRIOR_STOP_SHA='5250df4151fd79272cabefcc4238c2b52f6bae2ad9f7908adbea4371582c9d25'
OBSERVATION_SHA='3a04e6ea924672f5d135a0aa2561d27e59a96910dc5d41d9322c546a6bced2a6'
SCOPE={'actualPairs':67,'reusedDetailChecks':67,'reusedListChecks':67,'reusedMovieSsrChecks':2,'remainingSsrIds':['tmdb_tv_93740','4YNV','tmdb_tv_247718','1YKW'],'maximumAnonymousSsrGets':4,'apiDetailGets':0,'apiListGets':0,'postRequests':0,'operatorDirectSqlWrites':0,'cloudActions':0,'yearMustBeIndependentH2Span':True,'allSelectedRowsBaiduBlockedAndThirteenGenerationsRequired':True}

class H2YearSpans(E.html.parser.HTMLParser):
 """Capture complete independent span text only while the node is inside h2."""
 VOID={'area','base','br','col','embed','hr','img','input','link','meta','param','source','track','wbr'}
 def __init__(self):super().__init__(convert_charrefs=True);self.stack=[];self.active=[];self.values=[]
 def handle_starttag(self,tag,attrs):
  if tag in self.VOID:return
  self.stack.append(tag)
  if tag=='span' and 'h2' in self.stack[:-1]:self.active.append({'depth':len(self.stack),'text':[]})
 def handle_startendtag(self,tag,attrs):
  self.handle_starttag(tag,attrs)
  if tag not in self.VOID:self.handle_endtag(tag)
 def handle_endtag(self,tag):
  if tag in self.VOID or tag not in self.stack:return
  index=len(self.stack)-1-self.stack[::-1].index(tag)
  for node in self.active:
   if tag=='span' and node['depth']==index+1:
    self.values.append(''.join(node['text']).strip());E.need(len(self.values)<=1024,'BOUNDED_H2_SPAN_PROJECTION')
  self.active=[node for node in self.active if node['depth']<=index]
  del self.stack[index:]
 def handle_data(self,data):
  if 'script' not in self.stack and 'style' not in self.stack:
   for node in self.active:node['text'].append(data)

def exact_year_span(values,year):
 E.need(type(year) is int and 0<year<=9999,'ACTUAL_RENDERED_YEAR_INTEGER_REQUIRED')
 return sum(value==str(year) for value in values)==1

def revision_binding(expected):
 E.need(isinstance(expected,str) and re.fullmatch('[0-9a-f]{64}',expected) and E.sha(BINDING_FILE)==expected,'EXPLICIT_EXACT_SSR_COMPLETION_BINDING_SHA')
 value=E.read(BINDING_FILE);E.need(value.get('version')==1 and value.get('scope')==SCOPE,'EXACT_FOUR_REMAINING_SSR_SCOPE')
 helpers=value['helperFiles'];E.need(helpers==[E.reference(__file__)],'EXACT_SSR_COMPLETION_HELPER_BYTES')
 prior=value['entryV3Binding'];E.need(Path(prior['path']).resolve()==ROOT/'entry-acceptance-v3-binding.json' and prior['sha256']==PRIOR_V3_SHA,'FROZEN_ENTRY_V3_BINDING_REQUIRED');E.bound(prior['path'],prior['sha256']);V3.BINDING_SHA=prior['sha256'];V3.revision_binding()
 for field,expected_sha in [('entryV3StoppedReceipt',PRIOR_STOP_SHA),('headerObservation',OBSERVATION_SHA)]:
  item=value[field];E.need(item['sha256']==expected_sha,'EXACT_FROZEN_SSR_COMPLETION_EVIDENCE');E.bound(item['path'],item['sha256'])
 stopped=E.bound(value['entryV3StoppedReceipt']['path'],PRIOR_STOP_SHA);E.need(stopped.get('status')=='STOPPED' and stopped.get('failure',{}).get('code')=='SSR_RENDERED_ACTUAL_TITLE_AND_YEAR' and stopped.get('actualMergedPairs')==67,'ACTUAL_ENTRY_V3_HEADER_STOP_REQUIRED')
 E.need(len(stopped.get('pairChecks',[]))==len(stopped.get('listChecks',[]))==67 and all(x.get('status')=='PASS' for x in stopped['pairChecks']+stopped['listChecks']) and len(stopped.get('ssrChecks',[]))==2 and all(x.get('status')=='PASS' and x.get('category')=='mv' for x in stopped['ssrChecks']),'EXACT_67_DETAIL_LIST_AND_TWO_MOVIE_SSR_PASS_PROOFS')
 E.need(all(stopped.get(k) is True for k in ['selectedCompleteRowsFinallyRetained','oldBaiduStateFinallyRetained','runtimeFinallyRetained','permanentOriginalBlockedPairCompleteRowsRetained']),'ACTUAL_STOPPED_ENTRY_ALL_FINAL_GUARDS_PASS')
 observation=E.bound(value['headerObservation']['path'],OBSERVATION_SHA);o=observation.get('observation',{});E.need(observation.get('status')=='READ_ONLY_OBSERVED' and o.get('actualTitleInH1') is True and o.get('oldBoundedYearRegexMatches') is False and o.get('exactRenderedYearSpanPresent') is True,'ACTUAL_ISOLATED_YEAR_NODE_FALSE_BLOCK_OBSERVED')
 for field in ['tests','offlineReview']:
  item=value[field];record=E.bound(item['path'],item['sha256']);E.need(record.get('status')=='PASS' and record.get('allTestsPassed') is True and record.get('helperFiles')==helpers and record.get('productionPosts')==0,'SSR_COMPLETION_OFFLINE_PASS_REQUIRED')
 derivation=E.bound(value['derivation']['path'],value['derivation']['sha256']);E.need(derivation.get('helperFiles')==helpers and derivation.get('scope')==SCOPE and derivation.get('entryV3Binding')==prior,'EXACT_SSR_COMPLETION_DERIVATION')
 return value,stopped

class ScopedGetter(E.Getter):
 def get(self,path,display,json_response=True):
  E.need(not json_response and path in ['/movie/'+x for x in SCOPE['remainingSsrIds']] and self.report['nginxHttpGetRequests']<4,'ONLY_FOUR_REMAINING_ANONYMOUS_SSR_GETS')
  return super().get(path,display,False)

def blocked_guard(prior):
 E.need(V3.BLOCKED_STATE is not None,'INHERITED_PERMANENT_BLOCKED_COMPLETE_PREIMAGE_REQUIRED')
 M,C,R,blocked,expected=V3.BLOCKED_STATE;actual=M.state(C,R,blocked)
 E.need(actual==expected and E.safe_snapshot(actual)==prior['permanentBlockedCompleteRowsFinally'],'PERMANENT_BLOCKED_COMPLETE_ROWS_MATCH_ENTRY_V3_FINALLY')
 return E.safe_snapshot(actual)

def observed_guard(prior,selected,baidu,runtime):
 E.need(E.safe_snapshot(selected)==prior['selectedFullRowsFinally'],'ALL_67_PAIR_COMPLETE_ROWS_MATCH_ENTRY_V3_FINALLY')
 E.need(baidu==prior['baiduFinally'] and runtime==prior['runtimeFinally'],'BAIDU_AND_THIRTEEN_GENERATIONS_MATCH_ENTRY_V3_FINALLY')

def save(report):
 p=ROOT/('entry-ssr-completion-'+dt.datetime.now(dt.timezone.utc).strftime('%Y%m%dT%H%M%S.%fZ')+'.json')
 with p.open('x',encoding='utf-8',newline='\n') as f:json.dump(report,f,ensure_ascii=False,indent=2);f.write('\n');f.flush();os.fsync(f.fileno())
 return E.reference(p)

def execute(args):
 report={'version':1,'status':'STARTED','type':'ENTRY_V3_REMAINING_SSR_COMPLETION','startedAt':E.now(),'helperSha256':E.sha(__file__),'nginxHttpGetRequests':0,'httpReads':[],'ssrChecks':[],'postRequests':0,'operatorDirectSqlWrites':0,'cloudActions':0,'apiDetailGets':0,'apiListGets':0,'anonymousOnly':True,'responseBodiesOrProviderUrlsWritten':False,'normalMonitoringWritesMayOccur':True,'crossServiceAtomicSnapshot':False}
 M=C=R=B=None;before=baidu_before=runtime_before=prior=None;ids=[]
 try:
  binding,prior=revision_binding(args.ssr_binding_sha);report['binding']=E.reference(BINDING_FILE);report['reusedEntryV3Receipt']=binding['entryV3StoppedReceipt'];report['headerObservation']=binding['headerObservation']
  V3.INPUTS=V3.ACTIVE_PLAN=V3.BLOCKED_STATE=None;V3.SPLIT_VERIFIED=False;M=V3.load_original();plan,base,R,B,C,A,H,dep=M.execution_inputs()
  with C.weekly['lock'](C.MAINT):
   pairs,proofs,blocked=V3.completions(M,plan,args);E.need(len(pairs)==67 and [(p['fromMovieId'],p['toMovieId']) for p in pairs]==[(p['sourceId'],p['targetId']) for p in prior['pairChecks']] and [(p['sourceId'],p['targetId']) for p in prior['listChecks']]==[(p['fromMovieId'],p['toMovieId']) for p in pairs] and blocked==prior['unmergedStagedPairs'],'EXACT_SAME_67_CURRENT_COMPLETED_PAIRS_AND_BLOCKED_SCOPE')
   report['actualMergedPairs']=67;report['completedInputs']=proofs;report['unmergedStagedPairs']=blocked;report['currentSplitDatabaseReviewsVerified']=V3.SPLIT_VERIFIED
   ids=[p[k] for p in pairs for k in ['fromMovieId','toMovieId']];before=E.snapshot(M,C,R,ids);baidu_before=E.protected(M,C,R,B);runtime_before=E.runtime(M,C,R,dep,base);observed_guard(prior,before,baidu_before,runtime_before);report['selectedFullRowsBefore']=E.safe_snapshot(before);report['baiduBefore']=baidu_before;report['runtimeBefore']=runtime_before;report['permanentBlockedCompleteRowsBefore']=blocked_guard(prior)
   movies={m['id']:m for m in before['movie_metadata']};projection={p['targetId']:p['dto'] for p in prior['pairChecks']}
   movie=next(p for p in pairs if p['kind']!='MobLand' and movies[p['toMovieId']].get('category')=='mv');tv=next(p for p in pairs if p['kind']!='MobLand' and movies[p['toMovieId']].get('category')=='tv');mobland=next(p for p in pairs if p['kind']=='MobLand')
   E.need([(s['requestedId'],s['targetId']) for s in prior['ssrChecks']]==[(movie['fromMovieId'],movie['toMovieId']),(movie['toMovieId'],movie['toMovieId'])] and [p[k] for p in [tv,mobland] for k in ['fromMovieId','toMovieId']]==SCOPE['remainingSsrIds'],'EXACT_PRIOR_TWO_MOVIE_AND_REMAINING_FOUR_SSR_REPRESENTATIVES')
   report['reusedPassedChecks']={'detailPairs':67,'listPairs':67,'movieSsr':prior['ssrChecks']};getter=ScopedGetter(report)
   for pair in [tv,mobland]:
    target=pair['toMovieId'];metadata=movies[target];expected=projection[target];namespace=E.target_binding(pair,metadata,before['movie_source_identity']);visible=E.visible_ids(before['resource_link'],target)
    E.need(expected['canonicalId']==target and expected['tmdbId']==metadata.get('tmdb_id') and expected['tmdbType']==metadata.get('tmdb_type') and E.exact_metadata_season(expected['season'],metadata.get('season')) and expected['year']==metadata.get('year') and expected['resourceIds']==visible and expected['resourceCount']==len(visible) and metadata.get('status')=='ACTIVE' and metadata.get('deleted_at') is None,'REUSED_API_PROJECTION_MATCHES_CURRENT_METADATA_AND_VISIBLE_RESOURCES')
    for identity in [pair['fromMovieId'],target]:
     body,http=getter.get('/movie/'+identity,'/movie/'+identity,False);head=E.Headlines();head.feed(body);year_nodes=H2YearSpans();year_nodes.feed(body);found=[E.dto_projection(dto) for dto in E.flight_dtos(body) if dto['movie'].get('id')==target]
     E.need(found and all(dto==expected for dto in found),'SSR_COMPLETE_CANONICAL_DTO_AND_RESOURCE_PROJECTION_EQUALS_VERIFIED_API');E.need(metadata.get('title_cn') in head.h1 and 'Service Unavailable' not in head.h1,'SSR_RENDERED_EXACT_CANONICAL_H1_TITLE');E.need(exact_year_span(year_nodes.values,metadata.get('year')),'SSR_RENDERED_YEAR_IS_EXACT_INDEPENDENT_H2_SPAN')
     report['ssrChecks'].append({'requestedId':identity,'targetId':target,'kind':pair['kind'],'status':'PASS','year':metadata['year'],'exactIndependentYearSpan':True,'matchingYearSpanCount':sum(x==str(metadata['year']) for x in year_nodes.values),'h1ProjectionSha256':E.digest(head.h1),'h2SpanProjectionSha256':E.digest(year_nodes.values),'actualTitleAndYearRendered':True,'completeCanonicalDtoEqualsVerifiedApi':True,'canonicalDtoProjection':expected,**namespace});body=None
   after=E.snapshot(M,C,R,ids);baidu_after=E.protected(M,C,R,B);runtime_after=E.runtime(M,C,R,dep,base);E.need(after==before and baidu_after==baidu_before and runtime_after==runtime_before,'FOUR_SSR_WINDOW_ALL_SELECTED_ROWS_BAIDU_AND_RUNTIME_RETAINED');observed_guard(prior,after,baidu_after,runtime_after);report['selectedFullRowsAfter']=E.safe_snapshot(after);report['baiduAfter']=baidu_after;report['runtimeAfter']=runtime_after;report['permanentBlockedCompleteRowsAfter']=blocked_guard(prior);E.need(len(report['ssrChecks'])==report['nginxHttpGetRequests']==4,'EXACT_FOUR_REMAINING_SSR_COMPLETED');report['status']='ENTRY_ACCEPTANCE_SSR_COMPLETION_PASS';report['combinedVerifiedChecks']={'detailPairs':67,'listPairs':67,'ssrPages':6,'freshSsrPages':4,'reusedMovieSsrPages':2}
 except Exception as error:report['status']='STOPPED';report['failure']=E.fixed(error)
 finally:
  if C is not None and before is not None:
   try:
    final=E.snapshot(M,C,R,ids);final_baidu=E.protected(M,C,R,B);final_runtime=E.runtime(M,C,R,dep,base);report['selectedFullRowsFinally']=E.safe_snapshot(final);report['baiduFinally']=final_baidu;report['runtimeFinally']=final_runtime;report['selectedCompleteRowsFinallyRetained']=final==before;report['oldBaiduStateFinallyRetained']=final_baidu==baidu_before;report['runtimeFinallyRetained']=final_runtime==runtime_before;observed_guard(prior,final,final_baidu,final_runtime);report['permanentBlockedCompleteRowsFinally']=blocked_guard(prior);report['permanentOriginalBlockedPairCompleteRowsRetained']=True
    if not all(report[k] for k in ['selectedCompleteRowsFinallyRetained','oldBaiduStateFinallyRetained','runtimeFinallyRetained']):report['status']='STOPPED';report['invariantFailure']='FOUR_SSR_WINDOW_FINAL_FULL_ROWS_BAIDU_OR_RUNTIME_CHANGED'
   except Exception as error:report['status']='STOPPED';report['finalFailure']=E.fixed(error)
  report['completedAt']=E.now();reference=save(report);print(json.dumps({'status':report['status'],'receipt':reference,'actualMergedPairs':report.get('actualMergedPairs'),'freshSsrChecks':len(report['ssrChecks']),'nginxHttpGetRequests':report['nginxHttpGetRequests'],'apiDetailGets':0,'apiListGets':0,'postRequests':0,'operatorDirectSqlWrites':0,'cloudActions':0,'failure':report.get('failure'),'finalFailure':report.get('finalFailure')},ensure_ascii=False),flush=True)
 return 0 if report['status']=='ENTRY_ACCEPTANCE_SSR_COMPLETION_PASS' else 1

def main():
 p=argparse.ArgumentParser(description=__doc__);p.add_argument('--execute',action='store_true');p.add_argument('--ssr-binding-sha');p.add_argument('--v7-binding-sha');p.add_argument('--split-plan',type=Path,default=V3.PLAN_FILE);p.add_argument('--split-plan-sha');p.add_argument('--mobland-binding-receipt',type=Path);p.add_argument('--mobland-binding-sha');args=p.parse_args()
 if not args.execute:print(json.dumps({'mode':'NO_RUNTIME_IO_PLAN','scope':SCOPE}));return 0
 return execute(args)

if __name__=='__main__':raise SystemExit(main())
