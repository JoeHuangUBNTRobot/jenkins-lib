#!/usr/bin/env python3

import re
import argparse
import os
import hashlib
from pathlib import PurePosixPath, PosixPath

spcial_pkg_series = {
    'ustd': 'ustd',
    'ustd-ro': 'ustd',
    'ustd-naked': 'ustd',
    'ustd-stub': 'ustd',
    'devreg-mt7622': 'devreg',
    'devreg-al324': 'devreg',
}


class PkgMkInfo:
    def __init__(self, dist, series, name, version, arch):
        self.name = name
        self.version = version
        self.arch = arch
        self.deb_name = ''
        self.series = series
        self.dist = dist
        self.md5sum_list = dict()

    def add_md5sum(self, kernel_version, deb_file):
        self.deb_name = deb_file.name
        m = hashlib.md5()
        m.update(deb_file.read_bytes())

        if len(kernel_version) != 0:
            self.md5sum_list[kernel_version] = m.hexdigest()
        else:
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

        if isinstance(self.md5sum_list, str):
            makefile.write('# {}\n'.format('/'.join([
                base_url, self.series, self.dist, self.arch, self.version,
                self.deb_name
            ])))
        else:
            makefile.write('# {}\n'.format('/'.join([
                base_url, self.series, self.dist, self.arch, self.version,
                next(iter(self.md5sum_list)), self.deb_name
            ])))
        makefile.write('{}:={}\n'.format(variable_pkg_name, self.name))
        makefile.write('{}:={}\n\n'.format(variable_pkg_version, self.version))

        if isinstance(self.md5sum_list, str):
            base_url = '/'.join([
                base_url, self.series, '$(_distro)', arch,
                '$({})'.format(variable_pkg_version)
            ])
            makefile.write('{}:={}\n'.format(variable_md5, self.md5sum_list))
        else:
            base_url = '/'.join([
                base_url, self.series, '$(_distro)', arch,
                '$({})'.format(variable_pkg_version), '$(KVER_DIR)'
            ])
            for kver in self.md5sum_list:
                makefile.write('{}_{}_MD5:={}\n'.format(
                    variable_name_prefix, kver, self.md5sum_list[kver]))
            makefile.write(
                'KVER_DIR:=$(BUILD_KERNEL_VERSION)$(BUILD_KERNEL_LOCALVERSION)\n'
            )

        makefile.write('{}:={}\n\n'.format(variable_base_url, base_url))
        makefile.write('PKG_FILE:=$({})_$({})_{}.deb\n'.format(
            variable_pkg_name, variable_pkg_version, arch))
        if isinstance(self.md5sum_list, str):
            makefile.write('PKG_FILE_MD5SUM:=$({})\n'.format(variable_md5))
        else:
            makefile.write(
                'PKG_FILE_MD5SUM:=$(value {}_$(KVER_DIR)_MD5)\n'.format(
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
    return spcial_pkg_series.get(pkg_name,
                                 re.sub(r'(-dev|-dbgsym)', '', pkg_name))


def arrange_directory(args):
    file_list = [f for f in args.directory.rglob('*')]
    for f in file_list:
        if filter_files_not_handled(f, args):
            continue

        tokens = re.split(r'[._]', str(f.name))
        pkg_name = tokens[0]
        pkg_verion = '.'.join(tokens[1:-2])
        pkg_arch = tokens[-2]
        pkg_series = get_pkg_series(pkg_name)
        pkg_ker_ver = ''

        # Check if there is kernel version in path
        if f.parent != args.directory:
            pkg_ker_ver = str(f.parent.name)
        dst_path = args.directory / pkg_series / args.dist / pkg_arch / pkg_verion / pkg_ker_ver

        if f.suffix == '.deb':
            if pkg_name not in pkg_info_list:
                pkg_info_list[pkg_name] = PkgMkInfo(args.dist, pkg_series,
                                                    pkg_name, pkg_verion,
                                                    pkg_arch)
            pkg_info_list[pkg_name].add_md5sum(pkg_ker_ver, f)

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
    arrange_directory(parse_args())
