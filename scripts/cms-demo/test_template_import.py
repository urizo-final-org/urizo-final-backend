"""Template package isolation, target-ID mapping and approval recovery contracts."""
import copy
import json
from pathlib import Path
import tempfile
import unittest
from unittest.mock import patch

import import_demo as demo
from test_import_demo import FixtureClient


class TemplateImportTests(unittest.TestCase):
    def setUp(self):
        self.directory = demo.ROOT/'demo/cms-template-v1'
        self.package,self.hash = demo.load_package(self.directory)
        self.client = FixtureClient()
        for key in ('BOLD','CLASSIC','MINIMAL'):
            row = dict.fromkeys(demo.FIELDS['template'],'')
            row.update(key=key,layout=key,primaryColor='#FFFFFF',siteName='Recipient '+key,
                       heroTitle='Recipient title',heroImages=[],heroImageUrls=[],_active=True,_updatedAt='before')
            self.client.data['template'].append(row)
        self.client.data['site'] = [dict(key='main',siteName='Local site',publicPath='/',templateKey='MINIMAL',
                                         enabled=True,_default=True,_updatedAt='before')]
        self.folder = tempfile.TemporaryDirectory()
        self.addCleanup(self.folder.cleanup)
        self.path = Path(self.folder.name)/'journal.json'
        self.journal = {'mapping':{},'inflight':None}

    def plan(self,sites=()):
        return demo.build_plan(self.package,self.hash,self.client.snapshot(),self.client.protected(),{},self.journal,sites)

    def apply(self,plan=None):
        with patch('builtins.print'):
            return demo.apply_plan(self.client,self.package,self.directory,plan or self.plan(),self.journal,self.path)

    def test_curated_package_only_has_two_templates_and_five_portable_images(self):
        self.assertEqual({'image':5,'template':2},dict(demo.Counter(i['kind'] for i in self.package['items'])))
        text = json.dumps(self.package)
        for forbidden in ('/api/site/images/','siteName','updatedAt','authorId','templateKey'):
            self.assertNotIn(forbidden,text)

    def test_other_machine_ids_captions_order_and_all_unselected_data_preserved(self):
        before = self.client.snapshot()
        protected = self.client.protected()
        self.assertEqual(7,self.apply()['cmsWrites'])
        after = self.client.snapshot()
        for kind in demo.FIELDS:
            if kind not in ('image','template'):
                self.assertEqual(before[kind],after[kind])
        self.assertEqual(before['template'][2],after['template'][2])
        self.assertEqual(protected,self.client.protected())
        for item in self.package['items']:
            if item['kind']!='template':
                continue
            row = next(r for r in after['template'] if r['key']==item['fields']['key'])
            self.assertEqual('Recipient '+row['key'],row['siteName'])
            self.assertEqual(5,len(row['heroImages']))
            for source,saved in zip(item['fields']['heroImages'],row['heroImages']):
                self.assertEqual('/api/site/images/'+str(self.journal['mapping'][source['imageRef']]),saved['url'])
                self.assertEqual(source['title'],saved['title'])
                self.assertEqual(source['description'],saved['description'])
        calls = len(self.client.calls)
        self.assertEqual({'SKIP':7},dict(demo.Counter(a['action'] for a in self.plan()['actions'])))
        self.assertEqual(0,self.apply()['cmsWrites'])
        self.assertEqual(calls,len(self.client.calls))

    def test_existing_image_hashes_are_reused_without_upload(self):
        for number,item in enumerate(i for i in self.package['items'] if i['kind']=='image'):
            self.client.data['image'].append(dict(id=300+number,sha256=item['sha256'],contentType=item['contentType']))
        self.assertEqual(2,self.apply()['cmsWrites'])
        self.assertTrue(all(call[0]=='PUT' for call in self.client.calls))

    def test_site_impact_is_explicit_and_never_changes_site_settings(self):
        self.client.data['site'][0]['templateKey']='BOLD'
        self.assertIn('UNSELECTED_SITE_REFERENCES_TEMPLATE: template:BOLD',self.plan()['blockers'])
        plan = self.plan(('main',))
        self.assertFalse(plan['blockers'])
        self.assertEqual('PRESERVE',plan['templateSiteImpacts'][0]['settingsAction'])
        before = self.client.snapshot()['site']
        self.apply(plan)
        self.assertEqual(before,self.client.snapshot()['site'])

    def test_unknown_site_acknowledgment_and_v1_bypass_rejected(self):
        with self.assertRaisesRegex(demo.Blocked,'Unknown site'):
            self.plan(('wrong',))
        old,sha = demo.load_package(demo.ROOT/'demo/cms-tour-v1')
        with self.assertRaisesRegex(demo.Blocked,'template image packages only'):
            demo.build_plan(old,sha,self.client.snapshot(),{}, {},self.journal,('main',))

    def test_nonterminal_job_blocks_before_first_write(self):
        self.client.data['_jobs']=[{'status':'WAITING_APPROVAL'}]
        with self.assertRaisesRegex(demo.Blocked,'NONTERMINAL'):
            self.apply()
        self.assertEqual([],self.client.calls)

    def test_caption_and_site_change_after_plan_are_stale(self):
        for area in ('template','site'):
            plan = self.plan()
            row = self.client.data[area][0]
            row['heroImages' if area=='template' else 'siteName'] = [{'url':'/api/site/images/999','title':'changed','description':''}] if area=='template' else 'changed'
            with self.assertRaisesRegex(demo.Blocked,'STALE_PLAN'):
                self.apply(plan)
        self.assertEqual([],self.client.calls)

    def test_lost_template_response_recovers_read_only_then_finishes(self):
        request = self.client.request
        def lost(*args):
            self.client.fail_after_commit=True
            return request(*args)
        with patch.object(self.client,'request',side_effect=lost):
            with self.assertRaisesRegex(demo.Blocked,'lost response'):
                self.apply()
        self.assertEqual('template',self.journal['inflight']['kind'])
        count = len(self.client.calls)
        self.assertEqual(0,demo.recover(self.client,self.journal,self.path)['cmsWrites'])
        self.assertEqual(count,len(self.client.calls))
        self.assertEqual(1,self.apply()['cmsWrites'])

    def test_unknown_or_source_url_image_reference_and_oversized_captions_rejected(self):
        mutations = [lambda e:e.update(imageRef='image:missing'),
                     lambda e:e.update(url='/api/site/images/5'),
                     lambda e:e.update(title='x'*121),lambda e:e.update(description='x'*241),
                     lambda e:e.update(title='😀'*61)]
        for mutate in mutations:
            package = copy.deepcopy(self.package)
            item = next(i for i in package['items'] if i['kind']=='template')
            mutate(item['fields']['heroImages'][0])
            with patch.object(demo,'read_json',return_value=package):
                with self.assertRaises(demo.Blocked):
                    demo.load_package(self.directory)

    def test_more_than_five_images_and_foreign_resource_rejected(self):
        package = copy.deepcopy(self.package)
        item = next(i for i in package['items'] if i['kind']=='template')
        item['fields']['heroImages'].append(item['fields']['heroImages'][0])
        with patch.object(demo,'read_json',return_value=package):
            with self.assertRaisesRegex(demo.Blocked,'image limit'):
                demo.load_package(self.directory)
        item['kind']='site'
        with patch.object(demo,'read_json',return_value=package):
            with self.assertRaisesRegex(demo.Blocked,'other resources'):
                demo.load_package(self.directory)

    def test_missing_template_blocks_instead_of_creating(self):
        self.client.data['template']=[]
        with self.assertRaisesRegex(demo.Blocked,'TEMPLATE_MUST_ALREADY_EXIST'):
            self.apply()
        self.assertFalse(self.client.calls)

    def test_wrong_image_caption_readback_leaves_journal_pending(self):
        original = self.client.request
        def corrupt(*args):
            result = original(*args)
            self.client.data['template'][0]['heroImages'][0]['title']='unexpected'
            return result
        with patch.object(self.client,'request',side_effect=corrupt):
            with self.assertRaisesRegex(demo.Blocked,'CMS_READBACK_MISMATCH'):
                self.apply()
        self.assertIsNotNone(self.journal['inflight'])


if __name__=='__main__':
    unittest.main()
