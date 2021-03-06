def bash(String cmd) { sh("#!/usr/bin/env bash\nset -euo pipefail\n${cmd}") }

def get_docker_registry() {
    // use ip to avoid dns problem when pull docker image from registry.ubnt.com.tw:6666
    def dockerRegistry='10.2.40.125:6666'
    return dockerRegistry
}

def is_qa_test_branch(branchName) {
    if (branchName == "master" || branchName == "stable/2.3" || branchName == "stable/2.4" || branchName.startsWith("sustain/unifi-core-"))
        return true
    return false
}

def is_uof_test_branch(branchName) {
    if (branchName.startsWith("test-auto/"))
        return true
    return is_qa_test_branch(branchName)
}

def test_uof_branch(Map nasInfo, Map metaInfo) {
    if(!is_uof_test_branch(metaInfo['br']))
        return
    withCredentials([
        string(credentialsId: 'uofusertoken', variable:'usertoken'),
        string(credentialsId: 'uofjobentrypointtoken', variable:'jobentrytoken')
        ]) {
            def fw_url = metaInfo['fwInfo'][metaInfo.product]['fw_url']
            def HOST = "10.2.0.141:5680"
            def data_list = ["",
                "\"cause=builder-trigger\"",
                "\"token=${jobentrytoken}\"",
                "\"IS_TAG_BUILD=${metaInfo['is_tag'] ? 'true' : 'false'}\"",
                "\"IS_PR_BUILD=${metaInfo['is_pr'] ? 'true' : 'false'}\"",
                "\"PRODUCT=${metaInfo['product']}\"",
                "\"THREAD_ID=${metaInfo['uofSlackResp'] ? metaInfo['uofSlackResp'].threadId : ''}\"",
                "\"FW_URL=${fw_url}\""
            ]
            print data_list
            sh "curl ${data_list.join(' --data-urlencode ')} --user ubnt:${usertoken} \"http://${HOST}/job/job-entry-point/buildWithParameters\""
            return
    }
}

def iev_qa_test(Map nasInfo, Map metaInfo) {
    if (!metaInfo['is_tag'] && !metaInfo['is_pr'] && !is_qa_test_branch(metaInfo['br'])) {
        echo "Skip IEV QA test ..."
        return
    }
    def name = metaInfo['name']
    def uploadInfo = metaInfo['uploadInfo']
    def gitInfo = metaInfo['gitInfo']
    if (name == 'UDMPROSE' || name == 'UDR' || name == 'UDW' || name == 'UDMPRO' || name == 'UDMBASE') {
        withCredentials([string(
            credentialsId: 'IEV_JENKINS_TOKEN',
            variable:'jobtoken')]) {
            def HOST="kyiv-vega.rad.ubnt.com"
            def JOB="UGW_FW_Dispatcher"
            def job_info = uploadInfo.path.join('_')
            def fw_link = metaInfo['fwInfo'][metaInfo.product]['fw_link']
            //debbox, heads, task/test-jenkins-lib, 20-7321-1d184627e
            def fw_name = "${gitInfo.local_branch}.${gitInfo.rev_num}"
            // task/test-jenkins-lib, 7321
            def data = "BRANCH=${gitInfo.local_branch}" +
                "&FW_NAME=${fw_name}" +
                "&FW_DIR=${uploadInfo.dir_name}" +
                "&BUILD_TYPE=${metaInfo.product}" +
                "&FW_VERSION=${job_info}" +
                "&FW_URL=${fw_link}" +
                "&FW_COMMIT=${gitInfo.revision}"
            print data
            sh "curl -k -d \"${data}\" \"https://${HOST}/jenkins/buildByToken/buildWithParameters/build?job=${JOB}&token=${jobtoken}\""
            // def udmpse_json = "\'{\"parameter\": " +
            //                   "[{\"name\":\"BRANCH\", \"value\":\"${m.git_args.local_branch}\"}, " +
            //                   "{\"name\":\"FW_NAME\", \"value\":\"${fw_name}\"}, " +
            //                   "{\"name\":\"FW_DIR\", \"value\":\"${m.upload_info.dir_name}}\"}, " +
            //                   "{\"name\":\"BUILD_TYPE\", \"value\":\"${target_map.product}\"}, " +
            //                   "{\"name\":\"FW_VERSION\", \"value\":\"${job_info}\"}, " +
            //                   "{\"name\":\"FW_URL\", \"value\":\"${url}\"}, " +
            //                   "{\"name\":\"FW_COMMIT\", \"value\":\"${m.git_args.revision}\"}]}\'"
            // sh "curl -k -X POST \"https://${HOST}/jenkins/buildByToken/buildWithParameters/build?job=${JOB}&token=${jobtoken}\" --data-urlencode json=${udmpse_json}"
        }
    }
}

def net_qa_test(Map nasInfo, Map metaInfo) {
    if (!metaInfo['is_tag'] && !metaInfo['is_pr'] && !is_qa_test_branch(metaInfo['br'])) {
        echo "Skip NET QA test ..."
        return
    }
    if(metaInfo['name'] != 'UCKP') {
        return
    }
    def uploadInfo = metaInfo['uploadInfo']
    def gitInfo = metaInfo['gitInfo']
    def fw_url = metaInfo['fwInfo'][metaInfo.product]['fw_url']
    withCredentials([string(
        credentialsId: 'NET_JENKINS_TOKEN',
        variable: 'jobtoken')]) {
        def HOST = "jenkins.network-controller-prod.a.uidev.tools"
        def JOB = "unifi-network-be-e2e-tests/network-e2e-cloudkey-firmware-trigger"
        def job_info = uploadInfo.path.join('_')
        def fw_name = "${gitInfo.local_branch}.${gitInfo.rev_num}"
        def data = "BRANCH=${gitInfo.local_branch}" +
                "&FW_NAME=${fw_name}" +
                "&FW_DIR=${uploadInfo.dir_name}" +
                "&BUILD_TYPE=${metaInfo.product}" +
                "&FW_VERSION=${job_info}" +
                "&FW_URL=${fw_url}" +
                "&FW_COMMIT=${gitInfo.revision}"
        print data
        sh "curl -k -d \"${data}\" \"https://${HOST}/buildByToken/buildWithParameters/build?job=${JOB}&token=${jobtoken}\""
    }
}

def tpe_qa_test(Map nasInfo, Map metaInfo) {
    if (!metaInfo['is_tag'] && !metaInfo['is_pr'] && !is_qa_test_branch(metaInfo['br'])) {
        echo "Skip tpe QA test ..."
        return
    }
    if(metaInfo['name'] == 'UDWPRO' || metaInfo['name'] == 'UDMBASE') {
        echo "Skip tpe un-support model ..."
        return
    }
    def fw_url = metaInfo['fwInfo'][metaInfo.product]['fw_url']
    def params = "fw_url=${fw_url}\nslack_channel=${metaInfo.slackThreadId}"
    def isBlockBuild = false
    def auth = CredentialsAuth(credentials: 'jenkins8787-trigger')
    def job = null
    if (metaInfo['name'] == 'UNVR') {
        job = triggerRemoteJob job: "https://tpe-pbsqa-ci.rad.ubnt.com:8443/job/Debbox/job/UNVR_smoke_entry",
                                blockBuildUntilComplete: isBlockBuild,
                                parameters: params,
                                auth: auth
    } else {
        job = triggerRemoteJob job: "https://tpe-pbsqa-ci.rad.ubnt.com:8443/job/Debbox/job/${metaInfo.name}_smoke_test",
                                blockBuildUntilComplete: isBlockBuild,
                                parameters: params,
                                auth: auth
    }
}

def get_ids() {
    def username = sh_output("whoami")
    def uid = sh_output("id -zu $username")
    def gid = sh_output("id -zg $username")
    return [uid, gid]
}

def get_job_options(String project) {
    def options = [
        debbox_builder: [
            job_artifact_dir: "${env.JOB_NAME}_${env.BUILD_TIMESTAMP}_${env.BUILD_NUMBER}",
            node: 'debbox',
            upload: true,
            project_cache: true,
            project_cache_location: 'debbox.git'
        ],
        debfactory_builder:[
            job_artifact_dir: "${env.JOB_BASE_NAME}_${env.BUILD_TIMESTAMP}_${env.BUILD_NUMBER}",
            node: 'debfactory',
            build_archs: ['arm64'],
            upload: true
        ],
        debfactory_non_cross_builder:[
            job_artifact_dir: "${env.JOB_BASE_NAME}_${env.BUILD_TIMESTAMP}_${env.BUILD_NUMBER}",
            node: 'debfactory',
            build_archs: ['arm64'],
            upload: true,
            non_cross: true
        ],
        analytic_report_builder:[
            name: 'analytic_report',
            node: 'debfactory',
            upload: true,
            non_cross: true
        ],
        disk_smart_mon_builder:[
            name: 'disk_smart_mon',
            node: 'debfactory',
            upload: true
        ],
        disk_quota_builder:[
            name: 'disk_quota',
            node: 'debfactory',
            upload: true
        ],
        debbox_base_files_builder:[
            name: 'debbox_base',
            node: 'debfactory',
            upload: true
        ],
        cloudkey_apq8053_initramfs_builder:[
            name: 'cloudkey_apq8053_initramfs',
            node: 'debfactory',
            upload: true
        ],
        ubnt_archive_keyring_builder:[
            name: 'ubnt_archive_keyring',
            node: 'debfactory',
            upload: true
        ],
        ubnt_zram_swap_builder:[
            name: 'ubnt_zram_swap',
            node: 'debfactory',
            upload: true
        ],
        ubnt_tools_builder:[
            name: 'ubnt_tools',
            node: 'debfactory',
            upload: true
        ],
        amaz_alpinev2_boot_builder:[
            name: 'amaz_alpinev2_boot',
            node: 'debfactory',
            upload: true
        ],
        mt7622_boot_builder:[
            name: 'mt7622_boot',
            node: 'debfactory',
            upload: true
        ],
        preload_image_builder:[
            name: 'preload_image',
            node: 'debfactory',
            upload: true
        ],
        ustd_checker:[
            name: 'ustd',
            node: 'debfactory',
            upload: false
        ],
        rover_builder: [
            job_artifact_dir: "${env.JOB_NAME}_${env.BUILD_TIMESTAMP}_${env.BUILD_NUMBER}",
            node: 'ROS',
            upload: true,
            project_cache: true,
            project_cache_location: 'debbox.git'
        ]
    ]

    return options.get(project, [:])
}


