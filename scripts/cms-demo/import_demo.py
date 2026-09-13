#!/usr/bin/env python3
"""Local-only CMS fixture import. Python standard library; no SQL writes or retries.

Dry-run is the default. An apply requires a reviewed plan hash and an exclusive
CMS editing window. HTTP CRUD is not an atomic batch: see the team handoff.
"""
from __future__ import annotations

import argparse
from collections import Counter
from contextlib import contextmanager
import copy
import getpass
import hashlib
import json
import os
from pathlib import Path
import re
import shutil
import subprocess
import sys
import urllib.error
import urllib.parse
import urllib.request
import uuid


ROOT = Path(__file__).resolve().parents[2]
FIELDS = {
    'image': ('sha256', 'contentType'),
    'group': ('key', 'label', 'displayOrder', 'enabled'),
    'code': ('value', 'label', 'displayOrder', 'enabled'),
    'content': ('title', 'body'),
    'board': ('name', 'description', 'displayType', 'regionGroupKey', 'categoryGroupKey'),
    'post': ('title', 'body', 'thumbnailImageId', 'thumbnailAlt', 'regionCodeId', 'categoryCodeId'),
    'menu': ('name', 'path', 'displayOrder', 'targetType', 'parentId', 'targetId'),
    'template': ('key', 'layout', 'primaryColor', 'siteName', 'headerText', 'footerText',
                 'heroImageUrl', 'heroTitle', 'heroSubtitle', 'heroButtonLabel', 'heroButtonUrl'),
    'site': ('key', 'siteName', 'publicPath', 'templateKey', 'enabled'),
}
TABLES = {k: 'cms_' + v for k, v in {
    'image':'content_image', 'group':'code_group', 'code':'code', 'content':'content',
    'board':'board', 'post':'post', 'menu':'menu', 'template':'template', 'site':'site'}.items()}
REF_KIND = {'thumbnailImageId':'image', 'regionCodeId':'code', 'categoryCodeId':'code', 'parentId':'menu'}
KEY_FIELD = {'group':'key', 'template':'key', 'site':'key'}
META = ('_authorId', '_createdAt', '_updatedAt', '_deleted', '_deletedAt', '_active', '_default', '_byteSize')
TEMPLATE_IMAGE_FIELDS = tuple(n for n in FIELDS['template'] if n not in ('siteName','heroImageUrl')) + ('heroImages',)


class Blocked(Exception):
    pass


def canonical(value):
    return json.dumps(value, ensure_ascii=False, sort_keys=True, separators=(',', ':'))


def digest(value):
    return hashlib.sha256(canonical(value).encode('utf-8')).hexdigest()


def require(condition, message):
    if not condition:
        raise Blocked(message)


def identity(kind, row):
    return row[KEY_FIELD.get(kind, 'id')]


def project(kind, row, names=None):
    return {name: row.get(name) for name in (FIELDS[kind] if names is None else names)}


def save_json(path, value):
    path.parent.mkdir(parents=True, exist_ok=True)
    temp = path.with_suffix(path.suffix + '.tmp')
    with open(temp, 'w', encoding='utf-8') as handle:
        if os.name != 'nt':
            os.chmod(temp, 0o600)
        handle.write(json.dumps(value, ensure_ascii=False, indent=2) + '\n')
        handle.flush()
        os.fsync(handle.fileno())
    os.replace(temp, path)


def read_json(path, default=None):
    return json.loads(path.read_text(encoding='utf-8-sig')) if path.exists() else default


@contextmanager
def exclusive(directory):
    directory.mkdir(parents=True, exist_ok=True)
    lock = directory / 'import.lock'
    try:
        fd = os.open(lock, os.O_CREAT | os.O_EXCL | os.O_WRONLY, 0o600)
    except FileExistsError:
        raise Blocked('IMPORT_LOCK_EXISTS: check the owning process; do not start a second importer.') from None
    try:
        with os.fdopen(fd, 'w') as handle:
            handle.write(str(os.getpid()))
        yield
    finally:
        lock.unlink(missing_ok=True)


