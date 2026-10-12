"""GitHub Actions release controller. Python standard library, no local secrets."""
import argparse
import hashlib
import json
import os
from pathlib import Path
import re
import shutil
import subprocess
import sys
import urllib.error
import urllib.request
import zipfile


def version(value):
    if not re.fullmatch(r'(0|[1-9]\d*)\.(0|[1-9]\d*)\.(0|[1-9]\d*)', value):
        raise ValueError(f'Invalid version: {value!r}; expected X.Y.Z')
    return tuple(map(int, value.split('.')))


def bump_version(current, reserved, operation):
    major, minor, patch = max([version(current)] + [version(item) for item in reserved])
    if operation == 'patch':
        result = major, minor, patch + 1
    elif operation == 'minor':
        result = major, minor + 1, 0
    elif operation == 'major':
        result = major + 1, 0, 0
    else:
        raise ValueError('New releases require patch, minor or major')
    return '.'.join(map(str, result))


def git(*args):
    result = subprocess.run(['git', *args], check=True, stdout=subprocess.PIPE,
                            stderr=subprocess.PIPE, text=True, encoding='utf-8')
    return result.stdout.strip()


def github(path, body=None, method=None, missing_ok=False):
    repo = os.environ['GITHUB_REPOSITORY']
    if not re.fullmatch(r'[\w.-]+/[\w.-]+', repo):
        raise ValueError('Invalid GITHUB_REPOSITORY')
    request = urllib.request.Request(f'https://api.github.com/repos/{repo}{path}',
        data=None if body is None else json.dumps(body).encode(),
        method=method or ('GET' if body is None else 'POST'),
        headers={'Accept': 'application/vnd.github+json', 'User-Agent': 'futa-release',
                 'Authorization': 'Bearer ' + os.environ['GH_TOKEN'],
                 'Content-Type': 'application/json', 'X-GitHub-Api-Version': '2022-11-28'})
    try:
        with urllib.request.urlopen(request, timeout=30) as response:
            return json.load(response)
    except urllib.error.HTTPError as error:
        if missing_ok and error.code == 404:
            return None
        raise RuntimeError(f'GitHub API {path} returned HTTP {error.code}') from None


def releases():
    result = []
    page = 1
    while True:
        batch = github(f'/releases?per_page=100&page={page}')
        result.extend(batch)
        if len(batch) < 100:
            return result
        page += 1


def reserved_versions(tags, items):
    result = []
    for tag in list(tags) + [item['tag_name'] for item in items]:
        if re.fullmatch(r'v(0|[1-9]\d*)\.(0|[1-9]\d*)\.(0|[1-9]\d*)', tag):
            result.append(tag[1:])
    return result


def require_unpublished(release):
    if release is not None and not release.get('draft', False):
        raise ValueError('This version is already published; choose a new version instead of replacing it')


def save(path, plan):
    path.write_text(json.dumps(plan, indent=2), encoding='utf-8')


def output(**values):
    if os.environ.get('GITHUB_OUTPUT'):
        with open(os.environ['GITHUB_OUTPUT'], 'a', encoding='utf-8') as stream:
            for key, value in values.items():
                stream.write(f'{key}={value}\n')


def prepare(state, operation):
    if os.environ.get('GITHUB_REF') != 'refs/heads/main':
        raise ValueError('Run the release workflow on main')
    head = git('rev-parse', 'HEAD')
    if head != os.environ['GITHUB_SHA'] or head != git('rev-parse', 'origin/main'):
        raise ValueError('main changed since this run was requested; start a new run on current main')
    current = Path('version.txt').read_text(encoding='utf-8').strip()
    version(current)
    items = releases()
    plan = {'base_sha': head, 'operation': operation}
    if operation == 'retry':
        tag = 'v' + current
        require_unpublished(next((item for item in items if item['tag_name'] == tag), None))
        if git('show', f'{tag}:version.txt').strip() != current:
            raise ValueError('Retry tag and version.txt disagree')
        plan['commit'] = git('rev-parse', f'{tag}^{{commit}}')
        plan['tag_object'] = git('rev-parse', f'refs/tags/{tag}')
        git('checkout', '--detach', tag)
        selected = current
    else:
        selected = bump_version(current, reserved_versions(git('tag', '--list').splitlines(), items), operation)
        tag = 'v' + selected
        Path('version.txt').write_text(selected + '\n', encoding='utf-8')
    plan.update(version=selected, tag=tag)
    save(state, plan)
    output(version=selected, tag=tag)
    print(f'Release plan: {operation} -> {tag}; version.txt prepared only in runner workspace')


