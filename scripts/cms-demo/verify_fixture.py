"""Opt-in test driver, used only by CmsDemoImportFixtureTest with a disposable DB.

The production importer never calls fixture_sql. This file seeds synthetic
recipient records in the named, labelled, tmpfs-only test container.
"""
import argparse
import copy
import hashlib
import json
from pathlib import Path
import re
import shutil
import tempfile
import urllib.request

import import_demo as demo


ACTOR='8b9a0071-6f0d-4f63-bca9-422e074ed148'
PROFILE='ab79bf99-f08b-476d-8d78-155438838981'
JOB='ab79bf99-f08b-476d-8d78-155438838982'


class FixtureClient(demo.LocalClient):
    def __init__(self,url,container):
        demo.require(re.fullmatch(r'http://127\.0\.0\.1:[0-9]+',url), 'Fixture must be loopback.')
        demo.require(re.fullmatch(r'axms-cms-demo-fixture-[0-9a-f]{12}',container), 'Invalid fixture container name.')
        self.base=url
        self.db_container=container
        self.database='axms_cms_import_fixture'
        self.backend_root=demo.ROOT
        self.docker=shutil.which('docker')
        demo.require(self.docker,'Docker CLI missing.')
        inspected=json.loads(self.command('inspect',container))[0]
        demo.require(inspected['Config']['Labels'].get('axms.work-slug')=='axms-cms-demo-share-fixture', 'Wrong fixture label.')
        demo.require('/var/lib/postgresql/data' in inspected['HostConfig'].get('Tmpfs',{}), 'Fixture is not tmpfs backed.')
        demo.require(not any(m['Type']=='volume' for m in inspected['Mounts']), 'Fixture must not have persistent volumes.')
        self.opener=urllib.request.build_opener(urllib.request.ProxyHandler({}),demo.NoRedirect())
        self.token=None
        self.calls=0
        self.drop_image_response=True

    def fixture_sql(self,sql):
        return self.command('exec','-i',self.db_container,'psql','-X','-qAt','-U','bootstrap_admin','-d',self.database,
                            '-v','ON_ERROR_STOP=1',input_text=sql)

    def request(self,*args,**kwargs):
        self.calls+=1
        return super().request(*args,**kwargs)

    def upload(self,*args,**kwargs):
        self.calls+=1
        result=super().upload(*args,**kwargs)
        if self.drop_image_response:
            self.drop_image_response=False
            raise demo.Blocked('FIXTURE_LOST_IMAGE_RESPONSE_AFTER_REAL_COMMIT')
        return result


