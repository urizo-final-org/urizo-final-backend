"""Deterministic importer contract tests. No live CMS/DB/network writes."""
import copy
import json
from pathlib import Path
import tempfile
import unittest
from unittest.mock import patch

import import_demo as demo


class FixtureClient:
    def __init__(self):
        self.data = {k:[] for k in demo.FIELDS}
        self.data.update({'_target':{'systemId':'fixture-other-machine','database':'fixture','databaseOid':42},
                          '_jobs':[], '_history':[{'id':'old-ai-independent-history','hash':'unchanged'}]})
        self.protected_data = {'admin_account':'recipient-account-uuid','ai_profile_version':'recipient-profiles',
                               'coding_job':'existing-jobs','document_chunk':'recipient-rag','natural_cms_job':'terminal-history'}
        self.counter = 1000
        self.calls = []
        self.fail_after_commit = False
        self.mutate_before_write = False

    def snapshot(self):
        return copy.deepcopy(self.data)

    def protected(self):
        return copy.deepcopy(self.protected_data)

    def request(self,method,path,payload):
        kind = ('code' if '/codes' in path else 'group' if '/code-groups' in path else
                'post' if '/posts' in path else next(k for k,p in {'content':'contents','board':'boards',
                 'menu':'menus','template':'templates','site':'sites'}.items() if '/'+p in path))
        ident = path.rsplit('/',1)[1] if method=='PUT' else None
        if ident is not None and kind not in demo.KEY_FIELD:
            ident = int(ident)
        rows = self.data[kind]
        if ident is not None:
            row = next(r for r in rows if demo.identity(kind,r)==ident)
        else:
            self.counter += 7
            ident = payload.get('key',self.counter)
            row = {demo.KEY_FIELD.get(kind,'id'):ident}
            if kind in ('content','post'):
                row.update({'_authorId':'recipient-new-author','_createdAt':'fixture-created'})
            if kind in ('content','board','post'):
                row.update({'_deleted':'N','_deletedAt':None})
            if kind=='post':
                row['boardId']=int(path.split('/')[4])
            if kind=='code':
                row['groupKey']=path.split('/')[4]
            rows.append(row)
        row.update(payload)
        if kind in ('content','board','post','template','site'):
            row['_updatedAt']='updated-'+str(len(self.calls))
        rows.sort(key=lambda r:demo.identity(kind,r))
        if kind in ('menu','content','board','post','template'):
            self.data['_history'].append({'id':'history-'+str(len(self.calls)),'hash':demo.digest(row)})
        self.calls.append((method,path,copy.deepcopy(payload)))
        if self.fail_after_commit:
            self.fail_after_commit=False
            raise demo.Blocked('simulated lost response after commit')
        return copy.deepcopy(row)

    def upload(self,path,file,content_type):
        import hashlib
        self.counter+=7
        row={'id':self.counter,'sha256':hashlib.sha256(file.read_bytes()).hexdigest(),'contentType':content_type,
             '_authorId':'recipient-new-author','_createdAt':'fixture-created','_byteSize':file.stat().st_size}
        self.data['image'].append(row)
        self.calls.append(('POST',path,{'fixtureImage':file.name}))
        if self.fail_after_commit:
            self.fail_after_commit=False
            raise demo.Blocked('simulated image response lost')
        return {'id':row['id'],'contentType':content_type,'byteSize':row['_byteSize']}


