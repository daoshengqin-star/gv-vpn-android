#!/usr/bin/env python3
"""Build an already prepared/scanned source export; never restore removed files."""
from pathlib import Path
import os, shutil, subprocess, tempfile

source = Path(__file__).resolve().parents[1]
gradle = os.environ.get('GV_GRADLE', 'gradle')
version = subprocess.check_output([gradle, '--version'], text=True)
if 'Gradle 8.13\n' not in version:
    raise SystemExit('This release requires Gradle 8.13')

# Both upstream and the F-Droid recipe use this source-export profile. The
# caller records the full public commit; .git is not part of the source export.
# In particular, do not use git archive/reset: that would restore files removed
# by F-Droid preparation or scanning. No compiler outputs are copied as inputs.
with tempfile.TemporaryDirectory(prefix='gv-prepared-source-') as temp:
    target = Path(temp) / 'project'
    shutil.copytree(source, target, ignore=shutil.ignore_patterns(
        '.git', '.gradle', 'build', '.build-tools', '.gomobile-tmp',
        'fdroid-output', 'local.properties', '*.aar', '*-sources.jar'))
    subprocess.run(['sh', 'fdroid/native-from-source.sh'], cwd=target, check=True)
    subprocess.run([gradle, '--no-daemon', '--max-workers=4',
                    '-PgvFdroidUnsigned=true', ':app:assembleRelease'],
                   cwd=target, check=True)
    output = source / 'fdroid-output'
    output.mkdir(exist_ok=True)
    shutil.copyfile(target / 'app/build/outputs/apk/release/app-universal-release-unsigned.apk',
                    output / 'app-universal-release-unsigned.apk')