def verify_jar(path, expected):
    with zipfile.ZipFile(path) as archive:
        if archive.testzip() is not None:
            raise ValueError('JAR CRC verification failed')
        metadata = json.loads(archive.read('mcmod.info').decode('utf-8-sig'))
        entries = metadata if isinstance(metadata, list) else metadata.get('modList', [metadata])
        versions = [item.get('version') for item in entries if item.get('modid') == 'futa_gtnh']
        if versions != [expected]:
            raise ValueError('JAR mod ID/version differs from version.txt and release tag')
        if 'com/futa_gtnh/FutaGtnhMod.class' not in archive.namelist():
            raise ValueError('JAR entry class is missing')


def verify(state, plan):
    selected = plan['version']
    if Path('version.txt').read_text(encoding='utf-8').strip() != selected or plan['tag'] != 'v' + selected:
        raise ValueError('version.txt and release tag disagree')
    main = Path('build/libs') / f'futa_gtnh-{selected}.jar'
    sources = Path('build/libs') / f'futa_gtnh-{selected}-sources.jar'
    verify_jar(main, selected)
    assets = Path('release-assets')
    assets.mkdir(exist_ok=True)
    if list(assets.iterdir()):
        raise ValueError('release-assets must be empty before preparing a release')
    files = [main] + ([sources] if sources.exists() else [])
    plan['assets'] = {}
    for path in files:
        shutil.copyfile(path, assets / path.name)
        plan['assets'][path.name] = {'size': path.stat().st_size,
            'digest': 'sha256:' + hashlib.sha256(path.read_bytes()).hexdigest()}
    save(state, plan)
    print('Verified JAR version and prepared:', ', '.join(plan['assets']))


def remote_tag(tag):
    refs = git('ls-remote', 'origin', f'refs/tags/{tag}').splitlines()
    return refs[0].split()[0] if refs else None


def check_remote(plan):
    if remote_tag(plan['tag']) != plan['tag_object']:
        raise ValueError('Remote release tag changed or is missing')


def record(state, plan):
    if not plan.get('assets'):
        raise ValueError('Verify the built release assets before recording a version')
    require_unpublished(github('/releases/tags/' + plan['tag'], missing_ok=True))
    if plan['operation'] != 'retry':
        remote_head = git('ls-remote', 'origin', 'refs/heads/main').split()[0]
        if remote_head != plan['base_sha']:
            raise ValueError('main advanced during the build; nothing will be pushed, start a new run')
        if remote_tag(plan['tag']) is not None:
            raise ValueError('Target tag already exists; choose a new version')
        if git('diff', '--name-only', 'HEAD').splitlines() != ['version.txt']:
            raise ValueError('Only version.txt may change during the release build')
        git('config', 'user.name', 'github-actions[bot]')
        git('config', 'user.email', '41898282+github-actions[bot]@users.noreply.github.com')
        git('add', '--', 'version.txt')
        git('commit', '-m', f'发版：{plan["version"]}（GitHub Actions 自动升版）')
        git('tag', '-a', plan['tag'], '-m', f'FutaGTNH {plan["version"]}')
        git('push', '--atomic', 'origin', 'HEAD:refs/heads/main', f'refs/tags/{plan["tag"]}')
        plan['commit'] = git('rev-parse', 'HEAD')
        plan['tag_object'] = git('rev-parse', f'refs/tags/{plan["tag"]}')
        save(state, plan)
    check_remote(plan)
    print('Release commit/tag confirmed:', plan['commit'], plan['tag'])