class ImportTests(unittest.TestCase):
    def setUp(self):
        self.package_dir=demo.ROOT/'demo/cms-tour-v1'
        self.package,self.package_hash=demo.load_package(self.package_dir)
        self.client=FixtureClient()
        self.directory=tempfile.TemporaryDirectory()
        self.addCleanup(self.directory.cleanup)
        self.journal_path=Path(self.directory.name)/'journal.json'
        self.journal={'mapping':{},'inflight':None}
        template=next(i for i in self.package['items'] if i['kind']=='template')
        self.client.data['template']=[dict(template['fields'],siteName='recipient title',_active=True,_updatedAt='old')]
        site=next(i for i in self.package['items'] if i['kind']=='site')
        self.client.data['site']=[dict(site['fields'],siteName='recipient site',_default=True,_updatedAt='old')]

    def plan(self,bindings=None):
        return demo.build_plan(self.package,self.package_hash,self.client.snapshot(),self.client.protected(),bindings or {},self.journal)

    def apply(self,plan=None):
        with patch('builtins.print'):
            return demo.apply_plan(self.client,self.package,self.package_dir,plan or self.plan(),self.journal,self.journal_path)

    def test_package_is_curated_and_has_no_source_ids_or_account_metadata(self):
        self.assertEqual({'image':3,'group':4,'code':17,'content':8,'board':3,'post':90,'menu':15,'template':1,'site':1},
                         dict(demo.Counter(i['kind'] for i in self.package['items'])))
        text=json.dumps(self.package)
        for forbidden in ('authorId','account_id','createdAt','updatedAt','sessionToken','/api/site/images/', '/e2e/'):
            self.assertNotIn(forbidden,text)

    def test_import_remaps_all_ids_authors_and_second_apply_is_noop(self):
        self.client.data['content']=[{'id':8,'title':'personal content','body':'personal','_authorId':'old-recipient-author','_createdAt':'old','_updatedAt':'old','_deleted':'N','_deletedAt':None}]
        self.client.data['board']=[{'id':1,'name':'personal board','description':'keep','displayType':'LIST','regionGroupKey':None,'categoryGroupKey':None,'_createdAt':'old','_updatedAt':'old','_deleted':'N','_deletedAt':None}]
        self.client.data['post']=[{'id':8,'boardId':1,'title':'personal post','body':'personal','thumbnailImageId':None,'thumbnailAlt':'','regionCodeId':None,'categoryCodeId':None,'_authorId':'old-recipient-author','_createdAt':'old','_updatedAt':'old','_deleted':'N','_deletedAt':None}]
        old=self.client.snapshot()
        protected=self.client.protected()
        result=self.apply()
        self.assertEqual('CMS_IMPORT_PASS',result['status'])
        self.assertEqual(142,result['cmsWrites'])
        for kind in ('content','board','post'):
            self.assertEqual(old[kind][0],self.client.data[kind][0])
        self.assertEqual(protected,self.client.protected())
        posts=[r for r in self.client.data['post'] if r['id']!=8]
        self.assertEqual(90,len(posts))
        self.assertTrue(all(r['_authorId']=='recipient-new-author' and r['id']>1000 for r in posts))
        self.assertTrue(all('{{' not in r['body'] and '/api/site/images/' in r['body'] for r in posts))
        calls=len(self.client.calls)
        self.assertEqual({'SKIP':142},dict(demo.Counter(a['action'] for a in self.plan()['actions'])))
        self.assertEqual(0,self.apply()['cmsWrites'])
        self.assertEqual(calls,len(self.client.calls))

    def test_nonterminal_job_blocks_before_first_write(self):
        for status in ('ACTIVE','WAITING_APPROVAL','UNKNOWN_FUTURE_STATUS'):
            with self.subTest(status=status):
                self.client.data['_jobs']=[{'job_id':'local-job','status':status}]
                with self.assertRaisesRegex(demo.Blocked,'PLAN_BLOCKED'):
                    self.apply()
                self.assertEqual([],self.client.calls)

    def test_state_change_between_dry_run_and_apply_blocks(self):
        plan=self.plan()
        self.client.data['site'][0]['siteName']='concurrent edit'
        with self.assertRaisesRegex(demo.Blocked,'STALE_PLAN'):
            self.apply(plan)
        self.assertEqual([],self.client.calls)

    def test_protected_data_change_between_plan_and_apply_blocks(self):
        plan=self.plan()
        self.client.protected_data['coding_job']='concurrent AI change'
        with self.assertRaisesRegex(demo.Blocked,'protected data changed'):
            self.apply(plan)
        self.assertEqual([],self.client.calls)

    def test_concurrent_change_at_per_request_guard_stops_before_write(self):
        plan=self.plan()
        original=self.client.snapshot
        reads=0
        def raced_snapshot():
            nonlocal reads
            reads+=1
            if reads==2:
                self.client.data['site'][0]['siteName']='concurrent editor'
            return original()
        with patch.object(self.client,'snapshot',side_effect=raced_snapshot):
            with self.assertRaisesRegex(demo.Blocked,'CONCURRENT_CHANGE_BEFORE_WRITE'):
                self.apply(plan)
        self.assertEqual([],self.client.calls)

    def test_other_writer_after_http_commit_is_detected_and_no_next_write_sent(self):
        plan=self.plan()
        original=self.client.snapshot
        reads=0
        def raced_snapshot():
            nonlocal reads
            reads+=1
            if reads==3:
                self.client.data['site'][0]['siteName']='concurrent editor'
            return original()
        with patch.object(self.client,'snapshot',side_effect=raced_snapshot):
            with self.assertRaisesRegex(demo.Blocked,'CONCURRENT_OR_UNEXPECTED_CMS_CHANGE'):
                self.apply(plan)
        self.assertEqual(1,len(self.client.calls))
        self.assertIsNotNone(self.journal['inflight'])

    def test_duplicate_menu_path_blocks(self):
        row={'id':5,'name':'old','path':'/about','displayOrder':0,'targetType':'NONE','targetId':None,'parentId':None}
        self.client.data['menu']=[row,dict(row,id=88)]
        self.assertTrue(any('AMBIGUOUS_TARGET' in b for b in self.plan()['blockers']))

    def test_same_title_is_not_automatic_overwrite(self):
        content=next(i for i in self.package['items'] if i['kind']=='content')
        self.client.data['content']=[{'id':404,**content['fields'],'body':'local private content','_authorId':'local-owner','_createdAt':'old','_updatedAt':'old','_deleted':'N','_deletedAt':None}]
        self.assertTrue(any('EXPLICIT_BINDING_REQUIRED' in b for b in self.plan()['blockers']))
        self.assertFalse(self.plan({content['key']:'new'})['blockers'])
        plan=self.plan({content['key']:404})
        self.assertFalse(plan['blockers'])
        self.apply(plan)
        preserved=next(c for c in self.client.data['content'] if c['id']==404)
        self.assertEqual('local-owner',preserved['_authorId'])
        self.assertEqual('old',preserved['_createdAt'])

    def test_two_bindings_to_same_target_are_blocked(self):
        contents=[i for i in self.package['items'] if i['kind']=='content']
        self.client.data['content']=[{'id':3,'title':'other','body':'other','_deleted':'N'}]
        plan=self.plan({contents[0]['key']:3,contents[1]['key']:3})
        self.assertTrue(any('MULTIPLE_ITEMS_ONE_TARGET' in b for b in plan['blockers']))

    def test_existing_code_meaning_is_preserved(self):
        group=next(i for i in self.package['items'] if i['kind']=='group')
        self.client.data['group']=[dict(group['fields'],label='local meaning')]
        self.assertTrue(any('EXISTING_CODE_DIFFERS' in b for b in self.plan()['blockers']))

    def test_deleted_binding_is_not_reused(self):
        content=next(i for i in self.package['items'] if i['kind']=='content')
        self.client.data['content']=[{'id':1,**content['fields'],'_deleted':'Y'}]
        self.assertTrue(any('BOUND_RESOURCE_MISSING_OR_DELETED' in b for b in self.plan({content['key']:1})['blockers']))

    def test_unselected_menu_blocks_shared_target_update(self):
        content=next(i for i in self.package['items'] if i['kind']=='content')
        self.client.data['content']=[{'id':6,**content['fields'],'body':'old','_deleted':'N'}]
        self.client.data['menu']=[{'id':777,'path':'/personal','name':'keep','displayOrder':0,'targetType':'CONTENT','targetId':6,'parentId':None}]
        self.assertTrue(any('UNSELECTED_MENU_REFERENCES_TARGET' in b for b in self.plan({content['key']:6})['blockers']))

    def test_lost_image_response_recovers_without_duplicate_upload(self):
        self.client.fail_after_commit=True
        with self.assertRaisesRegex(demo.Blocked,'response lost'):
            self.apply()
        self.assertIsNotNone(self.journal['inflight'])
        calls=len(self.client.calls)
        recovered=demo.recover(self.client,self.journal,self.journal_path)
        self.assertEqual(0,recovered['cmsWrites'])
        self.assertEqual(calls,len(self.client.calls))
        self.apply()
        self.assertEqual(3,len(self.client.data['image']))
        self.assertEqual(90,len(self.client.data['post']))

    def test_uncommitted_or_ambiguous_inflight_never_retries_create(self):
        before=self.client.snapshot()
        self.client.fail_after_commit=True
        with self.assertRaises(demo.Blocked):
            self.apply()
        self.client.data=before
        with self.assertRaisesRegex(demo.Blocked,'RECOVERY_AMBIGUOUS_OR_NOT_COMMITTED'):
            demo.recover(self.client,self.journal,self.journal_path)
        self.assertIsNotNone(self.journal['inflight'])

    def test_history_mutation_is_detected(self):
        before=self.client.snapshot()
        after=copy.deepcopy(before)
        after['_history'][0]['hash']='changed'
        row=after['site'][0]
        with self.assertRaisesRegex(demo.Blocked,'EXISTING_CMS_HISTORY_CHANGED'):
            demo.assert_only_change(before,after,'site',row['key'],demo.project('site',row),False)

    def test_create_cannot_reuse_preexisting_id(self):
        before=self.client.snapshot()
        row=before['site'][0]
        with self.assertRaisesRegex(demo.Blocked,'API_REUSED_EXISTING_ID'):
            demo.assert_only_change(before,before,'site',row['key'],demo.project('site',row),True)

    def test_bad_reference_kind_is_rejected(self):
        package=copy.deepcopy(self.package)
        post=next(i for i in package['items'] if i['kind']=='post')
        post['fields']['thumbnailImageId']={'ref':'board:travel'}
        with patch.object(demo,'read_json',return_value=package):
            with self.assertRaisesRegex(demo.Blocked,'Wrong reference kind'):
                demo.load_package(self.package_dir)

    def test_duplicate_manifest_key_is_rejected(self):
        package=copy.deepcopy(self.package)
        package['items'].append(package['items'][0])
        with patch.object(demo,'read_json',return_value=package):
            with self.assertRaisesRegex(demo.Blocked,'Duplicate key'):
                demo.load_package(self.package_dir)

    def test_cyclic_menu_dependency_is_rejected(self):
        package=copy.deepcopy(self.package)
        menu=next(i for i in package['items'] if i['kind']=='menu')
        menu['fields']['parentId']={'ref':menu['key']}
        with self.assertRaisesRegex(demo.Blocked,'Cyclic'):
            demo.ordered_items(package)

    def test_image_tampering_is_rejected(self):
        package=copy.deepcopy(self.package)
        next(i for i in package['items'] if i['kind']=='image')['sha256']='0'*64
        with patch.object(demo,'read_json',return_value=package):
            with self.assertRaisesRegex(demo.Blocked,'Image hash'):
                demo.load_package(self.package_dir)

    def test_remote_url_rejected_before_docker(self):
        with self.assertRaisesRegex(demo.Blocked,'loopback'):
            demo.LocalClient('https://example.com','axms-spring-dev','ax_module_studio',demo.ROOT)

    def test_local_state_lock_prevents_second_importer(self):
        directory=Path(self.directory.name)
        with demo.exclusive(directory):
            with self.assertRaisesRegex(demo.Blocked,'IMPORT_LOCK_EXISTS'):
                with demo.exclusive(directory):
                    pass
        self.assertFalse((directory/'import.lock').exists())


if __name__=='__main__':
    unittest.main()
