def generate_buildinfo(Map git_args) {
    println git_args
    verify_required_params('generate_buildinfo', git_args, ['repository', 'is_pr', 'is_tag', 'ref', 'rev_num'])
    def output = [:]
    println git_args
    def ref_path = [] + git_args.repository
    def dir_name = 'unknown'
    def pkgs_path = [] + git_args.repository
    def ref = git_args.ref
    def ref_sha = git_helper.sha(ref)

    if (git_args.is_tag) {
        dir_name = 'tags'
        ref_path = ref_path + dir_name + ref
    } else if (git_args.is_pr) {
        dir_name = 'prs'
        ref_path = ref_path + dir_name + "PR-${env.CHANGE_ID}"
    } else {
        def branch = ref.replaceAll('^origin/', '')
        dir_name = 'heads'
        ref_path = ref_path + dir_name + branch
    }
    pkgs_path = pkgs_path + 'pkgs'

    if (git_args.is_temp_build) {
        String temp_dir_name = git_args.temp_dir_name ?: 'tmp'
        ref_path += temp_dir_name
        pkgs_path += temp_dir_name
    }

    println "PATH: ${ref_path.join('/')}"

    def output_dir = [BUILD_NUMBER, git_args.rev_num, git_helper.short_sha(ref_sha)].join('-')
    output.path = ref_path + output_dir
    
    output.latest_path = ref_path + 'latest'
    output.pkgs_path = pkgs_path
    output.ref_path = ref_path
    output.job_path = output_dir
    output.dir_name = dir_name
    return output
}

def get_temp_dir() {
    return 'build-temp/'
}
def recursive_touch(base_path, latest_path) {
    sh("a=$latest_path; while [ \"\$a\" != \"$base_path\" ]; do touch -m \$a; a=\$(dirname \$a); done")
}

def get_nasdir() {
    return "$HOME/builder"
}

def get_nasdomain() {
    return 'http://tpe-judo.rad.ubnt.com/build'
}

def move(src_path, dst_path) {
    def nasinfo = [:]
    def nasdomain = get_nasdomain()
    def nasdir = get_nasdir()
    def notmounted = sh_output.status_code("mountpoint -q $nasdir")
    if (!notmounted) {
        src_path = "$nasdir/$src_path"
        dst_path = "$nasdir/$dst_path"
        def dst_token = dst_path.tokenize('/')
        dst_token.pop()
        def latest_path = '/'+ dst_token.join('/') + '/latest'
        println "Move from $src_path/* to $dst_path"
        sh "mkdir -p $dst_path"
        sh "mv -f $src_path/* $dst_path"
        println "latest_path: $latest_path" 
        sh "ln -srfT $dst_path $latest_path"
        if (dst_path.contains("debbox")) {
            def dst_basename = dst_path.tokenize("/").pop()
            try {
                def output_path = sh_output("realpath ${dst_path}/*/*")
                output_path.split('\n').each {
                    artifact_name = it.tokenize('/').pop()
                    artifact_url = it.replace(nasdir, nasdomain)
                    nasinfo[artifact_url] = artifact_name
                }
            } catch (Exception e) {
                    //build failed: do nothing
            }
        }
    }
    return nasinfo
}

def upload(src_path, dst_path, latest_path, link_subdir = false, pkgs_path="") {
    def nasinfo = [:]
    def nasdomain = get_nasdomain()
    def nasdir = get_nasdir()
    def notmounted = sh_output.status_code("mountpoint -q $nasdir")
    if (!notmounted) {
        def nas_path = "$nasdir/$dst_path"
        latest_path = "$nasdir/$latest_path"
        println "upload from $src_path to $nas_path"
        sh "mkdir -p $nas_path"
        sh "cp -rp $src_path $nas_path"
        // if (nas_path.contains('debbox')) {
        //     def src_basename = src_path.tokenize('/').pop()
        //     try {
        //         def output_path = sh_output("realpath ${nas_path}/${src_basename}/*")
        //         output_path.split('\n').each {
        //             artifact_name = it.tokenize('/').pop()
        //             artifact_url = it.replace(nasdir, nasdomain)
        //             nasinfo[artifact_name] = artifact_url
        //         }
        //         println "nasinfo: $nasinfo"
        //     }
        //     catch (Exception e) {
        //     // build failed: do nothing
        //     }
        // }

        lock('nas_upload') {
            if (link_subdir) {
                sh "mkdir -p $latest_path"
                sh "for subdir in $nas_path/*; do ln -srf -t $latest_path \$subdir; done"
                if (pkgs_path) {
                    try {
                        pkgs_path = "$nasdir/$pkgs_path"
                        writeFile file:'pkg-arrange.py', text:libraryResource("pkg-arrange.py")
                        writeFile file:'link-creator.py', text:libraryResource("link-creator.py")
                        sh "python3 ./link-creator.py --src ${nas_path} --trg ${pkgs_path}"
                    } catch (err) {
                        println "${err}"
                    }
                }
            } else {
                sh "ln -srfT $nas_path $latest_path"
            }
            recursive_touch(nasdir, latest_path)
        }
    }

    return nasinfo
}

def get_fw_linkpath(firmware_relative_path) {
    def nasdir = "$HOME/builder"
    def fw_path = "${nasdir}/${firmware_relative_path}"
    fw_path = sh_output("readlink -f $fw_path")
    fw_path = fw_path.replace("${nasdir}/", "")
    return fw_path
}

def get_fw_build_date(firmware_relative_path) {
    def nasdir = "$HOME/builder"
    def fw_path = "${nasdir}/${firmware_relative_path}"
    fw_path = sh_output("readlink -f $fw_path")
    def fw_name = fw_path.tokenize('/').pop()
    def build_date_pattern = ~/(\d+)\.(\d+)\.bin$/
    def matcher = (fw_name =~ build_date_pattern)
    if (matcher.size() == 1) {
        return matcher[0][1] + '.' + matcher[0][2]
    }
    return
}