def load_package(directory):
    package = read_json(directory / 'manifest.json')
    require(isinstance(package, dict) and package.get('schemaVersion') in (1,2), 'Unsupported manifest.')
    image_templates = package['schemaVersion'] == 2
    require(re.fullmatch(r'[a-z0-9-]{1,60}', package.get('packageId', '')), 'Invalid package ID.')
    require(re.fullmatch(r'[0-9]{17}', package.get('minimumFlyway', '')), 'Invalid Flyway requirement.')
    items = package.get('items', [])
    require(0 < len(items) <= 1000, 'Invalid manifest item count.')
    by_key = {}
    for item in items:
        key, kind = item.get('key'), item.get('kind')
        require(isinstance(key, str) and re.fullmatch(r'[A-Za-z0-9_:/-]{1,240}', key), 'Invalid item key.')
        require(key not in by_key and kind in FIELDS, 'Duplicate key or unapproved resource kind.')
        by_key[key] = item
        require(not image_templates or kind in ('image','template'), 'Template package cannot change other resources.')
        if kind == 'image':
            require(set(item) == {'key','kind','file','sha256','contentType'}, 'Invalid image fields.')
            name = item['file']
            require(re.fullmatch(r'[a-z0-9_-]+\.(png|jpg|webp)', name), 'Unsafe image filename.')
            image_path = directory / name
            require(not image_path.is_symlink() and image_path.resolve().parent == directory.resolve(), 'Image leaves package.')
            data = image_path.read_bytes()
            require(len(data) <= 5_000_000 and hashlib.sha256(data).hexdigest() == item['sha256'], 'Image hash/size mismatch.')
            require(item['contentType'] in ('image/png','image/jpeg','image/webp'), 'Unsupported image type.')
        else:
            extras = {'groupKey'} if kind == 'code' else {'boardRef'} if kind == 'post' else set()
            require(set(item) == {'key','kind','fields'} | extras, 'Unapproved manifest metadata: ' + key)
            expected = TEMPLATE_IMAGE_FIELDS if image_templates and kind=='template' else FIELDS[kind]
            require(set(item['fields']) == set(expected), 'Unapproved business fields: ' + key)
            if image_templates and kind=='template':
                fields = item['fields']
                require(fields['layout'] in ('CLASSIC','BOLD','MINIMAL') and
                        isinstance(fields['primaryColor'],str) and re.fullmatch(r'#[0-9A-F]{6}',fields['primaryColor']),
                        'Invalid template layout/color: '+key)
                for name,limit in (('headerText',200),('footerText',200),('heroTitle',160),
                                   ('heroSubtitle',300),('heroButtonLabel',60),('heroButtonUrl',180)):
                    value = fields[name]
                    require(isinstance(value,str) and len(value.encode('utf-16-le'))//2<=limit and value==value.strip(),
                            'Invalid template text: '+key+'/'+name)
                require(fields['heroTitle'] and re.fullmatch(r'/(?:[A-Za-z0-9_-]+(?:/[A-Za-z0-9_-]+)*)?',fields['heroButtonUrl']),
                        'Template requires a title and local button path: '+key)
                images = fields['heroImages']
                require(isinstance(images,list) and len(images)<=5, 'Template image limit exceeded: '+key)
                for entry in images:
                    require(isinstance(entry,dict) and set(entry)=={'imageRef','title','description'}, 'Invalid template image fields: '+key)
                    require(isinstance(entry['imageRef'],str), 'Invalid template image reference: '+key)
                    for name,limit in (('title',120),('description',240)):
                        value = entry[name]
                        require(isinstance(value,str) and value==value.strip() and len(value.encode('utf-16-le'))//2<=limit,
                                'Invalid image caption: '+key)
            for name in ('key','groupKey','value','regionGroupKey','categoryGroupKey','templateKey'):
                val = item.get(name) if name == 'groupKey' else item['fields'].get(name)
                if val is not None:
                    require(isinstance(val,str) and re.fullmatch(r'[A-Za-z0-9_-]{1,40}', val), 'Invalid business key: ' + key)
            if 'body' in item['fields']:
                body = item['fields']['body']
                require(isinstance(body,str) and len(body) <= 200000, 'Invalid body: ' + key)
                require('/api/site/images/' not in body, 'Source image ID must be replaced by a reference: ' + key)
                require(isinstance(json.loads(body), dict), 'Expected a CMS document: ' + key)
    for item in items:
        f = item.get('fields', {})
        for entry in f.get('heroImages',[]):
            ref = entry['imageRef']
            require(ref in by_key and by_key[ref]['kind']=='image', 'Unknown template image reference: '+item['key'])
        for name, value in f.items():
            if isinstance(value, dict):
                require(set(value) == {'ref'} and value['ref'] in by_key, 'Unresolved reference: ' + item['key'])
                expected = REF_KIND.get(name)
                if name == 'targetId':
                    expected = {'CONTENT':'content','BOARD':'board'}.get(f['targetType'])
                require(expected and by_key[value['ref']]['kind'] == expected, 'Wrong reference kind: ' + item['key'])
            elif name in REF_KIND or name == 'targetId':
                require(value is None, 'Numeric source ID is forbidden: ' + item['key'])
        for ref in re.findall(r'\{\{([^{}]+)\}\}', f.get('body', '')):
            require(ref in by_key and by_key[ref]['kind'] == 'image', 'Unknown body image reference.')
        if item['kind'] == 'post':
            require(item['boardRef'] in by_key and by_key[item['boardRef']]['kind'] == 'board', 'Unknown board reference.')
    require(len({i['fields']['path'] for i in items if i['kind']=='menu'}) == sum(i['kind']=='menu' for i in items), 'Duplicate menu paths.')
    return package, digest(package)