def seed(client,package_dir):
    body=demo.canonical({'type':'doc','content':[{'type':'paragraph','content':[{'type':'text','text':'Personal fixture content; preserve.'}]}]})
    image=(package_dir/'forest.png').read_bytes()+b'personal-fixture-trailer'
    # Fixed synthetic fixture IDs/values only; never source PC records or credentials.
    client.fixture_sql(f"""INSERT INTO app.admin_account(account_id,login_id,password_hash,role,display_name)
VALUES ('{ACTOR}','fixture-recipient','pbkdf2-sha256$1$fixture$not_a_login_hash','SUPER_ADMIN','Fixture recipient');
INSERT INTO app.cms_content(content_id,author_id,title,body) VALUES (8,'{ACTOR}','Personal fixture content',$body${body}$body$);
INSERT INTO app.cms_board(board_id,board_name,description) VALUES (1,'Personal fixture board','Do not replace');
INSERT INTO app.cms_content_image(image_id,author_id,content_type,byte_size,bytes)
VALUES (1,'{ACTOR}','image/png',{len(image)},decode('{image.hex()}','hex'));
INSERT INTO app.cms_post(post_id,board_id,author_id,title,body,thumbnail_image_id)
VALUES (8,1,'{ACTOR}','Personal fixture post',$body${body}$body$,1);
INSERT INTO app.cms_code_group(group_key,label) VALUES ('LOCAL_ONLY','Recipient code');
INSERT INTO app.cms_code(code_id,group_key,code_value,label) VALUES (1,'LOCAL_ONLY','KEEP','Keep this meaning');
INSERT INTO app.ai_profile_version(profile_version_id,profile_key,profile_version,status,snapshot_json)
VALUES ('{PROFILE}','NATURAL_CMS',999999,'DRAFT',
 '{{"contractVersion":"1.0","profileVersionId":"{PROFILE}","profileKey":"NATURAL_CMS","profileVersion":999999}}');
INSERT INTO app.natural_cms_job(job_id,trace_id,profile_version_id,actor_id,status,request_text,resource_type,resource_id)
VALUES ('{JOB}','{JOB}','{PROFILE}','{ACTOR}','COMPLETED','Recipient completed fixture job','CONTENT','8');
INSERT INTO app.cms_change_history(change_id,resource_type,resource_id,operation,title,actor_id,actor_name,actor_role)
VALUES ('ab79bf99-f08b-476d-8d78-155438838983','CONTENT','8','CREATE','Previous recipient history','{ACTOR}','Fixture recipient','SUPER_ADMIN');
INSERT INTO app.coding_job(job_id,trace_id,status,state_version,context_digest,prompt_version,allowed_capabilities,allowed_nodes,expires_at,authority_source)
VALUES ('ab79bf99-f08b-476d-8d78-155438838990','ab79bf99-f08b-476d-8d78-155438838990','COMPLETED',1,'sha256:'||repeat('a',64),'fixture-v1',ARRAY['CHAT'],ARRAY['fixture'],now()+interval '1 day','LEGACY_FIXTURE');
INSERT INTO app.project(project_id,name) VALUES ('ab79bf99-f08b-476d-8d78-155438838991','Recipient RAG fixture');
INSERT INTO app.connector(connector_id,project_id,name) VALUES ('ab79bf99-f08b-476d-8d78-155438838992','ab79bf99-f08b-476d-8d78-155438838991','FIXTURE');
INSERT INTO app.connector_version(connector_version_id,connector_id,version_number,config_json,config_digest)
VALUES ('ab79bf99-f08b-476d-8d78-155438838993','ab79bf99-f08b-476d-8d78-155438838992',1,'{{}}','sha256:'||repeat('a',64));
INSERT INTO app.knowledge_base(knowledge_base_id,project_id,name) VALUES ('ab79bf99-f08b-476d-8d78-155438838994','ab79bf99-f08b-476d-8d78-155438838991','Recipient knowledge');
INSERT INTO app.knowledge_version(knowledge_version_id,knowledge_base_id,connector_version_id,version_number,config_digest)
VALUES ('ab79bf99-f08b-476d-8d78-155438838995','ab79bf99-f08b-476d-8d78-155438838994','ab79bf99-f08b-476d-8d78-155438838993',1,'sha256:'||repeat('a',64));
INSERT INTO app.source_document(source_document_id,knowledge_version_id,external_document_id,title,content,source_url,content_digest)
VALUES ('ab79bf99-f08b-476d-8d78-155438838996','ab79bf99-f08b-476d-8d78-155438838995','fixture-1','Recipient document','Preserve RAG content','https://example.invalid/fixture','sha256:'||repeat('a',64));
INSERT INTO app.document_chunk(document_chunk_id,source_document_id,knowledge_version_id,chunk_index,content,content_digest,embedding)
VALUES ('ab79bf99-f08b-476d-8d78-155438838997','ab79bf99-f08b-476d-8d78-155438838996','ab79bf99-f08b-476d-8d78-155438838995',0,'Preserve recipient embedding','sha256:'||repeat('a',64),('['||'1,'||repeat('0,',1022)||'0]')::vector);
""")
    for table,column in (('cms_content','content_id'),('cms_board','board_id'),('cms_post','post_id'),
                         ('cms_content_image','image_id'),('cms_code','code_id'),('cms_menu','menu_id')):
        client.fixture_sql("SELECT setval(pg_get_serial_sequence('app."+table+"','"+column+"'),1000,true);")


def main():
    parser=argparse.ArgumentParser()
    parser.add_argument('--base-url',required=True)
    parser.add_argument('--container',required=True)
    args=parser.parse_args()
    client=FixtureClient(args.base_url,args.container)
    package_dir=demo.ROOT/'demo/cms-tour-v1'
    package,package_hash=demo.load_package(package_dir)
    seed(client,package_dir)
    with tempfile.TemporaryDirectory(prefix='cms-import-fixture-') as folder:
        journal={'mapping':{},'inflight':None}
        path=Path(folder)/'journal.json'
        def plan():
            return demo.build_plan(package,package_hash,client.snapshot(),client.protected(),{},journal)
        def apply(candidate):
            return demo.apply_plan(client,package,package_dir,candidate,journal,path)
        for status in ('ACTIVE','WAITING_APPROVAL'):
            client.fixture_sql("UPDATE app.natural_cms_job SET status='"+status+"' WHERE job_id='"+JOB+"';")
            before=client.snapshot()
            try:
                apply(plan())
                raise AssertionError('A nonterminal job was not blocked.')
            except demo.Blocked as error:
                assert 'PLAN_BLOCKED' in str(error)
            assert client.calls==0 and client.snapshot()==before
        client.fixture_sql("UPDATE app.natural_cms_job SET status='COMPLETED' WHERE job_id='"+JOB+"';")
        original=client.snapshot()
        protected=client.protected()
        assert not plan()['blockers']
        try:
            apply(plan())
            raise AssertionError('Lost response injection did not execute.')
        except demo.Blocked as error:
            assert 'FIXTURE_LOST_IMAGE_RESPONSE' in str(error)
        assert journal['inflight'] and client.calls==1
        assert demo.recover(client,journal,path)['cmsWrites']==0
        assert client.calls==1
        result=apply(plan())
        assert result['cmsWrites']==141
        after=client.snapshot()
        assert client.protected()==protected
        for kind in ('content','post','image'):
            ident=8 if kind!='image' else 1
            assert next(r for r in after[kind] if r['id']==ident)==next(r for r in original[kind] if r['id']==ident)
        assert len(after['post'])==91 and len(after['image'])==4 and len(after['content'])==9
        assert all(p['_authorId']==ACTOR for p in after['post'])
        assert all(p['id']>1000 for p in after['post'] if p['id']!=8)
        second=plan()
        assert all(a['action']=='SKIP' for a in second['actions'])
        count=client.calls
        assert apply(second)['cmsWrites']==0 and client.calls==count
        assert client.snapshot()==after
        result={'status':'POSTGRES_REAL_CMS_IMPORT_PASS','packageItems':142,'samplePosts':90,
                'allSourceNumericIdsRemapped':True,'secondRunCmsWrites':0,'lostResponseRecovery':'PASS',
                'activeAndWaitingApprovalWriteBlock':'PASS','accountsAiProfilesCodingJobsRagDocumentsEmbeddings':'PRESERVED',
                'existingCmsRowsAndHistory':'PRESERVED','auth':'TEST_PRINCIPAL_NOT_LOGIN_FILTER_E2E'}
        demo.save_json(demo.ROOT/'.local/cms-demo-share/fixture-verification.json',result)
        print(demo.canonical(result))
    verify_templates(client)