def get_docker_args(artifact_dir) {
    def cache_dir = project_cache_updater.get_project_cache_dir()
    return '-u 0 --privileged=true ' +
        "-v $HOME/.jenkinbuild/.ssh:/root/.ssh:ro " +
        "-v ${artifact_dir}:/root/artifact_dir:rw " +
        "-v ${cache_dir}:${cache_dir}:rw " +
        '-v /ccache:/ccache:rw ' +
        '--env CCACHE_DIR=/ccache ' +
        '--env PATH=/usr/lib/ccache:/usr/local/sbin:/usr/local/bin:/usr/sbin:/usr/bin:/sbin:/bin'
}

/*
 * resultpath: the output dir that indicate where the binary generated
 * artifact_dir: this is the directory created in our build machine for storing the binary (relative path for artifact)
 * build_archs can be self defined
 */
def debfactory_builder(String productSeries, Map job_options=[:], Map build_series=[:]) {
    def build_jobs = []
    verify_required_params('debfactory_builder', job_options, [ 'build_archs' ])
    echo "build $productSeries"
    def build_dist = ['stretch']
    if (job_options.containsKey('dist')) {
        build_dist = job_options.dist.class == String ? [job_options.dist] : job_options.dist
    }

    job_options.build_archs.each { arch->
        build_jobs.add([
            node: job_options.node ?: 'debfactory',
            name: 'debfactory',
            dist: build_dist,
            resultpath: "build/dist",
            execute_order: 1,
            artifact_dir: job_options.job_artifact_dir ?: "${env.JOB_BASE_NAME}_${env.BUILD_TIMESTAMP}_${env.BUILD_NUMBER}",
            build_record: job_options.build_record ?: false,
            arch: arch,
            build_status:false,
            upload: job_options.upload ?: false,
            non_cross: job_options.non_cross ?: false,
            build_steps: { m->
                def uid, gid
                if (m.buildPackages == null) {
                    m.buildPackages = [:]
                }
                (uid, gid) = get_ids()
                sh 'export'
                stage ("checkout $m.name") {
                    m.build_dir = "${m.name}-${env.BUILD_NUMBER}-${env.BUILD_TIMESTAMP}"
                    sh "mkdir -p ${m.artifact_dir}"
                    m.absolute_artifact_dir = sh_output("readlink -f ${m.artifact_dir}")

                    dir("$m.build_dir") {
                        // checkout master by default
                        checkout([
                             $class: 'GitSCM',
                             branches:  [[name: 'refs/remotes/origin/master']],
                             doGenerateSubmoduleConfigurations: false,
                             extensions: [],
                             userRemoteConfigs: [[credentialsId: "${scm.userRemoteConfigs.credentialsId[0]}", url: "${scm.userRemoteConfigs.url[0]}"]]
                        ])
                        def co_map = checkout scm
                        def url = co_map.GIT_URL
                        def git_args = git_helper.split_url(url)
                        def repository = git_args.repository

                        echo "URL: ${url} -> site: ${git_args.site} " + "owner:${git_args.owner} repo: ${repository}"
                        git_args.revision = git_helper.sha()
                        git_args.rev_num = git_helper.rev()

                        def is_pr = env.getProperty('CHANGE_ID') != null
                        def is_tag = env.getProperty('TAG_NAME') != null
                        def is_atag = env.getProperty('TAG_NAME') != null

                        if (is_pr && is_atag) {
                            error 'Unexpected environment, cannot be both PR and TAG'
                        }
                        def ref
                        if (is_tag) {
                            ref = TAG_NAME
                            try {
                                git_helper.verify_is_atag(ref)
                            } catch (all) {
                                println "catch error: $all"
                                is_atag = false
                            }
                            println "tag build: istag: $is_tag, is_atag:$is_atag"
                            git_args.local_branch = ref
                        } else {
                            ref = git_helper.current_branch()
                            if (!ref || ref == 'HEAD') {
                                ref = "origin/${BRANCH_NAME}"
                            } else {
                                git_args.local_branch = ref
                            }
                        }
                        // decide release build logic
                        def is_release = false
                        if (is_tag) {
                            if (is_atag) {
                                is_release = true
                            } else {
                                is_release = TAG_NAME.contains('release')
                            }
                        }
                        m.is_release = is_release
                        git_args.is_pr = is_pr
                        git_args.is_tag = is_tag
                        git_args.is_atag = is_atag
                        git_args.ref = ref
                        m['git_args'] = git_args.clone()
                        m.upload_info = ubnt_nas.generate_buildinfo(m.git_args)
                        print m.upload_info

                        def last_successful_commit
                        if (is_pr) {
                            last_successful_commit = sh_output("git merge-base remotes/origin/${env.BRANCH_NAME} remotes/origin/${env.CHANGE_TARGET}")
                        } else {
                            if (env.BRANCH_NAME != 'master' && !(is_tag || is_atag)) { // non-master branch build
                                last_successful_commit = sh_output("git merge-base remotes/origin/${env.BRANCH_NAME} remotes/origin/master")
                            } else {
                                last_successful_commit = utility.getLastSuccessfulCommit()
                                if (env.commitHash && !env.commitHash.isEmpty()) {
                                    last_successful_commit = env.commitHash
                                } else if (!last_successful_commit) {
                                    last_successful_commit = "HEAD~"
                                }
                            }
                        }
                        def pkg_tools_params = "-g ${last_successful_commit}"
                        if (env.ForceChangesPkgs && !env.ForceChangesPkgs.isEmpty()) {
                            pkg_tools_params = env.ForceChangesPkgs.tokenize(';&|').join(' ')
                        }
                        print pkg_tools_params

                        m.dist.each { d ->
                            def b_pkg = []
                            String pkg_tools_cmd = "./pkg-tools.py ${m.non_cross ? '-nc' : ''}"
                            // TODO "dist" judgement can be removed in the future
                            def rc = sh script: "./pkg-tools.py -h | grep dist", returnStatus:true
                            if (rc == 0) {
                                pkg_tools_cmd += " -d ${d}"
                            }
                            pkg_tools_cmd += " -r ${pkg_tools_params}"
                            sh_output(pkg_tools_cmd).tokenize('\n').each {
                                b_pkg << it
                            }
                            m.buildPackages[d] = b_pkg
                            println "Packages (${d}) to be built: ${b_pkg}"
                        }

                        sh 'ls -lahi'
                        println "resultpath: $m.resultpath"
                        println "artifact_dir: $m.artifact_dir"
                    }
                }
                stage("build $m.name") {
                    dir_cleanup("$m.build_dir") {
                        m.build_status = true
                        try {
                            m.dist.each { d ->
                                if (m.buildPackages[d].size() == 0) {
                                    return
                                }
                                def dockerRegistry = get_docker_registry()
                                def dockerImage = docker.image("$dockerRegistry/debbox-builder-cross-${d}-arm64:latest")
                                if (m.non_cross) {
                                    dockerImage = docker.image("$dockerRegistry/debbox-builder-qemu-${d}-arm64:latest")
                                }
                                dockerImage.pull()
                                dockerImage.inside(get_docker_args(m.absolute_artifact_dir)) {
                                    def build_targets = m.buildPackages[d].join(' ')
                                    def debbox_cache = "${project_cache_updater.get_project_cache_dir()}/debbox.git"
                                    def kernel_cache = "${project_cache_updater.get_project_cache_dir()}/debbox-kernel.git"
                                    try {
                                        sh "DEBBOX_CACHE=${debbox_cache} KERNEL_CACHE=${kernel_cache} make ARCH=$m.arch DIST=${d} BUILD_DEPEND=yes ${build_targets} 2>&1"
                                    }
                                    catch (Exception e) {
                                        throw e
                                    } finally {
                                        sh "echo gid=$gid uid:$uid"
                                        sh "chown $uid:$gid -R /root/artifact_dir"
                                        sh "chown $uid:$gid -R ./"
                                    }
                                }
                            }
                        }
                        catch (Exception e) {
                            m.build_status = false
                            print e
                            throw e
                        }
                        try {
                            def upload_prefix = m.upload_info.path.join('/')
                            def artifact_dir = m.absolute_artifact_dir

                            // Intentionally use .makefile to avoid uploading to nas job dir
                            sh "mkdir -p ${artifact_dir}/.makefile"
                            writeFile file:'pkg-arrange.py', text:libraryResource("pkg-arrange.py")
                            sh "python3 ./pkg-arrange.py -o ${artifact_dir}/.makefile -u ${ubnt_nas.get_nasdomain()}/${upload_prefix} ./${m.resultpath}/"

                            def b_pkg = []
                            m.buildPackages.each { key, value ->
                                b_pkg += value
                            }
                            b_pkg.each { pkg ->
                                pkg = pkg.replaceAll('-dev$|-dbgsym$','')
                                sh "test ! -d ${m.resultpath}/${pkg} || cp -rf ${m.resultpath}/${pkg} ${artifact_dir}"
                            }
                        }
                        catch (Exception e) {
                            m.build_status = false
                            print e
                            throw e
                        }
                        finally {
                            deleteDir()
                            return m.build_status
                        }
                    }
                }
            },
            archive_steps: { m->
                def b_pkg = []
                m.buildPackages.each { key, value ->
                    b_pkg += value
                }
                if (b_pkg.size() == 0) {
                    dir_cleanup("$m.build_dir") {
                    }
                    return
                }
                stage("Artifact ${m.name}") {
                    archiveArtifacts artifacts: "${m.artifact_dir}/**"
                }
                stage('Upload to server') {
                    if (m.upload && m.containsKey('upload_info')) {
                        def upload_prefix = m.upload_info.path.join('/')
                        def latest_prefix = m.upload_info.latest_path.join('/')
                        def pkgs_prefix = m.upload_info.pkgs_path.join('/')
                        ubnt_nas.upload(m.absolute_artifact_dir + '/*' , upload_prefix, latest_prefix, true, pkgs_prefix)
                        if (m.build_record) {
                            def ref_path = m.upload_info.ref_path.join('/')
                            ref_path = "${ubnt_nas.get_nasdir()}/${ref_path}"
                            sh "rsync -av ${m.absolute_artifact_dir}/.makefile/ ${ref_path}/.makefile/"
                            sh "rm -rf ${m.absolute_artifact_dir}"
                        }
                    }
                }
            }
        ])
    }
    return build_jobs
}

