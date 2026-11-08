"""Read-only Cloudflare Access preflight. Never creates or changes cloud resources."""
import argparse
import fnmatch
import json
import os
from pathlib import Path
import re
import subprocess
import urllib.error
import urllib.parse
import urllib.request

HOST = 'gyinghub.dpdns.org'
PATHS = ('/admin', '/api/admin', '/api/resources/admin', '/api/resources/*/audit')
PUBLIC_PATHS = ('/', '/register', '/login', '/api/auth/register',
                '/api/auth/registration-policy', '/api/auth/send-email-code',
                '/api/movies/list', '/api/resources/mine', '/api/resources/1/report',
                '/resource-search', '/media/posters/example.jpg')


def matches_path(pattern, path):
    # Cloudflare public application paths inherit to descendants; '*' can span '/'.
    expression = re.escape(pattern).replace(r'\*', '.*')
    return re.fullmatch(expression + r'(?:/.*)?', path) is not None


def validate_plan(plan):
    if plan.get('hostname') != HOST:
        raise ValueError('Unexpected hostname')
    if not re.fullmatch(r'[a-fA-F0-9]{32}', plan.get('account_id', '')):
        raise ValueError('Invalid account ID')
    if tuple(plan.get('protected_paths', [])) != PATHS:
        raise ValueError('The reviewed path set must not be broadened or reduced')
    emails = plan.get('admin_emails', [])
    if not emails or any(not re.fullmatch(r'[^\s@*]+@[^\s@*]+\.[^\s@*]+', e) for e in emails):
        raise ValueError('Explicit administrator emails required; wildcards forbidden')
    if len(emails) != len(set(e.casefold() for e in emails)):
        raise ValueError('Duplicate administrator emails')
    if plan.get('identity_provider_type') != 'onetimepin' or plan.get('session_duration') != '1h':
        raise ValueError('Unexpected authentication contract')
    if any(matches_path(p, public) for p in PATHS for public in PUBLIC_PATHS):
        raise ValueError('Plan unexpectedly protects public functionality')
    if plan.get('allow_everyone') is not False:
        raise ValueError('Allow-everyone must explicitly be disabled')
    return plan


def app_uris(app):
    uris = [app.get('domain', '')]
    uris.extend(app.get('self_hosted_domains') or [])
    for dest in app.get('destinations') or []:
        if dest.get('type', 'public') == 'public':
            uris.append(dest.get('uri', ''))
    return list(dict.fromkeys(x for x in uris if isinstance(x, str) and x))


def overlapping_apps(apps):
    overlaps = []
    for app in apps:
        for uri in app_uris(app):
            u = urllib.parse.urlsplit(uri if '://' in uri else 'https://' + uri)
            hostname = u.hostname or ''
            if not fnmatch.fnmatchcase(HOST, hostname):
                continue
            path = u.path.rstrip('/') or '/'
            # Conservatively require review of any wildcard on this hostname.
            if path == '/' or '*' in path or any(matches_path(path, p) or matches_path(p, path) for p in PATHS):
                overlaps.append({'id': app.get('id'), 'type': app.get('type'), 'review_required': True})
                break
    return overlaps


def review_account(plan, organization, providers, apps, zones):
    problems = []
    if not organization or not organization.get('auth_domain'):
        problems.append('zero_trust_organization_not_ready')
    otp = [p for p in providers if p.get('type') == 'onetimepin' and p.get('id')]
    if not otp:
        problems.append('one_time_pin_provider_not_enabled')
    overlap = overlapping_apps(apps)
    if overlap:
        problems.append('existing_application_overlap_requires_review')
    active = [z for z in zones if (z.get('name') == HOST or HOST.endswith('.' + z.get('name', '')))
              and z.get('status') == 'active'
              and z.get('account', {}).get('id', '').lower() == plan['account_id'].lower()]
    if not active:
        problems.append('expected_active_zone_not_verified_in_account')
    return {'status': 'ready_for_apply_review' if not problems else 'needs_attention',
            'cloud_changed': False, 'problems': problems,
            'application_count': len(apps), 'overlapping_applications': overlap,
            'otp_provider_ids': [p['id'] for p in otp],
            'active_zone_verified': bool(active), 'organization_ready': bool(organization and organization.get('auth_domain'))}


class NoRedirect(urllib.request.HTTPRedirectHandler):
    def redirect_request(self, req, fp, code, msg, headers, newurl):
        return None


class APIError(Exception):
    def __init__(self, status):
        self.status = status