def resolved_fields(item, mapping, placeholders=True, current=None):
    if item['kind'] == 'image':
        return {name: item[name] for name in FIELDS['image']}
    result = copy.deepcopy(item['fields'])
    if 'heroImages' in result:
        images = []
        for entry in result['heroImages']:
            ref = entry['imageRef']
            require(placeholders or ref in mapping, 'Missing saved image: '+ref)
            url = '/api/site/images/'+str(mapping[ref]) if ref in mapping else {'ref':ref}
            images.append({'url':url,'title':entry['title'],'description':entry['description']})
        result['heroImages'] = images
        result['heroImageUrls'] = [entry['url'] for entry in images]
        result['heroImageUrl'] = images[0]['url'] if images else ''
        result['siteName'] = current.get('siteName') if current else None
        return result
    for name, value in result.items():
        if isinstance(value, dict):
            ref = value['ref']
            if ref in mapping:
                result[name] = mapping[ref]
            else:
                require(placeholders, 'Missing saved reference: ' + ref)
    if 'body' in result:
        for ref in re.findall(r'\{\{([^{}]+)\}\}', result['body']):
            if ref in mapping:
                result['body'] = result['body'].replace('{{' + ref + '}}', '/api/site/images/' + str(mapping[ref]))
            else:
                require(placeholders, 'Missing saved image: ' + ref)
    return result


def ordered_items(package):
    pending, done, result = list(package['items']), set(), []
    while pending:
        available = []
        for item in pending:
            f = item.get('fields', {})
            dependencies = {v['ref'] for v in f.values() if isinstance(v,dict)}
            dependencies.update(entry['imageRef'] for entry in f.get('heroImages',[]))
            dependencies.update(re.findall(r'\{\{([^{}]+)\}\}', f.get('body','')))
            if item['kind'] == 'post':
                dependencies.add(item['boardRef'])
            if item['kind'] == 'code':
                dependencies.update(i['key'] for i in package['items'] if i['kind']=='group' and i['fields']['key']==item['groupKey'])
            if item['kind'] == 'board':
                dependencies.update(i['key'] for i in package['items'] if i['kind']=='group' and i['fields']['key'] in (f['regionGroupKey'],f['categoryGroupKey']))
            if item['kind'] == 'site':
                dependencies.update(i['key'] for i in package['items'] if i['kind']=='template' and i['fields']['key']==f['templateKey'])
            if dependencies <= done:
                available.append(item)
        require(available, 'Cyclic manifest references.')
        # A stable dependency order makes plans and partial imports reproducible.
        for item in available:
            result.append(item)
            done.add(item['key'])
            pending.remove(item)
    return result


def build_plan(package, package_hash, snapshot, protected, bindings, journal, template_sites=()):
    by_key = {i['key']:i for i in package['items']}
    require(set(bindings) <= set(by_key), 'Bindings contain an unknown package key.')
    require(not template_sites or package['schemaVersion']==2, 'Site impact acknowledgment is for template image packages only.')
    require(set(template_sites)<={s['key'] for s in snapshot['site']}, 'Unknown site impact acknowledgment.')
    mapping = dict(journal.get('mapping', {}))
    require(set(mapping) <= set(by_key), 'Journal contains an unknown package key.')
    actions, blockers, claimed = [], [], {}
    for item in ordered_items(package):
        key, kind = item['key'], item['kind']
        rows = snapshot[kind]
        active = [r for r in rows if r.get('_deleted','N') == 'N']
        ident = mapping.get(key)
        explicit = bindings.get(key)
        if ident is not None and explicit not in (None, 'new', ident):
            blockers.append('JOURNAL_BINDING_CONFLICT: ' + key)
        if ident is None and explicit is not None and explicit != 'new':
            require(type(explicit) is int and explicit > 0 and kind in ('content','board','post','menu'), 'Invalid explicit binding: '+key)
            ident = explicit
        matches = []
        if ident is not None:
            matches = [r for r in active if identity(kind,r)==ident]
            if len(matches) != 1:
                blockers.append('BOUND_RESOURCE_MISSING_OR_DELETED: ' + key)
        elif kind in KEY_FIELD:
            matches = [r for r in active if r['key']==item['fields']['key']]
        elif kind == 'image':
            matches = sorted([r for r in active if project(kind,r)==resolved_fields(item,mapping)],key=lambda r:r['id'])[:1]
        elif kind == 'code':
            matches = [r for r in active if r['groupKey']==item['groupKey'] and r['value']==item['fields']['value']]
        elif kind == 'menu':
            matches = [r for r in active if r['path']==item['fields']['path']]
        elif explicit != 'new':
            label = 'name' if kind == 'board' else 'title'
            candidates = [r for r in active if r[label]==item['fields'][label]]
            if kind == 'post':
                candidates = [r for r in candidates if r['boardId']==mapping.get(item['boardRef'])]
                # Adopting an exact existing sample is safe; differing content needs a binding.
                matches = [r for r in candidates if project(kind,r)==resolved_fields(item,mapping)]
            if candidates and not matches:
                blockers.append('EXPLICIT_BINDING_REQUIRED: ' + key + ' candidates=' + ','.join(str(r['id']) for r in candidates))
        if len(matches) > 1:
            blockers.append('AMBIGUOUS_TARGET: ' + key)
        current = matches[0] if len(matches)==1 else None
        if current:
            ident = identity(kind,current)
            claim = (kind,ident)
            if claim in claimed and kind != 'image':
                blockers.append('MULTIPLE_ITEMS_ONE_TARGET: '+key)
            claimed[claim] = key
            mapping[key] = ident
            if kind == 'menu' and current['path'] != item['fields']['path']:
                blockers.append('MENU_BINDING_PATH_MISMATCH: '+key)
            if kind == 'post' and current['boardId'] != mapping.get(item['boardRef']):
                blockers.append('POST_BOARD_MISMATCH: '+key)
            if kind == 'code' and (current['groupKey'] != item['groupKey'] or current['value'] != item['fields']['value']):
                blockers.append('CODE_IDENTITY_CHANGED: '+key)
            if kind == 'image' and project(kind,current) != resolved_fields(item,mapping):
                blockers.append('IMAGE_IDENTITY_CHANGED: '+key)
        desired = resolved_fields(item,mapping,current=current)
        action = 'SKIP' if current and project(kind,current,desired)==desired else 'UPDATE' if current else 'CREATE'
        if not current and kind == 'template':
            blockers.append('TEMPLATE_MUST_ALREADY_EXIST: '+key)
        if kind == 'site' and current and not current.get('_default',False):
            blockers.append('SITE_IS_NOT_CURRENT_DEFAULT: '+key)
        if kind == 'site' and not current:
            blockers.append('SITE_MUST_ALREADY_EXIST: '+key)
        actions.append({'key':key,'kind':kind,'action':action,'id':ident if current else None,
                        'before':current,'after':desired})
    # A selected content/template is not allowed to silently affect unrelated navigation/sites.
    selected_menus = {a['id'] for a in actions if a['kind']=='menu' and a['id'] is not None}
    selected_sites = {a['id'] for a in actions if a['kind']=='site' and a['id'] is not None}
    for a in actions:
        if a['action'] != 'UPDATE':
            continue
        if a['kind'] in ('content','board'):
            if any(m['targetType']==a['kind'].upper() and m['targetId']==a['id'] and m['id'] not in selected_menus for m in snapshot['menu']):
                blockers.append('UNSELECTED_MENU_REFERENCES_TARGET: '+a['key'])
        if a['kind']=='template' and any(s['templateKey']==a['id'] and s['key'] not in selected_sites
                                       and s['key'] not in template_sites for s in snapshot['site']):
            blockers.append('UNSELECTED_SITE_REFERENCES_TEMPLATE: '+a['key'])
    for kind in ('group','code'):
        # Code labels are shared with local posts; do not change an existing meaning globally.
        for a in actions:
            if a['kind']==kind and a['action']=='UPDATE':
                blockers.append('EXISTING_CODE_DIFFERS_PRESERVE_LOCAL: '+a['key'])
    plan = {'packageId':package['packageId'],'packageHash':package_hash,'target':snapshot['_target'],
            'snapshotHash':digest(snapshot),'protectedHash':digest(protected),'bindings':bindings,
            'journalHash':digest(journal),'mapping':mapping,'actions':actions,'blockers':sorted(set(blockers))}
    if snapshot['_jobs']:
        plan['blockers'].append('NONTERMINAL_NATURAL_CMS_JOBS: '+str(len(snapshot['_jobs'])))
    if package['schemaVersion']==2:
        targets = {a['id'] for a in actions if a['kind']=='template'}
        plan['templateSiteImpacts'] = [dict(s,settingsAction='PRESERVE',acknowledged=s['key'] in template_sites)
                                       for s in snapshot['site'] if s['templateKey'] in targets]
        plan['acknowledgedTemplateSites'] = sorted(set(template_sites))
    plan['planHash'] = digest(plan)
    return plan