def debfactory_non_cross_builder(String productSeries, Map job_options=[:], Map build_series=[:]) {
    return debfactory_builder(productSeries, job_options, build_series)
}

def debbox_builder(String productSeries, Map job_options=[:], Map build_series=[:]) {
    def debbox_series = [
        UCK:
        [
            // UCK: 'unifi-cloudkey.mtk',
            UCKG2: [product: 'cloudkey-g2.apq8053', resultpath: 'target-cloudkey-g2.apq8053'],
            UCKP: [product: 'cloudkey-plus.apq8053', resultpath: 'target-cloudkey-plus.apq8053']
        ],
        UNVR:
        [
            UNVR: [product: 'unifi-nvr4-protect.alpine', resultpath:'target-unifi-nvr4.alpine'],
            UNVRPRO: [product: 'unifi-nvr-pro-protect.alpine', resultpath:'target-unifi-nvr-pro.alpine'],
            UNVRAI: [product: 'unifi-nvr-ai-protect.alpine', resultpath:'target-unifi-nvr-ai.alpine'],
            UNVRFCD: [product: 'unifi-nvr4-fcd.alpine', resultpath:'target-unifi-nvr4.alpine'],
            UNVRPROFCD: [product: 'unifi-nvr-pro-fcd.alpine', resultpath:'target-unifi-nvr-pro.alpine'],
            UNVRAIFCD: [product: 'unifi-nvr-ai-fcd.alpine', resultpath:'target-unifi-nvr-ai.alpine']
        ],
        NX:
        [
            UNX: [product: 'unifi-nx.nvidia', resultpath: 'target-unifi-nx.nvidia', additional_store: ['image/unx-image/boot.img', 'image/unx-image/rootfs.img', 'image/unx-image/jetson.dtb']],
            UNXTHERMAL: [product: 'unifi-nx-thermal.nvidia', resultpath: 'target-unifi-nx-thermal.nvidia', additional_store: ['image/unx-image/boot.img', 'image/unx-image/rootfs.img', 'image/unx-image/jetson.dtb']]
        ],
        UNIFICORE:
        [
            UCKG2: [product: 'cloudkey-g2.apq8053', resultpath: 'target-cloudkey-g2.apq8053', tag_prefix: 'unifi-cloudkey', pack_bootloader: ''],
            UCKP: [product: 'cloudkey-plus.apq8053', resultpath: 'target-cloudkey-plus.apq8053', tag_prefix: 'unifi-cloudkey', pack_bootloader: ''],
            UNVR: [product: 'unifi-nvr4-protect.alpine', resultpath:'target-unifi-nvr4.alpine', tag_prefix: 'unifi-nvr', pack_bootloader: 'yes'],
            UNVRPRO: [product: 'unifi-nvr-pro-protect.alpine', resultpath:'target-unifi-nvr-pro.alpine', tag_prefix: 'unifi-nvr', pack_bootloader: 'yes'],
            UNVRHD: [product: 'unifi-nvr-hd-protect.alpine', resultpath:'target-unifi-nvr-hd.alpine', tag_prefix: 'unifi-nvr', pack_bootloader: 'yes'],
            // UNVRAI: [product: 'unifi-nvr-ai-protect.alpine', resultpath:'target-unifi-nvr-ai.alpine', tag_prefix: 'unifi-nvr', pack_bootloader: 'yes'],
            // UNVRNK: [product: 'unifi-nvr-pro-nk.alpine', resultpath:'target-unifi-nvr-pro.alpine', tag_prefix: 'unifi-nvr'],
            UNVRFCD: [product: 'unifi-nvr4-fcd.alpine', resultpath:'target-unifi-nvr4.alpine', tag_prefix: 'unifi-nvr', pack_bootloader: 'yes'],
            UNVRPROFCD: [product: 'unifi-nvr-pro-fcd.alpine', resultpath:'target-unifi-nvr-pro.alpine', tag_prefix: 'unifi-nvr', pack_bootloader: 'yes'],
            UNVRHDFCD: [product: 'unifi-nvr-hd-fcd.alpine', resultpath:'target-unifi-nvr-hd.alpine', tag_prefix: 'unifi-nvr', pack_bootloader: 'yes']
            // UNVRAIFCD: [product: 'unifi-nvr-ai-fcd.alpine', resultpath:'target-unifi-nvr-ai.alpine', tag_prefix: 'unifi-nvr', pack_bootloader: 'yes']
        // UNVRNKFCD: [product: 'unifi-nvr-pro-nk-fcd.alpine', resultpath:'target-unifi-nvr-pro-nk.alpine', tag_prefix: 'unifi-nvr']
        ],
        UDM:
        [
            UDMPROSE: [product: 'unifi-udm-pro-se-controller.alpine', resultpath: 'target-unifi-udm-pro-se.alpine', tag_prefix: 'unifi-udm', additional_store: ['image/dream-image/uImage', 'image/dream-image/vmlinux', 'image/dream-image/vmlinuz-*-ui-alpine'], pack_bootloader: 'yes'],
            UDR: [product: 'unifi-dream-router-controller.mt7622', resultpath: 'target-unifi-dream-router.mt7622', tag_prefix: 'unifi-udr', additional_store: ['image/mtk7622-fwimage/uImage'], pack_bootloader: 'yes'],
            UDW: [product: 'unifi-dream-wall.alpine', resultpath: 'target-unifi-dream-wall.alpine', tag_prefix: 'unifi-udw', additional_store: ['image/dream-wall-image/uImage', 'image/dream-wall-image/vmlinux', 'image/dream-wall-image/vmlinuz-*-alpine-udw'], pack_bootloader: 'yes']
        ]
    ]

    if (build_series.size() == 0) {
        build_series = debbox_series.clone()
    }
    verify_required_params('debbox_builder', build_series, [ productSeries ])
    echo "build $productSeries"

    def is_pr = env.getProperty('CHANGE_ID') != null
    def is_tag = env.getProperty('TAG_NAME') != null
    def is_atag = env.getProperty('TAG_NAME') != null
    def build_product = build_series[productSeries]
    def build_jobs = []
    def job_shared = [:]
    Boolean is_temp_build = job_options.is_temp_build ?: false

    def slackThreadId = null
    if ((is_tag || is_pr || is_qa_test_branch(BRANCH_NAME)) && !is_temp_build) {
        def slackResp = slackSend(channel: 'unifi-os-firmware-smoke', message: "[STARTED] ${env.BUILD_URL}", color: "good")
        if (slackResp) slackThreadId = slackResp.threadId
    }

    def uofSlackResp = null
    if (is_uof_test_branch(BRANCH_NAME) && !is_temp_build) {
        uofSlackResp = slackSend(channel: 'unifi-os-firmware-smoke', message: "[STARTED] ${env.BUILD_URL}", color: "good", iconEmoji: ":pepe-sad:")
    }
    build_product.each { name, target_map ->
        def product_shared = [:]
        if (is_tag && productSeries == 'UNIFICORE') {
            def current_tag_prefix = TAG_NAME.tokenize('/')[0]
            if (current_tag_prefix != target_map.tag_prefix) {
                return
            }
        }
        build_jobs.add([
            node: 'debbox',
            execute_order: 3,
            name: target_map.product + '-3',
            build_status:false,
            pre_steps: { m->
                // merge three maps
                product_shared.each {k, v->
                    if(!m.containsKey(k) && !k.contains('_steps')) {
                        m[k] = v
                    }
                }
                job_shared.each { k, v->
                    if(!m.containsKey(k) && !k.contains('_steps')) {
                        m[k] = v
                    }
                }
                println(m)
            },
            qa_test_steps: { m->
                println(m.upload_info)
                if (m.name.contains('fcd')) {
                    echo "Skip fcd fw QA test ..."
                    return
                }
                if (is_temp_build) {
                    echo "Skip temp build fw QA test ..."
                    return
                }
                def metaInfo = [:]
                metaInfo['name'] = name //UDR, UDW
                metaInfo['product'] = target_map.product //uck-g2.apq8053, udr.mt7622
                metaInfo['br'] = BRANCH_NAME
                metaInfo['is_tag'] = is_tag
                metaInfo['is_pr'] = is_pr
                metaInfo['uofSlackResp'] = uofSlackResp
                metaInfo['slackThreadId'] = slackThreadId
                metaInfo['uploadInfo'] = m.upload_info
                metaInfo['gitInfo'] = m.git_args
                /*
                product: {
                    'fw_link' : https://xxx/FW.LATEST.bin
                    'fw_url' : https://xxx/UNVRPRO.al324.v2.5.0+root.7378.66d3fa1.220408.2323.bin
                }
                */
                def fwInfo = [:]
                m.nasinfo.each { url, artifact ->
                    if (artifact.endsWith(".bin") && url.contains(target_map.product)) {
                        if(!fwInfo.containsKey(target_map.product)) {
                            fwInfo[target_map.product] = [:]
                        }
                        println(fwInfo)
                        fwInfo[target_map.product]['fw_url'] = url
                        fwInfo[target_map.product]['fw_link'] = url.replace(/$artifact/, 'FW.LATEST.bin')
                    }
                }
                metaInfo['fwInfo'] = fwInfo
                test_uof_branch(m.nasinfo, metaInfo)
                iev_qa_test(m.nasinfo, metaInfo)
                net_qa_test(m.nasinfo, metaInfo)
                tpe_qa_test(m.nasinfo, metaInfo)
            }
        ])
        build_jobs.add([
            node: 'debbox',
            name: target_map.product,
            execute_order: 1,
            resultpath: target_map.resultpath,
            additional_store: target_map.additional_store ?: [],
            artifact_dir: job_options.job_artifact_dir ?: "${env.JOB_NAME}_${env.BUILD_TIMESTAMP}_${env.BUILD_NUMBER}_${name}",
            pack_bootloader: target_map.pack_bootloader ?: 'yes',
            build_status:false,
            project_cache: (job_options.project_cache ?: false),
            project_cache_location: (job_options.project_cache_location ?: 'debbox.git'),
            upload: job_options.upload ?: false,
            dist: job_options.dist ?: "stretch",
            arch: target_map.arch ?: "arm64",
            pre_checkout_steps: { m->
                // do whatever you want before checkout step
                sh 'export'
                return true
            },
            build_steps: { m->
                stage("Pre-build ${m.name} stage") {
                    // do whatever you want before building process
                    m.build_number = env.BUILD_NUMBER
                    m.build_dir = "${m.name}-${m.build_number}"
                    m.docker_artifact_path = m.artifact_dir + '/' + m.name

                    sh "mkdir -p ${m.build_dir} ${m.docker_artifact_path}"
                    m.docker_artifact_path = sh_output("readlink -f ${m.docker_artifact_path}")
                }
                stage("Build ${m.name}") {
                    dir_cleanup("${m.build_dir}") {
                        def dockerRegistry = get_docker_registry()
                        def dockerImage
                        if (productSeries == 'NX') {
                            dockerImage = docker.image("$dockerRegistry/ubuntu:nx")
                        } else {
                            dockerImage = docker.image("$dockerRegistry/debbox-builder-cross-${m.dist}-${m.arch}:latest")
                        }
                        dockerImage.pull()
                        def docker_args = get_docker_args(m.docker_artifact_path) + " -v ${aws_rotate.get_aws_dir()}:/root/.aws:ro"
                        dockerImage.inside(docker_args) {
                            /*
                            * tag build var:
                            * TAG_NAME: unifi-cloudkey/v1.1.9
                            *
                            * pr build var:
                            * CHANGE_BRANCH: feature/unifi-core-integration
                            * CHANGE_ID: 32 (pull request ID)
                            * git_args:
                            *     user: git
                            *     site: git.uidev.tools,
                            *     repository: firmware.debbox
                            *     revision: git changeset
                            *     local_branch: feature/unifi-core-integration or unifi-cloudkey/v1.1.9
                            *     is_pr: true or false
                            *     is_tag: true or false
                            *     tag: unifi-cloudkey/v1.1.9 (TAG_NAME)
                            *     branch_name (feature/unifi-core-integration)
                            */
                            def co_map
                            // open it when we have method to modify the CloneOption without affect others option
                            // def cache_path = "${project_cache_updater.get_project_cache_dir()}/${m.project_cache_location}"
                            // scm.extensions = scm.extensions + [[$class: 'CloneOption', reference: "${cache_path}"]]
                            for(retry = 0; retry < 3; retry++) {
                                try {
                                    timeout(time: 10, unit: 'MINUTES') {
                                        co_map = checkout scm
                                    }
                                    break
                                } catch (e) {
                                    echo "Timeout during checkout scm"
                                    sh "rm -rf .git || true"
                                }
                            }

                            def url = co_map.GIT_URL
                            def git_args = git_helper.split_url(url)
                            def repository = git_args.repository
                            echo "URL: ${url} -> site: ${git_args.site} " + "owner:${git_args.owner} repo: ${repository}"
                            git_args.revision = git_helper.sha()
                            git_args.rev_num = git_helper.rev()
                            if (is_pr && is_atag) {
                                error 'Unexpected environment, cannot be both PR and TAG'
                            }
                            def ref
                            if (is_tag) {
                                ref = TAG_NAME
                                try {
                                    git_helper.verify_is_atag(ref)
                                } catch (all) {
                                    println "catch error: $all"
                                    is_atag = false
                                }
                                println "tag build: istag: $is_tag, is_atag:$is_atag"
                                git_args.local_branch = ref
                            } else if (is_pr) {
                                // use change branch as ref
                                ref = "origin/${env.CHANGE_BRANCH}"
                                git_args.local_branch = "PR-${env.CHANGE_ID}"
                            } else {
                                ref = git_helper.current_branch()
                                if (!ref || ref == 'HEAD') {
                                    ref = "origin/${BRANCH_NAME}"
                                }
                                git_args.local_branch = ref
                            }
                            // decide release build logic
                            def is_release = false
                            if (is_tag) {
                                if (is_atag) {
                                    is_release = true
                                } else {
                                    is_release = TAG_NAME.contains('release')
                                }
                                is_release = true
                            }
                            m.is_release = is_release
                            git_args.is_pr = is_pr
                            git_args.is_tag = is_tag
                            git_args.is_atag = is_atag
                            git_args.ref = ref
                            if (is_temp_build) {
                                git_args.is_temp_build = true
                                git_args.temp_dir_name = m.dist
                            }
                            m['git_args'] = git_args.clone()
                            m.upload_info = ubnt_nas.generate_buildinfo(m.git_args)
                            print m.upload_info
                            try {
                                def kcache = "${project_cache_updater.get_project_cache_dir()}/debbox-kernel.git"
                                println "kcache: $kcache"
                                withEnv(['AWS_SHARED_CREDENTIALS_FILE=/root/.aws/credentials', 'AWS_CONFIG_FILE=/root/.aws/config']) {
                                    bash "AWS_PROFILE=default GITCACHE=${kcache} PACK_BOOTLOADER=${m.pack_bootloader} make PRODUCT=${m.name} DIST=${m.dist} RELEASE_BUILD=${is_release} 2>&1 | tee make.log"
                                }
                                println "Build completed: $m.name"
                                sh 'cp make.log /root/artifact_dir/'
                                sh "cp -r build/${m.resultpath}/dist/* /root/artifact_dir/"
                                sh "cp build/${m.resultpath}/bootstrap/root/usr/lib/version /root/artifact_dir/"
                                if (productSeries == 'UNVR' || name.contains('UNVR')) {
                                    sh "cp -r build/${m.resultpath}/image/unvr-image/uImage /root/artifact_dir/"
                                    sh "cp -r build/${m.resultpath}/image/unvr-image/vmlinux /root/artifact_dir/"
                                    sh "cp -r build/${m.resultpath}/image/unvr-image/vmlinuz-* /root/artifact_dir/"
                                }
                                m.additional_store.each { additional_file ->
                                    sh "cp -r build/${m.resultpath}/$additional_file /root/artifact_dir/"
                                }
                            }
                            catch (Exception e) {
                                // Due to we have the build error, remove all at here
                                sh "rm -rf /root/artifact_dir"
                                sh "rm -rf build"
                                throw e
                            }
                            finally {
                                // In order to cleanup the dl and build directory
                                sh 'chmod -R 777 .'
                                deleteDir()
                            }
                        }
                    }
                }
                return true
            },
            archive_steps: { m->
                stage('Upload to server') {
                    if (m.upload && m.containsKey('upload_info')) {
                        // upload "TO" path
                        def upload_path = m.upload_info.path.join('/')
                        def temp_path = ubnt_nas.get_temp_dir() + upload_path
                        def latest_path = ubnt_nas.get_temp_dir() + m.upload_info.latest_path.join('/')
                        ubnt_nas.upload(m.docker_artifact_path, temp_path, latest_path)
                        job_shared['job_upload_info'] = [:]
                        job_shared['job_upload_info']['upload_src_path'] = temp_path
                        job_shared['job_upload_info']['upload_dst_path'] = upload_path
                    }
                }
            },
            archive_cleanup_steps: { m->
                stage('Cleanup archive') {
                    try {
                        dir_cleanup("${m.docker_artifact_path}") {
                            deleteDir()
                        }
                    }
                    catch (Exception e) {
                    // do nothing
                    }
                }
            },
            post_steps: { m->
                product_shared = m.clone()
            }
        ])

    }
    build_jobs.add([
        node: 'debbox',
        execute_order: 2,
        name: 'upload',
        build_status:false,
        build_steps: { m->
            stage('Move to server') {
                if(job_shared.containsKey('job_upload_info')) {
                    def src_path = job_shared.job_upload_info['upload_src_path']
                    def dst_path = job_shared.job_upload_info['upload_dst_path']
                    m.nasinfo = ubnt_nas.move(src_path, dst_path)
                    job_shared['nasinfo'] = m.nasinfo.clone()
                }
            }
        }
    ])
    return build_jobs
}

