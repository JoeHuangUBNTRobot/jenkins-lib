#!/usr/bin/env python3

import subprocess
import json
import re
import argparse
import os
import hashlib
from pathlib import PosixPath

# pattern > repace group index
pkg_series_pattern = [
    (re.compile(r'(ustd).*'), 1),
    (re.compile(r'(devreg).*'), 1),
    (re.compile(r'.*?(base-files)'), 1),
    (re.compile(r'(.*?)(-dev|-dbgsym)'), 1),
]


class PkgMkInfo:
    def __init__(self, series, name, version, arch, multi_var_name, dist):
        self.name = name
        self.arch = arch
        self.series = series
        self.dist_info = dict()
        self.add_dist_info(version, multi_var_name, dist)

    def add_dist_info(self, version, multi_var_name, dist):
        if dist not in self.dist_info:
            self.dist_info[dist] = {
                'version': version,
                'deb_name': '',
                'md5sum_list': dict(),
                'multi_var_name': multi_var_name
            }

    def add_md5sum(self, multi_name, deb_file, dist):
        self.dist_info[dist]['deb_name'] = deb_file.name
        m = hashlib.md5()
        m.update(deb_file.read_bytes())

        self.dist_info[dist]['md5sum_list'][multi_name] = m.hexdigest()

    def generate_makefile(self, makefile, base_url):
        variable_name_prefix = self.name.upper().replace('-', '_')
        variable_pkg_name = '{}_PKG_NAME'.format(variable_name_prefix)
        variable_pkg_version = '$(value {}_$(_distro)_VERSION)'.format(variable_name_prefix)
        variable_base_url = '{}_BASEURL'.format(variable_name_prefix)

        arch = '$(_arch)'
        if self.arch == 'all':
            arch = 'all'

        for dist, info in self.dist_info.items():
            info['md5sum_list'] = sorted(info['md5sum_list'].items())

        # Only show one download address on top of file
        makefile.write('# {}\n\n'.format('/'.join([
            base_url,
            self.series,
            info['md5sum_list'][0][0],
            info['deb_name'],
        ])))

        makefile.write('{}:={}\n'.format(variable_pkg_name, self.name))

        for dist, info in self.dist_info.items():
            makefile.write('{}_{}_VERSION:={}\n'.format(
                variable_name_prefix, dist, info['version']))
            for multi_name, md5sum in info['md5sum_list']:
                makefile.write('{}_{}_MD5:={}\n'.format(
                    variable_name_prefix, multi_name.replace('/', '_'),
                    md5sum))

        base_url = '/'.join([
            base_url,
            self.series,
            '$(_distro)',
        ])
        if info['multi_var_name']:
            base_url += '/$({})'.format(info['multi_var_name'])

        makefile.write('{}:={}\n\n'.format(variable_base_url, base_url))
        makefile.write('PKG_FILE:=$({})_{}_{}.deb\n'.format(
            variable_pkg_name, variable_pkg_version, arch))

        if info['multi_var_name']:
            makefile.write(
                'PKG_FILE_MD5SUM:=$(value {}_$(_distro)_$({})_MD5)\n'.format(
                    variable_name_prefix, info['multi_var_name']))
        else:
            makefile.write(
                'PKG_FILE_MD5SUM:=$(value {}_$(_distro)_MD5)\n'.format(
                    variable_name_prefix))

        makefile.write('PKG_BASEURL:=$({})\n'.format(variable_base_url))


pkg_info_list = dict()


def delete_file(path, dry):
    print('Unlink {}'.format(path))
    if not dry:
        path.unlink()


def filter_files_not_handled(path, args):
    if path.is_dir():
        return True

    if re.match(r'^(\.mk)$', path.suffix):
        # Don't handle *.mk files, just let it pass
        return True

    if not re.match(r'^(\.deb|\.build.*|\.changes)$', path.suffix):
        delete_file(path, args.dry_run)
        return True

    if 'build-deps' in path.name or path.name.startswith('.mark'):
        delete_file(path, args.dry_run)
        return True

    return False


def remove_empty_dir(path, dry):
    has_empty = True
    while has_empty:
        has_empty = False
        for d in path.rglob('*'):
            if d.is_dir() and len(os.listdir(str(d))) == 0:
                print('Remove empty dir {}'.format(d))
                if not dry:
                    has_empty = True
                    d.rmdir()


# Remove suffix -<token> and let other packages with same prefix as same package series
# e.g. (libubnt, libubnt-dev) (ustd, ustd-ro, ustd-naked)
def get_pkg_series(pkg_name):
    for pattern, group in pkg_series_pattern:
        m = pattern.match(pkg_name)
        if m:
            return m.group(group)
    return pkg_name


multi_var_name_map = None


def get_pkg_list():
    result = subprocess.check_output(['make', 'pkg-list'])
    return str(result, encoding='utf8')