def draft(state, plan):
    check_remote(plan)
    existing = github('/releases/tags/' + plan['tag'], missing_ok=True)
    require_unpublished(existing)
    note_path = Path('docs') / f'{plan["version"]}-改动与交付说明.md'
    note = note_path.read_text(encoding='utf-8') if note_path.exists() else ''
    generated = github('/releases/generate-notes', {'tag_name': plan['tag'],
        'target_commitish': plan['commit']})['body']
    body = {'tag_name': plan['tag'], 'target_commitish': plan['commit'],
            'name': f'FutaGTNH {plan["version"]}', 'body': (note + '\n\n' + generated).strip(),
            'draft': True, 'prerelease': False}
    release = (github(f'/releases/{existing["id"]}', body, 'PATCH') if existing
               else github('/releases', body))
    plan['release_id'] = release['id']
    save(state, plan)
    print('Draft prepared; not visible to startup updaters until all assets are verified')


def validate_assets(expected, uploaded):
    for name, metadata in expected.items():
        matches = [item for item in uploaded if item['name'] == name and item.get('state') == 'uploaded']
        if len(matches) != 1 or matches[0]['size'] != metadata['size']:
            raise ValueError('Missing, duplicate or incomplete release asset: ' + name)
        if matches[0].get('digest') and matches[0]['digest'].lower() != metadata['digest']:
            raise ValueError('GitHub asset SHA-256 differs from the verified build: ' + name)
    if {item['name'] for item in uploaded} != set(expected):
        raise ValueError('Draft contains unexpected assets; inspect it before retrying')


def publish(state, plan):
    check_remote(plan)
    release = github(f'/releases/{plan["release_id"]}')
    require_unpublished(release)
    if release['tag_name'] != plan['tag']:
        raise ValueError('Draft release tag differs from the build')
    validate_assets(plan['assets'], release['assets'])
    for other in releases():
        tag = other['tag_name']
        if not other['draft'] and not other['prerelease'] and tag.startswith('v'):
            try:
                other_version = version(tag[1:])
            except ValueError:
                continue
            if other_version >= version(plan['version']):
                raise ValueError('A same/newer version is already published; refusing to publish this draft')
    released = github(f'/releases/{plan["release_id"]}', {'draft': False, 'make_latest': 'true'}, 'PATCH')
    if released['draft']:
        raise ValueError('GitHub did not publish the release')
    print('Published:', released['html_url'])
    if os.environ.get('GITHUB_STEP_SUMMARY'):
        with open(os.environ['GITHUB_STEP_SUMMARY'], 'a', encoding='utf-8') as stream:
            stream.write(f'已发布 [{plan["tag"]}]({released["html_url"]})，版本自动写回 main。\n')


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('command', choices=['prepare', 'verify', 'record', 'draft', 'publish'])
    parser.add_argument('--state', required=True, type=Path)
    parser.add_argument('--bump', choices=['patch', 'minor', 'major', 'retry'], default='patch')
    args = parser.parse_args()
    if args.command == 'prepare':
        prepare(args.state, args.bump)
    else:
        plan = json.loads(args.state.read_text(encoding='utf-8'))
        globals()[args.command](args.state, plan)


if __name__ == '__main__':
    try:
        main()
    except (ValueError, RuntimeError, subprocess.CalledProcessError, urllib.error.URLError) as error:
        # Do not emit git's credential-bearing stderr or raw request headers.
        message = str(error) if not isinstance(error, subprocess.CalledProcessError) else 'Git command failed; no force push is attempted'
        print('::error::' + message, file=sys.stderr)
        sys.exit(1)