def disk_smart_mon_builder(String productSeries, Map job_options=[:], Map build_series=[:]) {
    echo "build $productSeries"
    return debpkg(job_options, ['stretch/arm64', 'bullseye/arm64'])
}

def disk_quota_builder(String productSeries, Map job_options=[:], Map build_series=[:]) {
    echo "build $productSeries"
    return debpkg(job_options, ['stretch/all', 'bullseye/all'])
}

def analytic_report_builder(String productSeries, Map job_options=[:], Map build_series=[:]) {
    echo "build $productSeries"
    return debpkg(job_options, ['stretch/arm64', 'bullseye/arm64'])
}

def debbox_base_files_builder(String productSeries, Map job_options=[:], Map build_series=[:]) {
    echo "build $productSeries"
    return debpkg(job_options, ['stretch/all', 'bullseye/all'])
}

def cloudkey_apq8053_initramfs_builder(String productSeries, Map job_options=[:], Map build_series=[:]) {
    echo "build $productSeries"
    return debpkg(job_options, ['stretch/all', 'bullseye/all'])
}

def ubnt_archive_keyring_builder(String productSeries, Map job_options=[:], Map build_series=[:]) {
    echo "build $productSeries"
    return debpkg(job_options, ['stretch/all', 'bullseye/all'])
}

