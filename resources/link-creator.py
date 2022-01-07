#!/usr/bin/env python3

import os
import argparse
from pathlib import Path
import importlib

pkg_arrange = importlib.import_module('pkg-arrange')


def prepare_pkgs_link_by_version(args):
    trg = args.trg
    for proj in args.src.glob('*'):
        deb_list = sorted(proj.rglob('*.deb'))
        if len(deb_list) == 0:
            continue
        try:
            _, pkg_verion, _, pkg_series = pkg_arrange.deb_pkg_name_parser(str(deb_list[0].stem))
            trg_pkg_series_dir = Path(f"{str(trg.absolute())}/{proj.name}")
            symlink = Path(f"{trg_pkg_series_dir.absolute()}/{pkg_verion}")
            relpath = os.path.relpath(proj.absolute(), trg_pkg_series_dir.absolute())
            print(f'prepare link form {symlink} to {relpath}')
            if proj.name != pkg_series:
                print(f'proj {proj.name} is not equal to pkg_series {pkg_series}, there may be a problem')
            if not args.dry_run:
                if trg_pkg_series_dir.exists() and trg_pkg_series_dir.is_dir():
                    trg_pkg_series_dir.touch(exist_ok=True)
                else:
                    trg_pkg_series_dir.mkdir()
                if symlink.exists() or symlink.is_symlink():
                    symlink.unlink()
                symlink.symlink_to(relpath)
        except Exception as e:
            print(e)


def parse_args():
    parser = argparse.ArgumentParser(description='Link artifact dir format to pkgs dir')
    parser.add_argument('--src', '-s', required=True, type=Path, help='source pkgs folder')
    parser.add_argument('--trg', '-t', required=True, type=Path, help='target pkgs folder')
    parser.add_argument('--dry_run', '-n', action='store_true')
    return parser.parse_args()


if __name__ == '__main__':
    args = parse_args()
    prepare_pkgs_link_by_version(args)
