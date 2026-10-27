import contextlib
import importlib.util
import io
import json
import os
from pathlib import Path
import sys
import tempfile
import unittest
from unittest.mock import patch
from urllib.error import HTTPError
from urllib.parse import urlparse,parse_qs

spec=importlib.util.spec_from_file_location('backfill',Path(__file__).with_name('backfill.py'))
module=importlib.util.module_from_spec(spec);spec.loader.exec_module(module)
class Response:
 def __init__(self,data):self.data=data
 def __enter__(self):return self
 def __exit__(self,*args):pass
 def read(self):return json.dumps(self.data).encode() if self.data is not None else b''
class BackfillTest(unittest.TestCase):
 def run_script(self,args,failure=None):
  requests=[]
  def urlopen(req,timeout):
   path=urlparse(req.full_url);query=parse_qs(path.query);requests.append(req)
   if failure and path.path==failure:raise HTTPError(req.full_url,503,'unavailable',{},None)
   if req.method=='PUT':return Response(None)
   if path.path=='/api/users':
    if query['assigned']==['false']:return Response({'users':[{'userId':503,'isBanned':False}],'hasNext':False})
    page=int(query['page'][0]);return Response({'users':[{'userId':501+page,'isBanned':False}],'hasNext':page==0})
   if path.path=='/api/employees/503':raise HTTPError(req.full_url,404,'no shell',{},None)
   if path.path.startswith('/api/employees/'):return Response({'status':'ACTIVE' if path.path.endswith('501') else 'INACTIVE'})
   if path.path=='/api/projects':return Response({'projects':[{'id':701,'teamId':601,'name':'Existing','startDate':None,'endDate':None}],'hasNext':False})
   if path.path=='/api/teams':return Response([{'id':601,'members':[{'userId':501}]}])
   raise AssertionError(req.full_url)
  out=io.StringIO()
  with patch.dict(os.environ,{'TIMESHEET_BACKFILL_TOKEN':'never-print-this-test-token'}),patch.object(sys,'argv',['backfill.py',*args]),patch.object(module,'urlopen',urlopen),contextlib.redirect_stdout(out):
   module.main()
  self.assertNotIn('never-print-this-test-token',out.getvalue())
  return out.getvalue(),requests
 def test_export_paginates_and_marks_missing_employee_inactive(self):
  out,requests=self.run_script([]);snapshot=json.loads(out)
  self.assertEqual(snapshot['employees'],[{'userId':501,'active':True},{'userId':502,'active':False},{'userId':503,'active':False}])
  self.assertEqual(snapshot['projects'][0]['id'],701);self.assertEqual(len(snapshot['memberships']),1)
  self.assertFalse(any(r.method=='PUT' for r in requests))
 def test_apply_existing_snapshot_preserves_watermark(self):
  out,_=self.run_script([])
  with tempfile.TemporaryDirectory() as directory:
   file=Path(directory)/'snapshot.json';file.write_text(out)
   for _ in range(2):
    result,requests=self.run_script(['--input',str(file),'--apply'])
    self.assertEqual(len(requests),3)
    self.assertTrue(all(r.method=='PUT' for r in requests))
    self.assertEqual({json.loads(r.data)['asOf'] for r in requests},{json.loads(out)['asOf']})
 def test_upstream_failure_aborts_export_before_import(self):
  with self.assertRaisesRegex(SystemExit,'HTTP 503'):
   self.run_script(['--apply'],failure='/api/projects')
if __name__=='__main__':unittest.main()