def ubnt_zram_swap_builder(String productSeries, Map job_options=[:], Map build_series=[:]) {
    echo "build $productSeries"
    return debpkg(job_options, ['stretch/all', 'bullseye/all'])
}

def ubnt_tools_builder(String productSeries, Map job_options=[:], Map build_series=[:]) {
    echo "build $productSeries"

    // stretch/amd64 could not build successfully
    return debpkg(job_options, ['stretch/arm64', 'bullseye/arm64', 'bullseye/amd64'])
}

def rover_builder(String productSeries, Map job_options=[:], Map build_series=[:]) {
    def rover_series = [
        ROVER:[
            WAVEROVER: [product: 'wave-rover.alpine', resultpath: 'target-wave-rover.alpine', tag_prefix: 'wave-rover', additional_store: ['image/wave-rover-image/uImage', 'image/wave-rover-image/vmlinux', 'image/wave-rover-image/vmlinuz-*'], pack_bootloader: 'yes'],
        ]
    ]

    if (build_series.size() == 0) {
        build_series = rover_series.clone()
    }
    verify_required_params('rover_builder', build_series, [ productSeries ])
    echo "build $productSeries"

    def is_pr = env.getProperty('CHANGE_ID') != null
    def is_tag = env.getProperty('TAG_NAME') != null
    def is_atag = env.getProperty('TAG_NAME') != null
    def build_product = build_series[productSeries]
    def build_jobs = []
    def job_shared = [:]
    Boolean is_temp_build = job_options.is_temp_build ?: false

    def slackThreadId = null
    /*if ((is_tag || is_pr || is_qa_test_branch(BRANCH_NAME)) && !is_temp_build) {
        def slackResp = slackSend(channel: 'rover-firmware-smoke', message: "[STARTED] ${env.BUILD_URL}", color: "good")
        if (slackResp) slackThreadId = slackResp.threadId
    }*/

    def uofSlackResp = null
    /*if (is_uof_test_branch(BRANCH_NAME) && !is_temp_build) {
        uofSlackResp = slackSend(channel: 'rover-firmware-smoke', message: "[STARTED] ${env.BUILD_URL}", color: "good", iconEmoji: ":pepe-sad:")
    }*/
    build_product.each { name, target_map ->
        def product_shared = [:]
        if (is_tag && productSeries == 'UNIFICORE') {
            def current_tag_prefix = TAG_NAME.tokenize('/')[0]
            if (current_tag_prefix != target_map.tag_prefix) {
                return
            }
        }
        build_jobs.add([
            node: 'ROS',
            execute_order: 3,
            name: target_map.product + '-3',
            build_status:false,
            pre_steps: { m->
                // merge three maps
                product_shared.each {k, v->
                    if(!m.containsKey(k) && !k.contains('_steps')) {
                        m[k] = v
                    }
                }
                job_shared.each { k, v->
                    if(!m.containsKey(k) && !k.contains('_steps')) {
                        m[k] = v
                    }
                }
                println(m)
            },
            qa_test_steps: { m->
                println(m.upload_info)
                if (m.name.contains('fcd')) {
                    echo "Skip fcd fw QA test ..."
                    return
                }
                if (is_temp_build) {
                    echo "Skip temp build fw QA test ..."
                    return
                }
                def metaInfo = [:]
                metaInfo['name'] = name //UDR, UDW
                metaInfo['product'] = target_map.product //uck-g2.apq8053, udr.mt7622
                metaInfo['br'] = BRANCH_NAME
                metaInfo['is_tag'] = is_tag
                metaInfo['is_pr'] = is_pr
                metaInfo['uofSlackResp'] = uofSlackResp
                metaInfo['slackThreadId'] = slackThreadId
                metaInfo['uploadInfo'] = m.upload_info
                metaInfo['gitInfo'] = m.git_args
                /*
                product: {
                    'fw_link' : https://xxx/FW.LATEST.bin
                    'fw_url' : https://xxx/UNVRPRO.al324.v2.5.0+root.7378.66d3fa1.220408.2323.bin
                }
                */
                def fwInfo = [:]
                m.nasinfo.each { url, artifact ->
                    if (artifact.endsWith(".bin") && url.contains(target_map.product)) {
                        if(!fwInfo.containsKey(target_map.product)) {
                            fwInfo[target_map.product] = [:]
                        }
                        println(fwInfo)
                        fwInfo[target_map.product]['fw_url'] = url
                        fwInfo[target_map.product]['fw_link'] = url.replace(/$artifact/, 'FW.LATEST.bin')
                    }
                }
                metaInfo['fwInfo'] = fwInfo
                test_uof_branch(m.nasinfo, metaInfo)
                iev_qa_test(m.nasinfo, metaInfo)
                net_qa_test(m.nasinfo, metaInfo)
                tpe_qa_test(m.nasinfo, metaInfo)
            }
        ])
        build_jobs.add([
            node: 'ROS',
            name: target_map.product,
            execute_order: 1,
            resultpath: target_map.resultpath,
            additional_store: target_map.additional_store ?: [],
            artifact_dir: job_options.job_artifact_dir ?: "${env.JOB_NAME}_${env.BUILD_TIMESTAMP}_${env.BUILD_NUMBER}_${name}",
            pack_bootloader: target_map.pack_bootloader ?: 'yes',
            build_status:false,
            project_cache: (job_options.project_cache ?: false),
            project_cache_location: (job_options.project_cache_location ?: 'debbox.git'),
            upload: job_options.upload ?: false,
            dist: job_options.dist ?: "stretch",
            arch: target_map.arch ?: "arm64",
            pre_checkout_steps: { m->
                // do whatever you want before checkout step
                sh 'export'
                return true
            },
            build_steps: { m->
                stage("Pre-build ${m.name} stage") {
                    // do whatever you want before building process
                    m.build_number = env.BUILD_NUMBER
                    m.build_dir = "${m.name}-${m.build_number}"
                    m.docker_artifact_path = m.artifact_dir + '/' + m.name

                    sh "mkdir -p ${m.build_dir} ${m.docker_artifact_path}"
                    m.docker_artifact_path = sh_output("readlink -f ${m.docker_artifact_path}")
                }
                stage("Build ${m.name}") {
                    dir_cleanup("${m.build_dir}") {
                        def dockerRegistry = get_docker_registry()
                        def dockerImage
                        if (productSeries == 'NX') {
                            dockerImage = docker.image("$dockerRegistry/ubuntu:nx")
                        } else {
                            dockerImage = docker.image("$dockerRegistry/debbox-builder-cross-${m.dist}-${m.arch}:latest")
                        }
                        dockerImage.pull()
                        def docker_args = get_docker_args(m.docker_artifact_path) + " -v ${aws_rotate.get_aws_dir()}:/root/.aws:ro"
                        dockerImage.inside(docker_args) {
                            /*
                            * tag build var:
                            * TAG_NAME: unifi-cloudkey/v1.1.9
                            *
                            * pr build var:
                            * CHANGE_BRANCH: feature/unifi-core-integration
                            * CHANGE_ID: 32 (pull request ID)
                            * git_args:
                            *     user: git
                            *     site: git.uidev.tools,
                            *     repository: firmware.debbox
                            *     revision: git changeset
                            *     local_branch: feature/unifi-core-integration or unifi-cloudkey/v1.1.9
                            *     is_pr: true or false
                            *     is_tag: true or false
                            *     tag: unifi-cloudkey/v1.1.9 (TAG_NAME)
                            *     branch_name (feature/unifi-core-integration)
                            */
                            def co_map
                            // open it when we have method to modify the CloneOption without affect others option
                            // def cache_path = "${project_cache_updater.get_project_cache_dir()}/${m.project_cache_location}"
                            // scm.extensions = scm.extensions + [[$class: 'CloneOption', reference: "${cache_path}"]]
                            for(retry = 0; retry < 3; retry++) {
                                try {
                                    timeout(time: 10, unit: 'MINUTES') {
                                        co_map = checkout scm
                                    }
                                    break
                                } catch (e) {
                                    echo "Timeout during checkout scm"
                                    sh "rm -rf .git || true"
                                }
                            }

                            def url = co_map.GIT_URL
                            def git_args = git_helper.split_url(url)
                            def repository = git_args.repository
                            echo "URL: ${url} -> site: ${git_args.site} " + "owner:${git_args.owner} repo: ${repository}"
                            git_args.revision = git_helper.sha()
                            git_args.rev_num = git_helper.rev()
                            if (is_pr && is_atag) {
                                error 'Unexpected environment, cannot be both PR and TAG'
                            }
                            def ref
                            if (is_tag) {
                                ref = TAG_NAME
                                try {
                                    git_helper.verify_is_atag(ref)
                                } catch (all) {
                                    println "catch error: $all"
                                    is_atag = false
                                }
                                println "tag build: istag: $is_tag, is_atag:$is_atag"
                                git_args.local_branch = ref
                            } else if (is_pr) {
                                // use change branch as ref
                                ref = "origin/${env.CHANGE_BRANCH}"
                                git_args.local_branch = "PR-${env.CHANGE_ID}"
                            } else {
                                ref = git_helper.current_branch()
                                if (!ref || ref == 'HEAD') {
                                    ref = "origin/${BRANCH_NAME}"
                                }
                                git_args.local_branch = ref
                            }
                            // decide release build logic
                            def is_release = false
                            if (is_tag) {
                                if (is_atag) {
                                    is_release = true
                                } else {
                                    is_release = TAG_NAME.contains('release')
                                }
                                is_release = true
                            }
                            m.is_release = is_release
                            git_args.is_pr = is_pr
                            git_args.is_tag = is_tag
                            git_args.is_atag = is_atag
                            git_args.ref = ref
                            if (is_temp_build) {
                                git_args.is_temp_build = true
                                git_args.temp_dir_name = m.dist
                            }
                            m['git_args'] = git_args.clone()
                            m.upload_info = ubnt_nas.generate_buildinfo(m.git_args)
                            print m.upload_info
                            try {
                                def kcache = "${project_cache_updater.get_project_cache_dir()}/debbox-kernel.git"
                                println "kcache: $kcache"
                                withEnv(['AWS_SHARED_CREDENTIALS_FILE=/root/.aws/credentials', 'AWS_CONFIG_FILE=/root/.aws/config']) {
                                    bash "AWS_PROFILE=default GITCACHE=${kcache} PACK_BOOTLOADER=${m.pack_bootloader} make PRODUCT=${m.name} DIST=${m.dist} RELEASE_BUILD=${is_release} 2>&1 | tee make.log"
                                }
                                println "Build completed: $m.name"
                                sh 'cp make.log /root/artifact_dir/'
                                sh "cp -r build/${m.resultpath}/dist/* /root/artifact_dir/"
                                sh "cp build/${m.resultpath}/bootstrap/root/usr/lib/version /root/artifact_dir/"
                                if (productSeries == 'UNVR' || name.contains('UNVR')) {
                                    sh "cp -r build/${m.resultpath}/image/unvr-image/uImage /root/artifact_dir/"
                                    sh "cp -r build/${m.resultpath}/image/unvr-image/vmlinux /root/artifact_dir/"
                                    sh "cp -r build/${m.resultpath}/image/unvr-image/vmlinuz-* /root/artifact_dir/"
                                }
                                m.additional_store.each { additional_file ->
                                    sh "cp -r build/${m.resultpath}/$additional_file /root/artifact_dir/"
                                }
                            }
                            catch (Exception e) {
                                // Due to we have the build error, remove all at here
                                sh "rm -rf /root/artifact_dir"
                                sh "rm -rf build"
                                throw e
                            }
                            finally {
                                // In order to cleanup the dl and build directory
                                sh 'chmod -R 777 .'
                                deleteDir()
                            }
                        }
                    }
                }
                return true
            },
            archive_steps: { m->
                stage('Upload to server') {
                    if (m.upload && m.containsKey('upload_info')) {
                        // upload "TO" path
                        def upload_path = m.upload_info.path.join('/')
                        def temp_path = ubnt_nas.get_temp_dir() + upload_path
                        def latest_path = ubnt_nas.get_temp_dir() + m.upload_info.latest_path.join('/')
                        ubnt_nas.upload(m.docker_artifact_path, temp_path, latest_path)
                        job_shared['job_upload_info'] = [:]
                        job_shared['job_upload_info']['upload_src_path'] = temp_path
                        job_shared['job_upload_info']['upload_dst_path'] = upload_path
                    }
                }
            },
            archive_cleanup_steps: { m->
                stage('Cleanup archive') {
                    try {
                        dir_cleanup("${m.docker_artifact_path}") {
                            deleteDir()
                        }
                    }
                    catch (Exception e) {
                    // do nothing
                    }
                }
            },
            post_steps: { m->
                product_shared = m.clone()
            }
        ])

    }
    build_jobs.add([
        node: 'ROS',
        execute_order: 2,
        name: 'upload',
        build_status:false,
        build_steps: { m->
            stage('Move to server') {
                if(job_shared.containsKey('job_upload_info')) {
                    def src_path = job_shared.job_upload_info['upload_src_path']
                    def dst_path = job_shared.job_upload_info['upload_dst_path']
                    m.nasinfo = ubnt_nas.move(src_path, dst_path)
                    job_shared['nasinfo'] = m.nasinfo.clone()
                }
            }
        }
    ])
    return build_jobs
}
/*
 * A general package build function
 * job_options must contains the following params (key-value)
 * name: project name
 * dist: path of output dir  (default is "dist")
 *
 */