def parse_pkg_multi_var(pkg_list):
    output_list = dict()
    for pkg in json.loads(pkg_list):
        try:
            output_list[pkg['n']] = pkg.get('v', None)
        except KeyError:
            continue

    return output_list


def get_multi_var_name(pkg_name):
    global multi_var_name_map
    if multi_var_name_map is None:
        try:
            multi_var_name_map = parse_pkg_multi_var(get_pkg_list())
        except:
            return None
    return multi_var_name_map.get(pkg_name, None)


def deb_pkg_name_parser(name):
    tokens = re.split(r'[._]', name)
    pkg_name = tokens[0]
    pkg_version = '.'.join(tokens[1:-1])
    pkg_arch = tokens[-1]
    pkg_series = get_pkg_series(pkg_name)
    return pkg_name, pkg_version, pkg_arch, pkg_series


def arrange_directory(args):
    file_list = [f for f in args.directory.rglob('*')]
    for f in file_list:
        if filter_files_not_handled(f, args):
            continue

        pkg_name, pkg_version, pkg_arch, pkg_series = deb_pkg_name_parser(
            str(f.stem))

        relpath = f.relative_to(args.directory)
        multi_name = str(relpath.parent)
        if '/' in multi_name:
            dist = multi_name.split('/', 1)[0]
        else:
            dist = multi_name
        multi_var_name = get_multi_var_name(pkg_series)

        dst_path = args.directory / pkg_series / relpath

        if f.suffix == '.deb':
            if pkg_name not in pkg_info_list:
                pkg_info_list[pkg_name] = PkgMkInfo(pkg_series, pkg_name,
                                                    pkg_version, pkg_arch,
                                                    multi_var_name, dist)
            else:
                pkg_info_list[pkg_name].add_dist_info(pkg_version,
                                                      multi_var_name, dist)

            pkg_info_list[pkg_name].add_md5sum(multi_name, f, dist)

        print('Move {} to {}'.format(f, dst_path))
        if not args.dry_run:
            dst_path.parent.mkdir(parents=True, exist_ok=True)
            f.rename(dst_path)

    remove_empty_dir(args.directory, args.dry_run)


# a.k.a not greater or equals to
def compare_version_less(new_version_str, latest_mkfile):
    if not latest_mkfile.is_file():
        print('{} not exists'.format(latest_mkfile))
        return False

    old_version = [0, 0, 0]
    with latest_mkfile.open() as f:
        m = re.search(
            r'.*?VERSION:=(\d+)\.(\d+)\.(\d+)[-~](\d+)\+g(\w+)(M?)\n',
            f.read())
        if m is not None:
            try:
                old_version = [int(v) for v in m.group(1, 2, 3)]
            except:
                pass

    new_version = [9999, 9999, 9999]
    m = re.match(r'(\d+)\.(\d+)\.(\d+)[-~](\d+)\+g(\w+)(M?)', new_version_str)
    if m is not None:
        try:
            new_version = [int(v) for v in m.group(1, 2, 3)]
        except:
            pass

    print('Compare version between {} and {}'.format(new_version, old_version))
    for (new, old) in zip(new_version, old_version):
        if new != old:
            return new < old

    # new_version equals to old_version
    return False


def generate_makefile(args):
    if args.output_dir is not None:
        makefile_dir = args.output_dir
    else:
        makefile_dir = args.directory / '_makefile'
    makefile_dir.mkdir(parents=True, exist_ok=True)

    for p in pkg_info_list.values():
        # Check for tag build packages
        version = list(p.dist_info.values())[0]['version']
        if args.compare_dir is not None and compare_version_less(
                version, (args.compare_dir / p.name).with_suffix('.mk')):
            print('{}\'s version {} is less than latest. skip ...'.format(
                p.name, version))
            continue
        print('Generate {}\'s makefile with version {}'.format(
            p.name, version))
        with (makefile_dir / p.name).with_suffix('.mk').open('w') as f:
            p.generate_makefile(f, args.pkg_url_base)


def check_dir_and_resolve(path):
    p = PosixPath(path)
    if not p.is_dir():
        p.mkdir(parents=True, exist_ok=True)
    return p.resolve()


def parse_args():
    parser = argparse.ArgumentParser(
        description='Put debfactory output dir to artifact dir format')

    parser.add_argument('--dry_run', '-n', action='store_true')
    parser.add_argument('--output_dir',
                        '-o',
                        type=check_dir_and_resolve,
                        default=None)
    parser.add_argument('--compare_dir',
                        '-c',
                        type=check_dir_and_resolve,
                        default=None)
    parser.add_argument('--pkg_url_base', '-u', default='')
    parser.add_argument('directory', type=check_dir_and_resolve)

    return parser.parse_args()


if __name__ == '__main__':
    args = parse_args()
    arrange_directory(args)
    generate_makefile(args)
