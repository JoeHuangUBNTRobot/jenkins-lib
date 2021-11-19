#!/usr/bin/env python3

import subprocess
import json
import re
import argparse
import os
import hashlib
from pathlib import PosixPath

spcial_pkg_series = {
    'ustd': 'ustd',
    'ustd-ro': 'ustd',
    'ustd-naked': 'ustd',
    'ustd-stub': 'ustd',
    'devreg-mt7622': 'devreg',
    'devreg-al324': 'devreg',
}


class PkgMkInfo:
    def __init__(self, dist, series, name, version, arch, multi_var_name):
        self.name = name
        self.version = version
        self.arch = arch
        self.deb_name = ''
        self.series = series
        self.dist = dist  # Useless remove this in the future
        self.md5sum_list = dict()
        self.multi_var_name = multi_var_name

    def add_md5sum(self, multi_name, deb_file):
        self.deb_name = deb_file.name
        m = hashlib.md5()
        m.update(deb_file.read_bytes())

        self.md5sum_list[multi_name] = m.hexdigest()

    def generate_makefile(self, makefile, base_url):
        variable_name_prefix = self.name.upper().replace('-', '_')
        variable_pkg_name = '{}_PKG_NAME'.format(variable_name_prefix)
        variable_pkg_version = '{}_VERSION'.format(variable_name_prefix)
        variable_base_url = '{}_BASEURL'.format(variable_name_prefix)

        arch = '$(_arch)'
        if self.arch == 'all':
            arch = 'all'

        sorted_md5_list = sorted(self.md5sum_list.items())

        makefile.write('# {}\n\n'.format('/'.join([
            base_url,
            self.series,
            sorted_md5_list[0][0],
            self.deb_name,
        ])))
        makefile.write('{}:={}\n'.format(variable_pkg_name, self.name))
        makefile.write('{}:={}\n\n'.format(variable_pkg_version, self.version))

        base_url = '/'.join([
            base_url,
            self.series,
            '$(_distro)',
            '$({})',
        ]).format(self.multi_var_name)
        for multi_name, md5sum in sorted_md5_list:
            makefile.write('{}_{}_MD5:={}\n'.format(
                variable_name_prefix, multi_name.replace('/', '_'), md5sum))

        makefile.write('{}:={}\n\n'.format(variable_base_url, base_url))
        makefile.write('PKG_FILE:=$({})_$({})_{}.deb\n'.format(
            variable_pkg_name, variable_pkg_version, arch))

        if self.multi_var_name:
            makefile.write(
                'PKG_FILE_MD5SUM:=$(value {}_$(_distro)_$({})_MD5)\n'.format(
                    variable_name_prefix, self.multi_var_name))
        else:
            makefile.write(
                'PKG_FILE_MD5SUM:=$(value {}_$(_distro)_MD5)\n'.format(
                    variable_name_prefix, self.multi_var_name))

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
    return spcial_pkg_series.get(pkg_name,
                                 re.sub(r'(-dev|-dbgsym)', '', pkg_name))


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
        multi_var_name_map = parse_pkg_multi_var(get_pkg_list())
    return multi_var_name_map.get(pkg_name, None)


def deb_pkg_name_parser(name):
    tokens = re.split(r'[._]', name)
    pkg_name = tokens[0]
    pkg_verion = '.'.join(tokens[1:-1])
    pkg_arch = tokens[-1]
    pkg_series = get_pkg_series(pkg_name)
    return pkg_name, pkg_verion, pkg_arch, pkg_series


def arrange_directory(args):
    file_list = [f for f in args.directory.rglob('*')]
    for f in file_list:
        if filter_files_not_handled(f, args):
            continue

        pkg_name, pkg_verion, pkg_arch, pkg_series = deb_pkg_name_parser(str(f.stem))

        relpath = f.relative_to(args.directory)
        multi_name = str(relpath.parent)
        multi_var_name = get_multi_var_name(pkg_series)

        dst_path = args.directory / pkg_series / relpath

        if f.suffix == '.deb':
            if pkg_name not in pkg_info_list:
                pkg_info_list[pkg_name] = PkgMkInfo(args.dist, pkg_series,
                                                    pkg_name, pkg_verion,
                                                    pkg_arch, multi_var_name)
            pkg_info_list[pkg_name].add_md5sum(multi_name, f)

        print('Move {} to {}'.format(f, dst_path))
        if not args.dry_run:
            dst_path.parent.mkdir(parents=True, exist_ok=True)
            f.rename(dst_path)

    remove_empty_dir(args.directory, args.dry_run)


def generate_makefile(args):
    if args.output_dir is not None:
        makefile_dir = args.output_dir
    else:
        makefile_dir = args.directory / '_makefile'
    makefile_dir.mkdir(parents=True, exist_ok=True)
    for p in pkg_info_list.values():
        with (makefile_dir / p.name).with_suffix('.mk').open('w') as f:
            p.generate_makefile(f, args.pkg_url_base)


def parse_args():
    parser = argparse.ArgumentParser(
        description='Put debfactory output dir to artifact dir format')

    parser.add_argument('--dist',
                        '-d',
                        default='stretch',
                        type=str,
                        help='Distribution of debian')

    parser.add_argument('--dry_run', '-n', action='store_true')
    parser.add_argument('--output_dir', '-o', type=PosixPath, default=None)
    parser.add_argument('--pkg_url_base', '-u', default='')
    parser.add_argument('directory', type=PosixPath)

    return parser.parse_args()


if __name__ == '__main__':
    args = parse_args()
    arrange_directory(args)
    generate_makefile(args)