def debpkg(Map job_options, configs=['stretch/all']) {
    verify_required_params('debpkg', job_options, ['name'])

    return [[
        node: job_options.node ?: 'debfactory',
        name: job_options.name,
        artifact_dir: job_options.artifact_dir ?: "${env.JOB_BASE_NAME}_${env.BUILD_TIMESTAMP}_${env.BUILD_NUMBER}",
        dist: job_options.dist ?: 'dist',
        execute_order: 1,
        upload: job_options.upload ?: false,
        non_cross: job_options.non_cross ?: false,
        pre_checkout_steps: { m->
            m.build_dir = "${m.name}-${env.BUILD_NUMBER}-${env.BUILD_TIMESTAMP}"
        },
        build_steps: { m ->
            def uid, gid

            (uid, gid) = get_ids()
            sh "mkdir -p ${m.artifact_dir}"
            m.absolute_artifact_dir = sh_output("readlink -f ${m.artifact_dir}")

            ws("${m.build_dir}") {
                def co_map = checkout scm
                def url = co_map.GIT_URL
                def git_args = git_helper.split_url(url)
                def repository = git_args.repository
                echo "URL: ${url} -> site: ${git_args.site} " + "owner:${git_args.owner} repo: ${repository}"
                git_args.revision = git_helper.sha()
                git_args.rev_num = git_helper.rev()

                def is_pr = env.getProperty('CHANGE_ID') != null
                def is_atag = env.getProperty('TAG_NAME') != null
                if (is_pr && is_atag) {
                    error 'Unexpected environment, cannot be both PR and TAG'
                }
                def ref
                if (is_atag) {
                    ref = TAG_NAME
                    git_helper.verify_is_atag(ref)
                    git_args.local_branch = ref
                } else {
                    ref = git_helper.current_branch()
                    if (!ref || ref == 'HEAD') {
                        ref = "origin/${BRANCH_NAME}"
                    } else {
                        git_args.local_branch = ref
                    }
                }
                git_args.is_pr = is_pr
                git_args.is_tag = is_atag
                git_args.ref = ref
                m['git_args'] = git_args.clone()
                m.upload_info = ubnt_nas.generate_buildinfo(m.git_args)
                print m.upload_info
                configs.each { config ->
                    def (distribution, arch) = config.split('/')
                    def builder = "$distribution-arm64"
                    def extra = "DIST=${distribution}"

                    if (arch != 'all') {
                        builder = "${distribution}-${arch}"
                        extra += " DEB_TARGET_ARCH=${arch}"
                    }

                    def dockerRegistry = get_docker_registry()
                    def dockerImage = docker.image("$dockerRegistry/debbox-builder-${m.non_cross ? 'qemu' : 'cross'}-${builder}:latest")
                    dockerImage.pull()
                    dockerImage.inside(get_docker_args(m.absolute_artifact_dir)) {
                        try {
                            bash "make package RELEASE_BUILD=${is_atag} ${extra} 2>&1 | tee make.log"
                        } catch (Exception e) {
                            throw e
                        } finally {
                            sh "mkdir -p /root/artifact_dir/${distribution}"
                            sh "cp -rT ${m.dist} /root/artifact_dir/${distribution} || true"
                            sh "mv make.log /root/artifact_dir/${distribution} || true"
                            sh "echo gid=$gid uid:$uid"
                            sh "chown $uid:$gid -R /root/artifact_dir"
                        }
                    }
                    return true
                }
            }
        },
        archive_steps: { m ->
            stage("Artifact ${m.name}") {
                def rc = sh script: "ls -1qA ${m.artifact_dir} | grep -q .", returnStatus: true
                if (rc == 0) {
                    archiveArtifacts artifacts: "${m.artifact_dir}/**"
                    if (m.upload && m.containsKey('upload_info')) {
                        def upload_path = m.upload_info.path.join('/')
                        def latest_path = m.upload_info.latest_path.join('/')
                        def ref_path = m.upload_info.ref_path.join('/')

                        if (m.git_args.is_tag) {
                            writeFile file:'pkg-arrange.py', text:libraryResource("pkg-arrange.py")
                            sh "mkdir -p ${ubnt_nas.get_nasdir()}/${ref_path}/../.makefile"
                            sh "mkdir -p ${ubnt_nas.get_nasdir()}/${ref_path}/../.makefile.bkp"
                            def makefile_path = sh_output("realpath ${ubnt_nas.get_nasdir()}/${ref_path}/../.makefile")
                            def makefile_bkp_path = sh_output("realpath ${ubnt_nas.get_nasdir()}/${ref_path}/../.makefile.bkp")
                            sh "python3 ./pkg-arrange.py " +
                                "-o ${makefile_path} " +
                                "-c ${makefile_path} " +
                                "-u ${ubnt_nas.get_nasdomain()}/${upload_path} " +
                                "${m.artifact_dir}/"
                        }

                        println "upload: $upload_path, artifact_path: ${m.artifact_dir}/* latest_path: $latest_path"
                        ubnt_nas.upload("${m.artifact_dir}/*", upload_path, latest_path)
                    }
                    sh "rm -rf ${m.artifact_dir}"
                }
            }
        },
        archive_cleanup_steps: { m ->
            stage("Artifact cleanup ${m.name}") {
                dir_cleanup("${m.build_dir}") {
                    echo "cleanup artifact ${m.build_dir}"
                }
            }
        }
    ]]
}

