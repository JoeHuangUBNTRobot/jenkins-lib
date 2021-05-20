#!/usr/bin/env python3

import re
import argparse
import os
import hashlib
from pathlib import PosixPath


class PkgMkInfo:
    def __init__(self, dist, name, version, arch):
        self.name = name
        self.version = version
        self.arch = arch
        self.deb_name = ''
        self.dist = dist
        self.md5sum = ''

    def add_md5sum(self, deb_file):
        self.deb_name = deb_file.name

        m = hashlib.md5()
        m.update(deb_file.read_bytes())

        self.md5sum_list = m.hexdigest()

    def generate_makefile(self, makefile, base_url):
        variable_name_prefix = self.name.upper().replace('-', '_')
        variable_pkg_name = '{}_PKG_NAME'.format(variable_name_prefix)
        variable_pkg_version = '{}_VERSION'.format(variable_name_prefix)
        variable_base_url = '{}_BASEURL'.format(variable_name_prefix)
        variable_md5 = '{}_MD5'.format(variable_name_prefix)

        arch = '$(_arch)'
        if self.arch == 'all':
            arch = 'all'

        makefile.write('# {}\n'.format('/'.join(
            [base_url, self.dist, self.arch, self.deb_name])))
        makefile.write('{}:={}\n'.format(variable_pkg_name, self.name))
        makefile.write('{}:={}\n\n'.format(variable_pkg_version, self.version))

        base_url = '/'.join([base_url, '$(_distro)', arch])
        makefile.write('{}:={}\n'.format(variable_md5, self.md5sum_list))
        makefile.write('{}:={}\n\n'.format(variable_base_url, base_url))

        makefile.write('PKG_FILE:=$({})_$({})_{}.deb\n'.format(
            variable_pkg_name, variable_pkg_version, arch))
        makefile.write('PKG_FILE_MD5SUM:=$({})\n'.format(variable_md5))
        makefile.write('PKG_BASEURL:=$({})\n'.format(variable_base_url))


pkg_info_list = dict()


def delete_file(path, dry):
    print('Unlink {}'.format(path))
    if not dry:
        path.unlink()


def filter_files_not_handled(path, args):
    if path.is_dir():
        return True

    if 'make.log' == path.name:
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


def compare_version_less(new_version_str, latest_mkfile):
    if not latest_mkfile.is_file():
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

    for (new, old) in zip(new_version, old_version):
        if new < old:
            return True

    # new_version is greater than or equals to old_version
    return False


def arrange_directory(args):
    file_list = [f for f in args.directory.rglob('*')]
    for f in file_list:
        if filter_files_not_handled(f, args):
            continue

        tokens = re.split(r'[._]', str(f.name))
        pkg_name = tokens[0]
        pkg_verion = '.'.join(tokens[1:-2])
        pkg_arch = tokens[-2]

        dst_path = args.directory / args.dist / pkg_arch

        if f.suffix == '.deb':
            if pkg_name not in pkg_info_list:
                pkg_info_list[pkg_name] = PkgMkInfo(args.dist, pkg_name,
                                                    pkg_verion, pkg_arch)
            pkg_info_list[pkg_name].add_md5sum(f)

        print('Move {} to {}'.format(f, dst_path))
        if not args.dry_run:
            dst_path.mkdir(parents=True, exist_ok=True)
            f.rename(dst_path / f.name)

    remove_empty_dir(args.directory, args.dry_run)
    if args.output_dir is not None:
        makefile_dir = args.output_dir
    else:
        makefile_dir = args.directory / '_makefile'
    makefile_dir.mkdir(parents=True, exist_ok=True)
    for p in pkg_info_list.values():
        if args.compare_dir is not None and compare_version_less(
                p.version, (args.compare_dir / p.name).with_suffix('.mk')):
            print('{}\'s version {} is less than latest. skip ...'.format(
                p.name, p.version))
            continue
        print('Generate {}\'s makefile with version {}'.format(
            p.name, p.version))
        with (makefile_dir / p.name).with_suffix('.mk').open('w') as f:
            p.generate_makefile(f, args.pkg_url_base)


def parse_args():
    parser = argparse.ArgumentParser(
        description='Put pkgdeb output dir to artifact dir format')

    parser.add_argument('--dist',
                        '-d',
                        default='stretch',
                        type=str,
                        help='Distribution of debian')

    parser.add_argument('--dry_run', '-n', action='store_true')
    parser.add_argument('--output_dir', '-o', type=PosixPath, default=None)
    parser.add_argument('--compare_dir', '-c', type=PosixPath, default=None)
    parser.add_argument('--pkg_url_base', '-u', default='')
    parser.add_argument('directory', type=PosixPath)

    return parser.parse_args()


if __name__ == '__main__':
    arrange_directory(parse_args())