def assert_only_change(before, after, kind, ident, desired, is_create):
    for other in FIELDS:
        old = [r for r in before[other] if other!=kind or identity(other,r)!=ident]
        new = [r for r in after[other] if other!=kind or identity(other,r)!=ident]
        require(old==new, 'CONCURRENT_OR_UNEXPECTED_CMS_CHANGE: '+other)
    matches = [r for r in after[kind] if identity(kind,r)==ident]
    require(len(matches)==1 and project(kind,matches[0],desired)==desired, 'CMS_READBACK_MISMATCH: '+kind)
    old = [r for r in before[kind] if identity(kind,r)==ident]
    if is_create:
        require(not old, 'API_REUSED_EXISTING_ID: '+kind)
    else:
        require(len(old)==1, 'UPDATE_TARGET_DISAPPEARED: '+kind)
        stable = set(old[0]) - set(desired) - {'_updatedAt'}
        require(all(old[0].get(k)==matches[0].get(k) for k in stable), 'AUTHOR_OR_METADATA_CHANGED: '+kind)
    require(before['_target']==after['_target'], 'TARGET_DATABASE_CHANGED')
    require(before['_jobs']==after['_jobs'], 'NATURAL_CMS_JOB_CHANGED_DURING_IMPORT')
    old_history = {r['id']:r['hash'] for r in before['_history']}
    new_history = {r['id']:r['hash'] for r in after['_history']}
    require(all(new_history.get(key)==value for key,value in old_history.items()), 'EXISTING_CMS_HISTORY_CHANGED')
    expected = 1 if kind in ('menu','content','board','post','template') else 0
    require(len(new_history)-len(old_history)==expected, 'CMS_HISTORY_APPEND_MISMATCH')