def verify_templates(client):
    directory = demo.ROOT/'demo/cms-template-v1'
    package,package_hash = demo.load_package(directory)
    client.template_images = True
    client.prerequisites(package)
    # Synthetic recipient settings: require explicit impact acknowledgment without changing them.
    client.fixture_sql("UPDATE app.cms_site SET template_key='BOLD',site_name='Recipient site keep' WHERE site_key='main';")
    original = client.snapshot()
    protected = client.protected()
    with tempfile.TemporaryDirectory(prefix='cms-template-fixture-') as folder:
        journal = {'mapping':{},'inflight':None}
        path = Path(folder)/'journal.json'
        def plan(sites=('main',)):
            return demo.build_plan(package,package_hash,client.snapshot(),client.protected(),{},journal,sites)
        def apply(candidate):
            return demo.apply_plan(client,package,directory,candidate,journal,path)
        assert 'UNSELECTED_SITE_REFERENCES_TEMPLATE: template:BOLD' in plan(())['blockers']
        assert not plan()['blockers']
        # Real HTTP success followed by client-side response loss at the first template update.
        request = client.request
        dropped = False
        def lost_template_response(method,route,payload=None):
            nonlocal dropped
            result = request(method,route,payload)
            if route.startswith('/api/cms/templates/') and not dropped:
                dropped = True
                raise demo.Blocked('FIXTURE_LOST_TEMPLATE_RESPONSE_AFTER_REAL_COMMIT')
            return result
        client.request = lost_template_response
        try:
            apply(plan())
            raise AssertionError('Template response-loss injection did not execute.')
        except demo.Blocked as error:
            assert 'FIXTURE_LOST_TEMPLATE_RESPONSE' in str(error)
        finally:
            client.request = request
        assert journal['inflight']['kind']=='template'
        calls = client.calls
        assert demo.recover(client,journal,path)['cmsWrites']==0 and client.calls==calls
        assert apply(plan())['cmsWrites']==1
        after = client.snapshot()
        for kind in demo.FIELDS:
            if kind not in ('image','template'):
                assert after[kind]==original[kind],kind
        assert next(r for r in after['template'] if r['key']=='MINIMAL')==next(r for r in original['template'] if r['key']=='MINIMAL')
        assert all(r in after['image'] for r in original['image'])
        assert client.protected()==protected
        for item in package['items']:
            if item['kind']=='template':
                row = next(r for r in after['template'] if r['key']==item['fields']['key'])
                before = next(r for r in original['template'] if r['key']==row['key'])
                assert row['siteName']==before['siteName'] and len(row['heroImages'])==5
                assert demo.project('template',row,demo.resolved_fields(item,journal['mapping'],False,before))==demo.resolved_fields(item,journal['mapping'],False,before)
            else:
                ident = journal['mapping'][item['key']]
                row = next(r for r in after['image'] if r['id']==ident)
                assert ident>1000 and row['sha256']==item['sha256']
                data,content_type = client.http('GET','/api/site/images/'+str(ident),raw=True)
                assert hashlib.sha256(data).hexdigest()==item['sha256'] and content_type.startswith(item['contentType'])
        assert dict(demo.Counter(a['action'] for a in plan()['actions']))=={'SKIP':7}
        calls = client.calls
        assert apply(plan())['cmsWrites']==0 and client.calls==calls and client.snapshot()==after
        result = {'status':'POSTGRES_REAL_TEMPLATE_IMPORT_PASS','packageItems':7,'templates':2,'images':5,
                  'secondRunCmsWrites':0,'siteSettingsAndOtherCms':'PRESERVED','protectedData':'PRESERVED',
                  'imageGetHash':'PASS','lostTemplateResponseRecovery':'PASS','auth':'TEST_PRINCIPAL_NOT_LOGIN_FILTER_E2E'}
        demo.save_json(demo.ROOT/'.local/cms-demo-share/template-fixture-verification.json',result)
        print(demo.canonical(result))


if __name__=='__main__':
    main()