/*
 * A amaz_alpinev2_boot builder function
 * job_options must contains the following params (key-value)
 * name: project name
 * dist: path of output dir  (default is "dist")
 * amaz_alpinev2_boot_config: a map include model:hw_ver pair
 * build: 1. build_series all 2. build projectSeries
 */

def amaz_alpinev2_boot_builder(String build_target, Map job_options=[:], Map build_config=[:]) {
    def build_jobs = []
    def config = [:]

    verify_required_params('amaz_alpinev2_boot_builder', job_options, ['name'])

    // amaz_alpinev2_boot_config = [1:'ubnt_nas-5', 2:'ubnt_nas_pro-2', 3:'ubnt_nas_ai-3', 4:'ubnt_unvr_all-1']
    amaz_alpinev2_boot_config = [1:'ubnt_unvr_all-1']

    if (build_config.size() > 0) {
        if (build_config.containsKey(build_target)) {
            config.put(build_target, build_config[build_target])
        } else {
            config = build_config.clone()
        }
    } else {
        if (amaz_alpinev2_boot_config.containsKey(build_target)) {
            config.put(build_target, amaz_alpinev2_boot_config[build_target])
        } else {
            config = amaz_alpinev2_boot_config.clone()
        }
    }
    echo "build $build_target $config"
    config.each { k, v->
        (model, hw_ver) = v.split('-')
        build_jobs.add([
            node: job_options.node ?: 'debfactory',
            name: "$model$hw_ver",
            model: model,
            hw_ver: hw_ver,
            artifact_dir: job_options.artifact_dir ?: "${env.JOB_BASE_NAME}_${env.BUILD_TIMESTAMP}_${env.BUILD_NUMBER}",
            dist: 'output',
            execute_order: 1,
            upload: job_options.upload ?: false,
            pre_checkout_steps: { m->
                m.artifact_prefix = "${m.model}-${m.hw_ver}"
                m.build_dir = "${m.name}-${env.BUILD_NUMBER}-${env.BUILD_TIMESTAMP}"
            },
            build_steps: { m ->
                def uid, gid
                def deleteWsPath

                (uid, gid) = get_ids()
                sh "mkdir -p ${m.artifact_dir}/${m.artifact_prefix}"
                m.absolute_artifact_dir = sh_output("readlink -f ${m.artifact_dir}/${m.artifact_prefix}")

                ws("${m.build_dir}") {
                    deleteWsPath = env.WORKSPACE
                    def dockerRegistry = get_docker_registry()
                    def dockerImage = docker.image("$dockerRegistry/debbox-builder-cross-stretch-arm64:latest")
                    dockerImage.pull()
                    dockerImage.inside(get_docker_args(m.absolute_artifact_dir)) {
                        def co_map = checkout scm
                        sh 'git submodule update --init --recursive'
                        def url = co_map.GIT_URL
                        def git_args = git_helper.split_url(url)
                        def repository = git_args.repository
                        echo "URL: ${url} -> site: ${git_args.site} " + "owner:${git_args.owner} repo: ${repository}"
                        git_args.revision = git_helper.sha()
                        git_args.rev_num = git_helper.rev()

                        def is_pr = env.getProperty('CHANGE_ID') != null
                        def is_atag = env.getProperty('TAG_NAME') != null
                        def is_tag = env.getProperty('TAG_NAME') != null
                        if (is_pr && is_atag) {
                            error 'Unexpected environment, cannot be both PR and TAG'
                        }
                        def ref
                        if (is_atag) {
                            ref = TAG_NAME
                            try {
                                git_helper.verify_is_atag(ref)
                            } catch (all) {
                                println "catch error: $all"
                                is_atag = false
                            }
                            println "tag build: istag: $is_tag, is_atag:$is_atag"
                            git_args.local_branch = ref
                        } else {
                            ref = git_helper.current_branch()
                            if (!ref || ref == 'HEAD') {
                                ref = "origin/${BRANCH_NAME}"
                            } else {
                                git_args.local_branch = ref
                            }
                        }
                        git_args.is_pr = is_pr
                        git_args.is_tag = is_tag
                        git_args.is_atag = is_atag
                        git_args.ref = ref
                        m['git_args'] = git_args.clone()
                        m.upload_info = ubnt_nas.generate_buildinfo(m.git_args)
                        print m.upload_info
                        try {
                            bash "./release.sh ${m.model} ${m.hw_ver} 2>&1 | tee make.log"
                        }
                        catch (Exception e) {
                            throw e
                        }
                        finally {
                            sh 'chmod -R 777 .'
                            sh 'mkdir -p /root/artifact_dir/dtb'
                            sh "cp -rT ${m.dist} /root/artifact_dir || true"
                            sh "cp -r ${m.dist}/input/*.dtb /root/artifact_dir/dtb || true"
                            sh 'mv make.log /root/artifact_dir'
                            sh "ln -srf /root/artifact_dir/boot.img /root/artifact_dir/boot-${git_helper.short_sha()}.img"
                            sh "md5sum /root/artifact_dir/boot.img /root/artifact_dir/dtb/* > /root/artifact_dir/md5sum.txt"
                            sh 'rm -rf /root/artifact_dir/input || true'
                            sh "echo gid=$gid uid:$uid"
                            sh "chown $uid:$gid -R /root/artifact_dir"
                        }
                    }

                }
                dir_cleanup("${deleteWsPath}") {
                    echo "cleanup ws ${deleteWsPath}"
                }

                return true
            },
            archive_steps: { m ->
                stage("Artifact ${m.name}") {
                    if (fileExists("$m.artifact_dir/${m.artifact_prefix}/make.log")) {
                        archiveArtifacts artifacts: "${m.artifact_dir}/${m.artifact_prefix}/**"
                        if (m.containsKey('upload_info')) {
                            def upload_path = m.upload_info.path.join('/')
                            def latest_path = m.upload_info.latest_path.join('/')
                            println "upload: $upload_path , artifact_path: ${m.artifact_dir}/${m.artifact_prefix}, latest_path: $latest_path"
                            if (m.upload) {
                                ubnt_nas.upload("${m.artifact_dir}/${m.artifact_prefix}", upload_path, latest_path)
                            }
                            sh "rm -rf ${m.artifact_dir}/${m.artifact_prefix}"
                        }
                    }
                }
            }
        ])
    }

    return build_jobs
}

def mt7622_boot_builder(String build_target, Map job_options=[:], Map build_config=[:]) {
    def build_jobs = []
    def targets = []

    verify_required_params('mt7622_boot_builder', job_options, ['name'])

    if (build_config.containsKey('targets')) {
        targets = build_config['targets']
    }

    targets.each { target ->
        build_jobs.add([
            node: job_options.node ?: 'debfactory',
            name: target,
            artifact_dir: job_options.artifact_dir ?: "${env.JOB_BASE_NAME}_${env.BUILD_TIMESTAMP}_${env.BUILD_NUMBER}",
            dist: 'u-boot-mtk.bin',
            execute_order: 1,
            upload: job_options.upload ?: false,
            pre_checkout_steps: { m->
                m.artifact_prefix = target
                m.build_dir = "${target}-${env.BUILD_NUMBER}-${env.BUILD_TIMESTAMP}"
            },
            build_steps: { m ->
                def deleteWsPath
                def uid, gid

                (uid, gid) = get_ids()
                sh "mkdir -p ${m.artifact_dir}/${m.artifact_prefix}"
                m.absolute_artifact_dir = sh_output("readlink -f ${m.artifact_dir}/${m.artifact_prefix}")

                ws("${m.build_dir}") {
                    deleteWsPath = env.WORKSPACE
                    def dockerRegistry = get_docker_registry()
                    def dockerImage = docker.image("$dockerRegistry/debbox-builder-cross-stretch-arm64:latest")
                    dockerImage.pull()
                    dockerImage.inside(get_docker_args(m.absolute_artifact_dir)) {
                        def co_map = checkout scm
                        def url = co_map.GIT_URL
                        def git_args = git_helper.split_url(url)
                        def repository = git_args.repository
                        echo "URL: ${url} -> site: ${git_args.site} " + "owner:${git_args.owner} repo: ${repository}"
                        git_args.revision = git_helper.sha()
                        git_args.rev_num = git_helper.rev()

                        def is_pr = env.getProperty('CHANGE_ID') != null
                        def is_atag = env.getProperty('TAG_NAME') != null
                        def is_tag = env.getProperty('TAG_NAME') != null
                        if (is_pr && is_atag) {
                            error 'Unexpected environment, cannot be both PR and TAG'
                        }
                        def ref
                        if (is_atag) {
                            ref = TAG_NAME
                            try {
                                git_helper.verify_is_atag(ref)
                            } catch (all) {
                                println "catch error: $all"
                                is_atag = false
                            }
                            println "tag build: istag: $is_tag, is_atag:$is_atag"
                            git_args.local_branch = ref
                        } else {
                            ref = git_helper.current_branch()
                            if (!ref || ref == 'HEAD') {
                                ref = "origin/${BRANCH_NAME}"
                            } else {
                                git_args.local_branch = ref
                            }
                        }
                        git_args.is_pr = is_pr
                        git_args.is_tag = is_tag
                        git_args.is_atag = is_atag
                        git_args.ref = ref
                        m['git_args'] = git_args.clone()
                        m.upload_info = ubnt_nas.generate_buildinfo(m.git_args)
                        print m.upload_info
                        try {
                            bash "BUILD_RELEASE=1 BUILD_DEBUG=0 ./build_ubnt_mt7622.sh ${target} | tee make.log"
                        }
                        catch (Exception e) {
                            throw e
                        }
                        finally {
                            sh 'chmod -R 777 .'
                            sh "cp ${m.dist} /root/artifact_dir || true"
                            sh 'mv make.log /root/artifact_dir'
                            sh 'rm -rf /root/artifact_dir/input || true'
                            sh "ln -srf /root/artifact_dir/u-boot-mtk.bin /root/artifact_dir/u-boot-mtk-${git_helper.short_sha()}.bin"
                            sh "md5sum /root/artifact_dir/u-boot-mtk.bin > /root/artifact_dir/md5sum.txt"
                            sh "echo gid=$gid uid:$uid"
                            sh "chown $uid:$gid -R /root/artifact_dir"
                        }
                    }
                }
                dir_cleanup("${deleteWsPath}") {
                    echo "cleanup ws ${deleteWsPath}"
                }

                return true
            },
            archive_steps: { m ->
                stage("Artifact ${target}") {
                    if (fileExists("$m.artifact_dir/${m.artifact_prefix}/make.log")) {
                        archiveArtifacts artifacts: "${m.artifact_dir}/${m.artifact_prefix}/**"
                        if (m.containsKey('upload_info')) {
                            def upload_path = m.upload_info.path.join('/')
                            def latest_path = m.upload_info.latest_path.join('/')
                            println "upload: $upload_path , artifact_path: ${m.artifact_dir}/${m.artifact_prefix}, latest_path: $latest_path"
                            if (m.upload) {
                                ubnt_nas.upload("${m.artifact_dir}/${m.artifact_prefix}", upload_path, latest_path)
                            }
                            sh "rm -rf ${m.artifact_dir}/${m.artifact_prefix}"
                        }
                    }
                }
            }
        ])
    }

    return build_jobs
}