def endpoint(item, ident, mapping):
    kind = item['kind']
    if kind in ('image','group','content','board','menu','template'):
        base = '/api/cms/' + {'image':'images','group':'code-groups','content':'contents',
                            'board':'boards','menu':'menus','template':'templates'}[kind]
    elif kind=='site':
        base = '/api/admin/cms/sites'
    elif kind=='code':
        base = '/api/cms/code-groups/' + item['groupKey'] + '/codes' if ident is None else '/api/cms/codes'
    else:
        base = '/api/cms/boards/' + str(mapping[item['boardRef']]) + '/posts' if ident is None else '/api/cms/posts'
    return base if ident is None else base + '/' + str(ident)


def apply_plan(client, package, package_dir, plan, journal, journal_path):
    require(not plan['blockers'], 'PLAN_BLOCKED: '+ '; '.join(plan['blockers']))
    require(not journal.get('inflight'), 'INFLIGHT_REQUEST: run read-only --recover first; never delete the journal.')
    current = client.snapshot()
    require(digest(current)==plan['snapshotHash'], 'STALE_PLAN: CMS or Natural CMS state changed.')
    protected = client.protected()
    require(digest(protected)==plan['protectedHash'], 'STALE_PLAN: protected data changed.')
    mapping = dict(plan['mapping'])
    journal.update({'packageHash':plan['packageHash'],'target':plan['target'],'mapping':mapping})
    save_json(journal_path, journal)
    actions = {a['key']:a for a in plan['actions']}
    writes = 0
    for item in ordered_items(package):
        action = actions[item['key']]
        if action['action']=='SKIP':
            continue
        # Check all CMS state again immediately before every write. Operators must still
        # enforce quiescence: a third-party writer can race this check and the HTTP call.
        fresh = client.snapshot()
        require(fresh==current and not fresh['_jobs'], 'CONCURRENT_CHANGE_BEFORE_WRITE; no further request sent.')
        desired = resolved_fields(item,mapping,placeholders=False,current=action['before'])
        ident = action['id']
        journal['inflight'] = {'key':item['key'],'kind':item['kind'],'id':ident,'desired':desired,
                               'beforeIds':[identity(item['kind'],r) for r in current[item['kind']]],
                               'beforeSnapshot':current}
        save_json(journal_path,journal)
        method = 'POST' if ident is None else 'PUT'
        path = endpoint(item,ident,mapping)
        if item['kind']=='image':
            response = client.upload(path,package_dir/item['file'],item['contentType'])
        else:
            payload = dict(desired)
            if item['kind'] in ('template','site') and ident is not None:
                payload.pop('key',None)
            response = client.request(method,path,payload)
        saved_id = identity(item['kind'],response)
        require(ident is None or saved_id==ident, 'API_RETURNED_WRONG_ID; reconcile inflight before continuing.')
        after = client.snapshot()
        assert_only_change(current,after,item['kind'],saved_id,desired,ident is None)
        mapping[item['key']] = saved_id
        journal['mapping'] = dict(mapping)
        journal['inflight'] = None
        save_json(journal_path,journal)
        current = after
        writes += 1
        print('SAVED_READBACK_PASS: '+item['key'],flush=True)
    require(client.protected()==protected, 'PROTECTED_DATA_CHANGED: import stopped; do not roll back AI or account data.')
    require(client.snapshot()==current, 'CONCURRENT_CHANGE_AFTER_IMPORT')
    journal['lastSuccessfulPlan'] = plan['planHash']
    save_json(journal_path,journal)
    return {'status':'CMS_IMPORT_PASS','cmsWrites':writes,'mappedItems':len(mapping),'protectedData':'PRESERVED',
            'existingCmsRows':'PRESERVED_EXCEPT_APPROVED_FIELDS','atomicBatch':False}


def recover(client, journal, journal_path):
    pending = journal.get('inflight')
    require(pending, 'No inflight request to recover.')
    now = client.snapshot()
    require(not now['_jobs'] and now['_target']==journal['target'], 'Recovery target/jobs changed.')
    kind = pending['kind']
    candidates = [r for r in now[kind] if project(kind,r,pending['desired'])==pending['desired'] and
                  (identity(kind,r)==pending['id'] if pending['id'] is not None else identity(kind,r) not in pending['beforeIds'])]
    require(len(candidates)==1, 'RECOVERY_AMBIGUOUS_OR_NOT_COMMITTED: preserve journal; owner must inspect the pending request.')
    ident = identity(kind,candidates[0])
    assert_only_change(pending['beforeSnapshot'],now,kind,ident,pending['desired'],pending['id'] is None)
    journal['mapping'][pending['key']] = ident
    journal['inflight'] = None
    save_json(journal_path,journal)
    return {'status':'INFLIGHT_RECOVERED_READ_ONLY','cmsWrites':0,'key':pending['key']}


