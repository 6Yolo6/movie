"""Cloudflare Access scope checks use synthetic plans only; no API calls."""
import copy
from pathlib import Path
import sys
import unittest
sys.path.insert(0, str(Path(__file__).resolve().parents[1]))
from cloudflare_access import HOST, PATHS, PUBLIC_PATHS, matches_path, validate_plan, overlapping_apps, review_account

class CloudflareAccessTest(unittest.TestCase):
    def setUp(self):
        self.plan={'hostname':HOST,'account_id':'a'*32,'admin_emails':['admin@example.com'],
                   'protected_paths':list(PATHS),'identity_provider_type':'onetimepin',
                   'session_duration':'1h','allow_everyone':False}

    def test_exact_plan_is_accepted(self):
        self.assertEqual(validate_plan(self.plan),self.plan)

    def test_public_and_registration_paths_remain_outside_access(self):
        for path in PUBLIC_PATHS:
            self.assertFalse(any(matches_path(pattern,path) for pattern in PATHS),path)

    def test_admin_roots_descendants_and_audit_are_covered(self):
        for path in ['/admin','/admin/users','/api/admin','/api/admin/config/key',
                     '/api/resources/admin','/api/resources/admin/1/repair-invalid',
                     '/api/resources/1/audit','/api/resources/batch/audit']:
            self.assertTrue(any(matches_path(pattern,path) for pattern in PATHS),path)
        self.assertFalse(matches_path('/admin','/administrator'))

    def test_middle_wildcard_can_span_path_segments(self):
        self.assertTrue(matches_path('/api/resources/*/audit','/api/resources/one/two/audit'))

    def test_broad_or_incomplete_path_sets_are_rejected(self):
        for paths in [['/'],['/*'],['/admin/*'],list(PATHS)+['/api/resources']]:
            p=copy.deepcopy(self.plan);p['protected_paths']=paths
            with self.assertRaises(ValueError):validate_plan(p)

    def test_wildcard_emails_and_allow_everyone_are_rejected(self):
        for emails in [[],['*@example.com'],['admin@example.com','ADMIN@example.com']]:
            p=copy.deepcopy(self.plan);p['admin_emails']=emails
            with self.assertRaises(ValueError):validate_plan(p)
        self.plan['allow_everyone']=True
        with self.assertRaises(ValueError):validate_plan(self.plan)

    def test_existing_root_wildcard_and_child_apps_require_review(self):
        for uri in [HOST,HOST+'/admin',HOST+'/admin/users',HOST+'/*','*.dpdns.org/admin']:
            rows=overlapping_apps([{'id':'fixture','type':'self_hosted','domain':uri}])
            self.assertEqual(len(rows),1,uri)
        self.assertFalse(overlapping_apps([{'id':'other','domain':'other.example.com/admin'},
                                           {'id':'public','domain':HOST+'/other-team'}]))

    def test_destination_only_conflicts_are_detected(self):
        self.assertEqual(len(overlapping_apps([{'id':'fixture','destinations':[{'type':'public','uri':HOST+'/api/admin'}]}])),1)

    def test_covering_parent_zone_in_same_account_is_accepted(self):
        result=review_account(self.plan,{'auth_domain':'fixture.cloudflareaccess.com'},
                              [{'id':'otp','type':'onetimepin'}],[],
                              [{'name':'dpdns.org','status':'active','account':{'id':'a'*32}}])
        self.assertTrue(result['active_zone_verified'])

    def test_zone_from_other_account_or_unrelated_domain_is_rejected(self):
        for zone in [{'name':HOST,'status':'active','account':{'id':'b'*32}},
                     {'name':'other.example.com','status':'active','account':{'id':'a'*32}}]:
            result=review_account(self.plan,{'auth_domain':'fixture.cloudflareaccess.com'},
                                  [{'id':'otp','type':'onetimepin'}],[],[zone])
            self.assertFalse(result['active_zone_verified'])

    def test_missing_org_provider_zone_or_overlap_never_ready(self):
        org={'auth_domain':'fixture.cloudflareaccess.com'}
        providers=[{'id':'fixture-otp','type':'onetimepin'}]
        zones=[{'name':HOST,'status':'active','account':{'id':'a'*32}}]
        ready=review_account(self.plan,org,providers,[],zones)
        self.assertEqual(ready['status'],'ready_for_apply_review')
        self.assertFalse(ready['cloud_changed'])
        for o,p,a,z in [(None,providers,[],zones),(org,[],[],zones),(org,providers,[],[]),
                        (org,providers,[{'id':'fixture','domain':HOST}],zones)]:
            self.assertEqual(review_account(self.plan,o,p,a,z)['status'],'needs_attention')

if __name__=='__main__':unittest.main()