def preload_image_builder(String productSeries, Map job_options=[:], Map build_series=[:]) {
    def build_jobs = []
    echo "build $productSeries"

    build_jobs.add([
        node: job_options.node ?: 'debfactory',
        name: job_options.name,
        artifact_dir: job_options.artifact_dir ?: "${env.JOB_BASE_NAME}_${env.BUILD_TIMESTAMP}_${env.BUILD_NUMBER}/arm64",
        execute_order: 1,
        upload: job_options.upload ?: false,
        pre_checkout_steps: { m->
            m.build_dir = "${m.name}-${env.BUILD_NUMBER}-${env.BUILD_TIMESTAMP}"
            m.upload_info = [
                path: ["${m.name}", 'arm64', "${m.build_dir}"],
                latest_path: ["${m.name}", 'arm64', 'latest']
            ]
        },
        build_steps: { m ->
            sh "mkdir -p ${m.artifact_dir}"
            m.artifact_dir_absolute_path = sh_output("readlink -f ${m.artifact_dir}")
            def deleteWsPath
            ws("${m.build_dir}") {
                checkout scm
                deleteWsPath = env.WORKSPACE
                bootload_path = "/home/dio/builder/amaz-alpinev2-boot/${env.ub_path}/ubnt_unvr_all-1/boot.img"
                unvr4_fcd_uImage = "/home/dio/builder/firmware.debbox/tags/unifi-nvr/${env.fw_tag_version}/latest/unifi-nvr4-fcd.alpine/uImage"
                unvr4_preload = "spi-unifi-nvr4-${env.fw_tag_version}-${env.BUILD_TIMESTAMP}.bin"
                unvrpro_fcd_uImage = "/home/dio/builder/firmware.debbox/tags/unifi-nvr/${env.fw_tag_version}/latest/unifi-nvr-pro-fcd.alpine/uImage"
                unvrpro_preload = "spi-unifi-nvr-pro-${env.fw_tag_version}-${env.BUILD_TIMESTAMP}.bin"
                unvrai_fcd_uImage = "/home/dio/builder/firmware.debbox/tags/unifi-nvr/${env.fw_tag_version}/latest/unifi-nvr-ai-fcd.alpine/uImage"
                unvrai_preload = "spi-unifi-nvr-ai-${env.fw_tag_version}-${env.BUILD_TIMESTAMP}.bin"

                bootload_path = sh_output("realpath $bootload_path")
                unvr4_fcd_uImage = sh_output("realpath $unvr4_fcd_uImage")
                unvrpro_fcd_uImage = sh_output("realpath $unvrpro_fcd_uImage")
                unvrai_fcd_uImage = sh_output("realpath $unvrai_fcd_uImage")
                try {
                    bash "./preload_image.py $bootload_path $unvr4_fcd_uImage $unvr4_preload ea1a | tee -a make.log"
                    bash "./preload_image.py $bootload_path $unvrpro_fcd_uImage $unvrpro_preload ea20 | tee -a make.log"
                    bash "./preload_image.py $bootload_path $unvrai_fcd_uImage $unvrai_preload ea21 | tee -a make.log"
                    sh "mv $unvr4_preload $unvrpro_preload $unvrai_preload ${m.artifact_dir_absolute_path}"
                    sh "mv make.log ${m.artifact_dir_absolute_path}"
                }
                catch (Exception e) {
                    throw e
                }
                finally {
                    dir_cleanup("${deleteWsPath}") {
                        echo "cleanup ws ${deleteWsPath}"
                        deleteDir()
                    }
                }
            }
            return true
        },
        archive_steps: { m ->
            stage("Artifact ${m.name}") {
                if (fileExists("${m.artifact_dir}/make.log")) {
                    if (m.containsKey('upload_info')) {
                        def upload_path = m.upload_info.path.join('/')
                        def latest_path = m.upload_info.latest_path.join('/')
                        println "upload: $upload_path ,artifact_path: ${m.artifact_dir}/* latest_path: $latest_path"
                        if (m.upload) {
                            ubnt_nas.upload("${m.artifact_dir}/*", upload_path, latest_path)
                        }
                    }
                    archiveArtifacts artifacts: "${m.artifact_dir}/**"
                    sh "rm -rf ${m.artifact_dir}"
                }
            }
        }
    ])
    return build_jobs
}

def ustd_checker(String productSeries, Map job_options=[:], Map build_series=[:]) {
    def build_jobs = []

    verify_required_params('ustd', job_options, ['name'])

    build_jobs.add([
        node: job_options.node ?: 'debfactory',
        name: job_options.name,
        artifact_dir: job_options.artifact_dir ?: "${env.JOB_BASE_NAME}_${env.BUILD_TIMESTAMP}_${env.BUILD_NUMBER}",
        dist: job_options.dist ?: 'build/dist',
        execute_order: 1,
        upload: job_options.upload ?: false,
        non_cross: job_options.non_cross ?: false,
        pre_checkout_steps: { m->
            m.build_dir = "${m.name}-${env.BUILD_NUMBER}-${env.BUILD_TIMESTAMP}"
        },
        build_steps: { m ->
            def return_status = false
            def uid, gid

            (uid, gid) = get_ids()
            sh "mkdir -p ${m.artifact_dir}/stretch/arm64"
            m.absolute_artifact_dir = sh_output("readlink -f ${m.artifact_dir}/stretch/arm64")

            dir("${m.build_dir}/ustd") {
                stage('checkout source') {
                    sh 'pwd'
                    checkout scm
                    sh 'ls -alhi'
                }
                stage('pylint check error') {
                    def dockerRegistry = get_docker_registry()
                    def dockerImage = docker.image("$dockerRegistry/debbox-builder-cross-stretch-arm64:latest")
                    dockerImage.pull()
                    dockerImage.inside(get_docker_args(m.absolute_artifact_dir)) {
                        sh 'apt-get update && apt-get install python3-systemd python3-cryptography -y'
                        sh 'pylint3 -E ustd/*.py ustd/*/*.py'
                    }
                }
            }

            dir("${m.build_dir}/debfactory") {
                def dockerRegistry = get_docker_registry()
                dockerImage = docker.image("$dockerRegistry/debbox-builder-qemu-stretch-arm64:latest")
                dockerImage.pull()
                stage('checkout debfactory helper') {
                    git branch: 'master',
                        credentialsId: 'ken.lu-ssh',
                        url: 'git@github.com:ubiquiti/debfactory.git'
                    sh 'mkdir -p ./source && cp -rl ../ustd ./source/ustd'
                }

                stage('build deb package') {
                    dockerImage.pull()
                    dockerImage.inside(get_docker_args(m.absolute_artifact_dir)) {
                        try {
                            bash 'make ARCH=arm64 DIST=stretch BUILD_DEPEND=yes ustd 2>&1 | tee make.log'
                            return_status = true
                        }
                        catch (Exception e) {
                            throw e
                        }
                        finally {
                            sh 'chmod -R 777 .'
                            sh "cp -rT ${m.dist} /root/artifact_dir || true"
                            sh 'mv make.log /root/artifact_dir || true'
                            sh "echo gid=$gid uid:$uid"
                            sh "chown $uid:$gid -R /root/artifact_dir"
                        }
                    }
                }
            }
            dir_cleanup("${m.build_dir}/debfactory") {
            }
            dir_cleanup("${m.build_dir}/ustd") {
            }
            return return_status
        },
        archive_steps: { m ->
            stage("Artifact ${m.name}") {
                archiveArtifacts artifacts: "${m.artifact_dir}/**"
                sh "rm -rf ${m.artifact_dir}"
            }
        }
    ])

    return build_jobs
}