# Database access below is SELECT-only, in an explicit read-only transaction.
# Read snapshots include local CMS authors/timestamps for preservation checks; these
# are written only below ignored .local/, never into the distributable manifest.
SELECTS = {
 'image': 'image_id id,content_type "contentType",encode(sha256(bytes),\'hex\') sha256,byte_size "_byteSize",author_id "_authorId",created_at "_createdAt"',
 'group': 'group_key key,label,display_order "displayOrder",enabled',
 'code': 'code_id id,group_key "groupKey",code_value value,label,display_order "displayOrder",enabled',
 'content': 'content_id id,title,body,author_id "_authorId",created_at "_createdAt",updated_at "_updatedAt",deleted_yn "_deleted",deleted_at "_deletedAt"',
 'board': 'board_id id,board_name name,description,display_type "displayType",region_group_key "regionGroupKey",category_group_key "categoryGroupKey",created_at "_createdAt",updated_at "_updatedAt",deleted_yn "_deleted",deleted_at "_deletedAt"',
 'post': 'post_id id,board_id "boardId",title,body,thumbnail_image_id "thumbnailImageId",thumbnail_alt "thumbnailAlt",region_code_id "regionCodeId",category_code_id "categoryCodeId",author_id "_authorId",created_at "_createdAt",updated_at "_updatedAt",deleted_yn "_deleted",deleted_at "_deletedAt"',
 'menu': 'menu_id id,menu_name name,path,parent_menu_id "parentId",display_order "displayOrder",target_type "targetType",target_id "targetId"',
 'template': 'template_key key,layout,primary_color "primaryColor",site_name "siteName",header_text "headerText",footer_text "footerText",hero_image_url "heroImageUrl",hero_title "heroTitle",hero_subtitle "heroSubtitle",hero_button_label "heroButtonLabel",hero_button_url "heroButtonUrl",active_yn=\'Y\' "_active",updated_at "_updatedAt"',
 'site': 'site_key key,site_name "siteName",public_path "publicPath",template_key "templateKey",enabled_yn=\'Y\' enabled,default_yn=\'Y\' "_default",updated_at "_updatedAt"',
}


class NoRedirect(urllib.request.HTTPRedirectHandler):
    def redirect_request(self, *args, **kwargs):
        raise Blocked('HTTP_REDIRECT_BLOCKED')


