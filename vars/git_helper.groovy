def verify_is_atag(ref) {
    try {
        sh "git describe --match ${ref} ${ref}"
    } catch (all) {
        error "Trying to build ${ref} as ATAG, but could not verify it exists"
    }
}
def sha(ref='HEAD') {
    return sh_output("git rev-parse ${ref}^{commit}")
}

def short_sha(ref='HEAD') {
    return sh_output("git rev-parse --short ${ref}^{commit}")
}

def current_branch() {
    return sh_output('git rev-parse --abbrev-ref HEAD')
}

def first_commit() {
    return sh_output('git rev-list --max-parents=0 HEAD')
}

def rev(ref='HEAD', base_tag='BEGIN_BUILD') {
    def now = rev_list(ref)
    def begin = 0
    return "${now - begin}"
}

def rev_list(ref='HEAD') {
    return sh_output("git rev-list --count ${ref}") as Integer
}

def ref_info(ref) {
    def output = sh_output("git show-ref ${ref} || true")
    def info = [
        found: false,
        is_tag: false,
        is_branch: false,
        is_sha: false,
        ]
    if (output != '') {
        def ref_map = split_lines(output).collectEntries {
            def tokens = it.split()
            return [ "${tokens[1]}": tokens[0] ]
        }

        for (ref_type in ['tags', 'remotes', 'heads']) {
            for (item in ref_map) {
                def full_ref = item.key
                def full_sha = item.value
                def matcher = safe_regex(full_ref, ~"^refs/${ref_type}/(.*)")
                if (matcher) {
                    info.ref = matcher[0][1]
                    info.found = true
                    info.sha = full_sha
                    info.is_tag = ref_type == 'tags'
                    info.is_branch = ref_type in ['remotes', 'heads']
                    return info
                }
            }
        }
    }
    def sha_test = sh_output("git cat-file -t ${ref} || true")
    if (sha_test == 'commit') {
        info.found = true
        info.is_sha = true
        info.sha = sha(ref)
    }

    return info
}

def split_url(url) {
    def user = 'git'
    def site
    def url_parts = url.tokenize('/:')
    def url_idx = 0
    if (url_parts[url_idx].startsWith('http')) {
        url_idx++
        site = url_parts[url_idx++]
        if (url_parts[url_idx] == '10080') {
            url_idx++
        }
    } else {
        if (url_parts[url_idx].contains('@')) {
            user = url_parts[url_idx].replaceAll('@.*', '')
        }
        site = url_parts[url_idx++].replaceAll("^${user}@", '')
    }
    def owner = url_parts[url_idx++]
    def git_suffix = url_parts[url_idx].endsWith('.git')
    def repository = url_parts[url_idx++] - '.git'
    if (url_parts.size() != url_idx) {
        error "Problem parsing URL ${url} parts ${url_parts}"
    }
    return [user:user,
        site:site,
        owner:owner,
        repository:repository,
        git_suffix:git_suffix,
    ]
}

def get_file_changes(baseRev, curRev='') {
    def fileDiffs = sh_output("git diff --name-only $baseRev $curRev")
    return fileDiffs
}