class CloudflareReadOnly:
    def __init__(self, token):
        self.token = token
        self.opener = urllib.request.build_opener(NoRedirect())

    def get(self, path):
        if not path.startswith('/') or '://' in path or '#' in path:
            raise ValueError('Invalid API path')
        request = urllib.request.Request('https://api.cloudflare.com/client/v4' + path,
                                         headers={'Authorization': 'Bearer ' + self.token,
                                                  'Accept': 'application/json'}, method='GET')
        try:
            with self.opener.open(request, timeout=25) as response:
                body = response.read(8 * 1024 * 1024 + 1)
            if len(body) > 8 * 1024 * 1024:
                raise APIError('response_too_large')
            data = json.loads(body)
        except urllib.error.HTTPError as error:
            raise APIError(error.code) from None
        except (urllib.error.URLError, TimeoutError, OSError):
            raise APIError('transport_failure') from None
        if not data.get('success'):
            raise APIError('api_rejected')
        return data

    def pages(self, path):
        out = []
        for page in range(1, 101):
            separator = '&' if '?' in path else '?'
            data = self.get(path + separator + f'page={page}&per_page=100')
            rows = data.get('result')
            if not isinstance(rows, list):
                raise APIError('unexpected_list_shape')
            out.extend(rows)
            total = data.get('result_info', {}).get('total_pages')
            if (isinstance(total, int) and page >= total) or (total is None and len(rows) < 100):
                return out
        raise APIError('pagination_limit')


def read_dpapi_token(path):
    path = Path(path).resolve(strict=True)
    if os.name != 'nt' or str(path).lower() != str(Path('G:/gying-secrets/cloudflare-access-token.dpapi').resolve()).lower():
        raise ValueError('Token path must be the reviewed Windows private file')
    quoted = str(path).replace("'", "''")
    script = r"""$ErrorActionPreference='Stop'
$p='__PATH__'
$allowed=@([Security.Principal.WindowsIdentity]::GetCurrent().User.Value,'S-1-5-18')
foreach($target in @((Split-Path -Parent $p),$p)) {
 $acl=Get-Acl -LiteralPath $target
 foreach($rule in $acl.Access) {
  $sid=$rule.IdentityReference.Translate([Security.Principal.SecurityIdentifier]).Value
  if($rule.AccessControlType -eq 'Allow' -and $sid -notin $allowed) { throw 'Unexpected token ACL' }
 }
}
$secure=Get-Content -LiteralPath $p -Raw -Encoding UTF8 | ConvertTo-SecureString
$credential=[Management.Automation.PSCredential]::new('token',$secure)
[Console]::Out.Write($credential.GetNetworkCredential().Password)
""".replace('__PATH__', quoted)
    env = {k: v for k, v in os.environ.items() if k.upper() != 'PSMODULEPATH'}
    # This private pipe is captured in memory, never written to terminal/logs/reports.
    result = subprocess.run(['powershell.exe', '-NoProfile', '-Command', script],
                            capture_output=True, timeout=15, env=env)
    if result.returncode:
        raise ValueError('DPAPI token or private ACL verification failed')
    token = result.stdout.decode('utf-8').strip()
    if not token or any(c.isspace() for c in token):
        raise ValueError('Invalid token format')
    return token


def main():
    parser = argparse.ArgumentParser()
    parser.add_argument('--plan', required=True)
    parser.add_argument('--preflight', action='store_true', help='Read-only account/API verification; never applies')
    parser.add_argument('--report')
    args = parser.parse_args()
    plan = validate_plan(json.loads(Path(args.plan).read_text(encoding='utf-8-sig')))
    result = {'status': 'local_plan_validated', 'cloud_changed': False,
              'hostname': HOST, 'protected_paths': list(PATHS),
              'administrator_count': len(plan['admin_emails']), 'public_paths_unchanged': True}
    if args.preflight:
        try:
            token = read_dpapi_token(plan['token_dpapi_file'])
            api = CloudflareReadOnly(token)
            account = plan['account_id']
            org = api.get(f'/accounts/{account}/access/organizations').get('result')
            providers = api.get(f'/accounts/{account}/access/identity_providers').get('result')
            apps = api.pages(f'/accounts/{account}/access/apps')
            zones = api.pages('/zones?account.id=' + account)
            if not isinstance(providers, list):
                raise APIError('unexpected_identity_provider_shape')
            result.update(review_account(plan, org, providers, apps, zones))
        except FileNotFoundError:
            result.update(status='needs_local_token', cloud_changed=False)
        except APIError as error:
            result.update(status='account_preflight_failed', api_status=error.status, cloud_changed=False)
        except Exception as error:
            result.update(status='account_preflight_failed', error_class=type(error).__name__, cloud_changed=False)
    if args.report:
        # Exclusive creation preserves previous attempts; contains no token or response body.
        with Path(args.report).open('x', encoding='utf-8') as f:
            json.dump(result, f, indent=2)
    print(json.dumps(result, ensure_ascii=False, indent=2))
    if args.preflight and result['status'] != 'ready_for_apply_review':
        raise SystemExit(2)


if __name__ == '__main__':
    try:
        main()
    except (KeyboardInterrupt, SystemExit):
        raise
    except Exception as error:
        raise SystemExit('Access preflight failed (' + type(error).__name__ + '); raw output withheld')