class LocalClient:
    def __init__(self, base_url, compose_project, database, backend_root):
        parsed = urllib.parse.urlsplit(base_url)
        require(parsed.scheme=='http' and parsed.hostname in ('127.0.0.1','localhost') and
                not parsed.username and not parsed.password and parsed.path in ('','/') and not parsed.query and not parsed.fragment,
                'Only an explicit loopback HTTP endpoint is allowed.')
        require(re.fullmatch(r'[a-z0-9][a-z0-9_-]{0,80}',compose_project), 'Invalid Compose project.')
        require(re.fullmatch(r'[a-zA-Z0-9_]{1,63}',database), 'Invalid database name.')
        self.base = base_url.rstrip('/')
        self.compose_project = compose_project
        self.database = database
        self.backend_root = backend_root
        self.docker = shutil.which('docker')
        if not self.docker and os.name=='nt':
            fallback = Path(os.environ.get('ProgramFiles','C:/Program Files'))/'Docker/Docker/resources/bin/docker.exe'
            if fallback.is_file():
                self.docker = str(fallback)
        require(self.docker, 'Docker CLI not found; do not install automatically.')
        names = self.command('ps','--filter','label=com.docker.compose.project='+compose_project,'--format','{{.ID}}').split()
        require(names, 'Running Compose project not found.')
        containers = json.loads(self.command('inspect',*names))
        services = {}
        for service in ('database','spring-app','nginx'):
            matches = [c for c in containers if c['Config']['Labels'].get('com.docker.compose.service')==service]
            require(len(matches)==1, 'Missing/ambiguous running Compose service: '+service)
            services[service] = matches[0]
        port = str(parsed.port or 80)
        ports = services['nginx']['NetworkSettings']['Ports'].get('80/tcp') or []
        require(any(p['HostIp']=='127.0.0.1' and p['HostPort']==port for p in ports), 'URL does not match this Compose ingress.')
        self.db_container = services['database']['Id']
        self.app_container = services['spring-app']['Id']
        self.runtime_env = dict(e.split('=',1) for e in services['spring-app']['Config']['Env'])
        expected_jdbc = 'jdbc:postgresql://database:5432/' + database
        require(all(self.runtime_env.get(name)==expected_jdbc for name in ('AXMS_CORE_DB_JDBC_URL','AXMS_CMS_DB_URL')),
                'Spring database settings do not match the selected local database.')
        addresses = {n.get('IPAddress') for n in services['database']['NetworkSettings']['Networks'].values()}
        resolved = {line.split()[0] for line in self.command('exec',self.app_container,'getent','hosts','database').splitlines() if line.strip()}
        require(resolved and resolved <= addresses, 'Spring database hostname does not resolve to the selected container.')
        self.opener = urllib.request.build_opener(urllib.request.ProxyHandler({}),NoRedirect())
        self.token = None
        self.actor_id = None

    def command(self,*arguments,input_text=None):
        completed = subprocess.run([self.docker,*arguments],input=input_text,text=True,encoding='utf-8',
                                   capture_output=True,timeout=120)
        require(completed.returncode==0, 'Docker read check failed; command output suppressed for credential safety.')
        return completed.stdout.strip()

    def sql(self,query):
        return self.command('exec','-i',self.db_container,'psql','-X','-qAt','-U','bootstrap_admin','-d',self.database,
                            '-v','ON_ERROR_STOP=1',input_text='BEGIN ISOLATION LEVEL REPEATABLE READ READ ONLY; SET LOCAL statement_timeout=\'60s\'; '+query+' COMMIT;')

    def snapshot(self):
        pairs = []
        for kind, select in SELECTS.items():
            if kind=='template' and getattr(self,'template_images',False):
                select += ',hero_image_urls "heroImageUrls",hero_images "heroImages"'
            order = KEY_FIELD.get(kind,'id')
            pairs.append("'"+kind+"',(SELECT coalesce(json_agg(t ORDER BY t."+order+"),'[]'::json) FROM (SELECT "+select+' FROM app.'+TABLES[kind]+') t)')
        pairs.append("'_jobs',(SELECT coalesce(json_agg(t ORDER BY t.job_id),'[]'::json) FROM (SELECT job_id,status,resource_type,resource_id FROM app.natural_cms_job WHERE status NOT IN ('COMPLETED','REJECTED')) t)")
        pairs.append("'_target',json_build_object('systemId',(SELECT system_identifier::text FROM pg_control_system()),'database',current_database(),'databaseOid',(SELECT oid FROM pg_database WHERE datname=current_database()))")
        pairs.append("'_history',(SELECT coalesce(json_agg(h ORDER BY h.id),'[]'::json) FROM (SELECT change_id id,md5(row_to_json(t)::text) hash FROM app.cms_change_history t) h)")
        result = json.loads(self.sql('SELECT json_build_object('+','.join(pairs)+');'))
        result['_target'].update({'endpoint':self.base,'project':getattr(self,'compose_project','isolated-fixture')})
        return result

    def protected(self):
        omitted = set(TABLES.values()) | {'admin_session','cms_change_history'}
        names = json.loads(self.sql("SELECT coalesce(json_agg(tablename ORDER BY tablename),'[]'::json) FROM pg_tables WHERE schemaname='app';"))
        checks = []
        for name in names:
            require(re.fullmatch(r'[a-z0-9_]+',name), 'Unexpected table identifier.')
            if name not in omitted:
                checks.append("SELECT '"+name+"' name,count(*) n,md5(coalesce(string_agg(row_to_json(t)::text,'|' ORDER BY row_to_json(t)::text),'')) hash FROM app."+name+' t')
        checks.append("SELECT 'flyway_schema_history' name,count(*) n,md5(coalesce(string_agg(row_to_json(t)::text,'|' ORDER BY row_to_json(t)::text),'')) hash FROM public.flyway_schema_history t")
        return json.loads(self.sql("SELECT json_agg(t ORDER BY name) FROM ("+' UNION ALL '.join(checks)+') t;'))

    def prerequisites(self,package):
        versions = json.loads(self.sql("SELECT coalesce(json_agg(version),'[]'::json) FROM public.flyway_schema_history WHERE success;"))
        require(package['minimumFlyway'] in versions, 'Required CMS Flyway revision missing; use the official approved runtime flow first.')
        invalid = self.sql('SELECT count(*) FROM public.flyway_schema_history WHERE NOT success;')
        require(invalid=='0', 'Unsuccessful Flyway history; do not repair/clean automatically.')
        for path in package.get('requiredStaticAssets',[]):
            require(re.fullmatch(r'/images/cms/[a-z0-9_-]+\.png',path), 'Unapproved static asset path.')
            data, content_type = self.http('GET',path,raw=True)
            require(data.startswith(b'\x89PNG\r\n\x1a\n') and content_type.startswith('image/png'), 'Frontend asset missing: '+path)

    def http(self,method,path,data=None,content_type='application/json',raw=False):
        require(path.startswith('/api/') or path.startswith('/images/cms/'), 'Unapproved HTTP path.')
        require(not any(c in path for c in ('?','#','\r','\n','..')), 'Unsafe HTTP path.')
        headers = {'Accept':'application/json','X-Trace-Id':str(uuid.uuid4()),'Idempotency-Key':str(uuid.uuid4())}
        if self.token:
            headers['Authorization'] = 'Bearer '+self.token
        if data is not None:
            headers['Content-Type'] = content_type
        request = urllib.request.Request(self.base+path,data=data,method=method,headers=headers)
        try:
            with self.opener.open(request,timeout=40) as response:
                payload = response.read(6_000_001)
                require(len(payload)<=6_000_000, 'HTTP response exceeds local import limit.')
                if raw:
                    return payload,response.headers.get('Content-Type','')
                return json.loads(payload) if payload else None
        except (urllib.error.URLError,TimeoutError,OSError,ValueError):
            raise Blocked('HTTP_REQUEST_FAILED: '+method+' '+path+'; no automatic retry, inspect journal.') from None

    def request(self,method,path,payload=None):
        return self.http(method,path,canonical(payload).encode('utf-8') if payload is not None else None)

    def authenticate(self,use_demo):
        if use_demo:
            config = (self.backend_root/'src/main/resources/application-local-full.yml').read_text(encoding='utf-8')
            def value(name):
                if name in self.runtime_env:
                    return self.runtime_env[name]
                found = re.search(r'\$\{'+name+r':([^}]+)\}',config)
                require(found, 'Local demo account is not configured; use interactive login.')
                return found.group(1)
            login, password = value('AXMS_AUTH_SUPER_ADMIN_LOGIN_ID'),value('AXMS_AUTH_SUPER_ADMIN_PASSWORD')
        else:
            login = input('Local CMS admin login ID: ')
            password = getpass.getpass('Local CMS admin password (not logged): ')
        try:
            session = self.request('POST','/api/auth/login',{'schemaVersion':'1.0','loginId':login,'passwordValue':password})
            require(session['actor']['role'] in ('SUPER_ADMIN','GENERAL_ADMIN'), 'CMS administrator required.')
            self.token = session['sessionToken']
            self.actor_id = session['actor'].get('actorId')
        finally:
            password = login = None
            self.runtime_env = {}
        # Cross-check the HTTP backend against the selected database before any CMS write.
        menus = self.request('GET','/api/cms/menus')
        db_menus = self.snapshot()['menu']
        require(sorted(menus,key=lambda m:m['id'])==db_menus, 'HTTP_AND_DATABASE_TARGET_MISMATCH')

    def upload(self,path,file,content_type):
        boundary = 'axms-'+uuid.uuid4().hex
        data = ('--'+boundary+'\r\nContent-Disposition: form-data; name="file"; filename="'+file.name+
                '"\r\nContent-Type: '+content_type+'\r\n\r\n').encode()+file.read_bytes()+('\r\n--'+boundary+'--\r\n').encode()
        return self.http('POST',path,data,'multipart/form-data; boundary='+boundary)


def main(argv=None):
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('--package',type=Path,default=ROOT/'demo/cms-tour-v1')
    parser.add_argument('--base-url',default='http://127.0.0.1:18080')
    parser.add_argument('--project',default='axms-spring-dev')
    parser.add_argument('--database',default='ax_module_studio')
    parser.add_argument('--bindings',type=Path)
    parser.add_argument('--allow-template-site',action='append',default=[],metavar='SITE_KEY',
                        help='Include this existing site in the template appearance impact review; keep its settings unchanged.')
    parser.add_argument('--apply',action='store_true')
    parser.add_argument('--approve-plan')
    parser.add_argument('--confirm-quiescent',action='store_true')
    parser.add_argument('--use-local-demo-account',action='store_true')
    parser.add_argument('--recover',action='store_true')
    args = parser.parse_args(argv)
    package, package_hash = load_package(args.package)
    require(not (args.apply and args.recover), 'Apply and recovery are separate operations.')
    require(not args.apply or (args.approve_plan and args.confirm_quiescent), 'Apply requires --approve-plan and --confirm-quiescent after owner approval.')
    require(not args.recover or args.confirm_quiescent, 'Recovery requires an exclusive CMS editing window.')
    bindings = read_json(args.bindings,{}) if args.bindings else {}
    require(isinstance(bindings,dict), 'Bindings must be a JSON object.')
    state_dir = ROOT/'.local/cms-demo-share'/package['packageId']
    with exclusive(state_dir):
        client = LocalClient(args.base_url,args.project,args.database,ROOT)
        client.prerequisites(package)
        client.template_images = package['schemaVersion']==2
        snapshot = client.snapshot()
        journal_path = state_dir/'journal.json'
        journal = read_json(journal_path,{'mapping':{},'inflight':None})
        if journal.get('target'):
            require(journal['target']==snapshot['_target'] and journal['packageHash']==package_hash,
                    'Journal belongs to another database/package. Preserve it and ask the owner.')
        if args.recover:
            print(canonical(recover(client,journal,journal_path)))
            return 0
        require(not journal.get('inflight'), 'INFLIGHT_REQUEST: inspect --recover; do not delete the journal.')
        protected = client.protected()
        plan = build_plan(package,package_hash,snapshot,protected,bindings,journal,args.allow_template_site)
        plan_path = state_dir/'plan.json'
        if args.apply:
            approved = read_json(plan_path,{})
            require(approved.get('planHash')==args.approve_plan==plan['planHash'], 'APPROVED_PLAN_STALE_OR_MISSING: run dry-run and review again.')
            require(not plan['blockers'], 'PLAN_BLOCKED: '+ '; '.join(plan['blockers']))
            client.authenticate(args.use_local_demo_account)
            save_json(state_dir/'before-approved-cms.json',{'target':plan['target'],'packageHash':package_hash,
                      'rows':[a for a in plan['actions'] if a['action']=='UPDATE']})
            result = apply_plan(client,package,args.package,plan,journal,journal_path)
            save_json(state_dir/'verification.json',result)
            print(canonical(result))
        else:
            save_json(plan_path,plan)
            print(canonical({'status':'DRY_RUN_BLOCKED' if plan['blockers'] else 'DRY_RUN_PASS','cmsWrites':0,
                  'planHash':plan['planHash'],'planFile':str(plan_path),'actions':dict(Counter(a['action'] for a in plan['actions'])),
                  'blockers':plan['blockers']}))
            return 2 if plan['blockers'] else 0
    return 0


if __name__=='__main__':
    try:
        sys.exit(main())
    except (Blocked,KeyError,TypeError,json.JSONDecodeError,FileNotFoundError,subprocess.TimeoutExpired) as error:
        # Never print arbitrary HTTP bodies, environment values, SQL output, or credentials.
        message = str(error) if isinstance(error,Blocked) else 'Malformed or unavailable local input; inspect files without exposing credentials.'
        print('CMS_IMPORT_BLOCKED: '+message,file=sys.stderr)
        sys.exit(2)
