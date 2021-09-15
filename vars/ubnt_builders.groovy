def bash(String cmd) { sh("#!/usr/bin/env bash\nset -euo pipefail\n${cmd}") }

def get_docker_registry() {
    def dockerRegistry='http://registry.ubnt.com.tw:6666'
    return dockerRegistry
}

def get_job_options(String project) {
    def options = [
        debbox_builder: [
            job_artifact_dir: "${env.JOB_NAME}_${env.BUILD_TIMESTAMP}_${env.BUILD_NUMBER}",
            node: 'debbox',
            upload: true
        ],
        debfactory_builder:[
            job_artifact_dir: "${env.JOB_BASE_NAME}_${env.BUILD_TIMESTAMP}_${env.BUILD_NUMBER}",
            node: 'fwteam',
            build_archs: ['arm64'],
            upload: true
        ],
        debfactory_non_cross_builder:[
            job_artifact_dir: "${env.JOB_BASE_NAME}_${env.BUILD_TIMESTAMP}_${env.BUILD_NUMBER}",
            node: 'fwteam',
            build_archs: ['arm64'],
            upload: true,
            non_cross: true
        ],
        analytic_report_builder:[
            name: 'analytic_report',
            node: 'fwteam',
            upload: true,
            non_cross: true
        ],
        disk_smart_mon_builder:[
            name: 'disk_smart_mon',
            node: 'fwteam',
            upload: true
        ],
        disk_quota_builder:[
            name: 'disk_quota',
            node: 'fwteam',
            upload: true
        ],
        debbox_base_files_builder:[
            name: 'debbox_base',
            node: 'fwteam',
            upload: true
        ],
        cloudkey_apq8053_initramfs_builder:[
            name: 'cloudkey_apq8053_initramfs',
            node: 'fwteam',
            upload: true
        ],
        ubnt_archive_keyring_builder:[
            name: 'ubnt_archive_keyring',
            node: 'fwteam',
            upload: true
        ],
        ubnt_zram_swap_builder:[
            name: 'ubnt_zram_swap',
            node: 'fwteam',
            upload: true
        ],
        ubnt_tools_builder:[
            name: 'ubnt_tools',
            node: 'fwteam',
            upload: true
        ],
        amaz_alpinev2_boot_builder:[
            name: 'amaz_alpinev2_boot',
            node: 'fwteam',
            upload: true
        ],
        mt7622_boot_builder:[
            name: 'mt7622_boot',
            node: 'fwteam',
            upload: true
        ],
        preload_image_builder:[
            name: 'preload_image',
            node: 'fwteam',
            upload: true
        ],
        ustd_checker:[
            name: 'ustd',
            node: 'fwteam',
            upload: false
        ]
    ]

    return options.get(project, [:])
}

def get_docker_args(artifact_dir) {
    return '-u 0 --privileged=true ' +
        "-v $HOME/.jenkinbuild/.ssh:/root/.ssh:ro " +
        "-v ${artifact_dir}:/root/artifact_dir:rw " +
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
    def build_dist = 'stretch'
    if (job_options.containsKey('dist')) {
        build_dist = job_options.dist
    }

    job_options.build_archs.each { arch->
        build_jobs.add([
            node: job_options.node ?: 'fwteam',
            name: 'debfactory',
            dist: "$build_dist",
            resultpath: "build/dist",
            execute_order: 1,
            artifact_dir: job_options.job_artifact_dir ?: "${env.JOB_BASE_NAME}_${env.BUILD_TIMESTAMP}_${env.BUILD_NUMBER}",
            build_record: job_options.build_record ?: false,
            arch: arch,
            build_status:false,
            upload: job_options.upload ?: false,
            non_cross: job_options.non_cross ?: false,
            build_steps: { m->
                sh 'export'
                def buildPackages = []
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

                        if (m.non_cross) {
                            sh_output("./pkg-tools.py -nc -r ${pkg_tools_params}").tokenize('\n').each {
                                buildPackages << it
                            }
                        } else {
                            sh_output("./pkg-tools.py -r ${pkg_tools_params}").tokenize('\n').each {
                                buildPackages << it
                            }
                        }
                        m.buildPackages = buildPackages
                        println "Packages to be built: $buildPackages"

                        sh 'ls -lahi'
                        println "resultpath: $m.resultpath"
                        println "artifact_dir: $m.artifact_dir"
                    }
                }
                stage("build $m.name") {
                    if (buildPackages.size() == 0) {
                        return
                    }
                    dir_cleanup("$m.build_dir") {
                        try {
                            def dockerImage = docker.image("dio-debfactory-${m.dist}-builder:latest")
                            if (m.non_cross) {
                                dockerImage = docker.image("debbox-builder-qemu-${m.dist}-arm64:latest")
                            }
                            dockerImage.inside(get_docker_args(m.absolute_artifact_dir)) {
                                def build_targets = buildPackages.join(' ')
                                sh "make ARCH=$m.arch DIST=$m.dist BUILD_DEPEND=yes ${build_targets} 2>&1"

                                def upload_prefix = m.upload_info.path.join('/')

                                // Intentionally use .makefile to avoid uploading to nas job dir
                                sh "mkdir -p /root/artifact_dir/.makefile"
                                writeFile file:'pkg-arrange.py', text:libraryResource("pkg-arrange.py")
                                sh "python3 ./pkg-arrange.py -o /root/artifact_dir/.makefile -d ${m.dist} -u ${ubnt_nas.get_nasdomain()}/${upload_prefix} ${m.resultpath}/"
                                buildPackages.each { pkg ->
                                    sh "test ! -d ${m.resultpath}/${pkg} || cp -rf ${m.resultpath}/${pkg} /root/artifact_dir/"
                                }
                                sh 'make distclean 2>&1'
                            }
                            m.build_status = true
                        }
                        catch (Exception e) {
                            m.build_status = false
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
                if (m.buildPackages.size() == 0) {
                    return
                }
                stage("Artifact ${m.name}") {
                    archiveArtifacts artifacts: "${m.artifact_dir}/**"
                }
                stage('Upload to server') {
                    if (m.upload && m.containsKey('upload_info')) {
                        def upload_prefix = m.upload_info.path.join('/')
                        def latest_prefix = m.upload_info.latest_path.join('/')
                        ubnt_nas.upload(m.absolute_artifact_dir + '/*' , upload_prefix, latest_prefix, true)
                        if (m.build_record) {
                            def ref_path = m.upload_info.ref_path.join('/')
                            ref_path = "${ubnt_nas.get_nasdir()}/${ref_path}"
                            sh "rsync -av ${m.absolute_artifact_dir}/.makefile/ ${ref_path}/.makefile/"
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
    def semaphore = 0
    def upload_semaphore = 0
    def total_job = 0
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

    build_product.each { name, target_map ->
        if (is_tag && productSeries == 'UNIFICORE' && !TAG_NAME.startsWith(target_map.tag_prefix)) {
            return
        }
        lock("debbox_builder-${env.BUILD_NUMBER}") {
           total_job = total_job + 1
           println "total_job: $total_job"
        }
        build_jobs.add([
            node: is_pr ? 'deb-img' : (job_options.node ?: 'debbox'),
            name: target_map.product,
            resultpath: target_map.resultpath,
            additional_store: target_map.additional_store ?: [],
            execute_order: 1,
            artifact_dir: job_options.job_artifact_dir ?: "${env.JOB_NAME}_${env.BUILD_TIMESTAMP}_${env.BUILD_NUMBER}_${name}",
            pack_bootloader: target_map.pack_bootloader ?: 'yes',
            build_status:false,
            upload: job_options.upload ?: false,
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
                        docker.withRegistry(dockerRegistry) {
                            def dockerImage
                            if (productSeries == 'NX') {
                                dockerImage = docker.image('ubuntu:nx')
                            } else {
                                dockerImage = docker.image('debbox-builder-cross-stretch-arm64:latest')
                            }
                            dockerImage.pull()
                            def docker_args = get_docker_args(m.docker_artifact_path) + " -v $HOME/.jenkinbuild/.aws:/root/.aws:ro"
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
                                def co_map = checkout scm
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
                                m['git_args'] = git_args.clone()
                                m.upload_info = ubnt_nas.generate_buildinfo(m.git_args)
                                print m.upload_info
                                try {
                                    withEnv(['AWS_SHARED_CREDENTIALS_FILE=/root/.aws/credentials', 'AWS_CONFIG_FILE=/root/.aws/config']) {
                                        bash "AWS_PROFILE=default PACK_BOOTLOADER=${m.pack_bootloader} make PRODUCT=${m.name} RELEASE_BUILD=${is_release} 2>&1 | tee make.log"
                                    }
                                    lock("debbox_builder-${env.BUILD_NUMBER}") {
                                        semaphore = semaphore + 1
                                        println "semaphore: $semaphore"
                                    }
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
                                    timeout(time: 2, unit: 'HOURS') {
                                        waitUntil {
                                            semaphore == total_job
                                        }
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
                }
                return true
            },
            archive_steps: { m->
                stage('Upload to server') {
                    if (m.upload && m.containsKey('upload_info')) {
                        def upload_path = m.upload_info.path.join('/')
                        def latest_path = m.upload_info.latest_path.join('/')
                        m.nasinfo = ubnt_nas.upload(m.docker_artifact_path, upload_path, latest_path)
                    }
                    lock("debbox_builder-${env.BUILD_NUMBER}") {
                        upload_semaphore = upload_semaphore + 1
                        println "upload_semaphore: $upload_semaphore"
                    }
                    timeout(time: 1, unit: 'HOURS') {
                        waitUntil {
                            upload_semaphore == total_job
                        }
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
            qa_test_steps: { m->
                if (m.name.contains('fcd')) {
                    return
                }
                def url_domain = 'http://tpe-judo.rad.ubnt.com/build'
                if (m.containsKey('upload_info')) {
                    def upload_path = m.upload_info.path.join('/')
                    def relative_path = "${upload_path}/${m.name}/FW.LATEST.bin"
                    def fw_path = ubnt_nas.get_fw_linkpath(relative_path)
                    def url = "${url_domain}/${fw_path}"
                    echo "url: $url"

                    if (name == 'UDMPROSE' || name == 'UDR') {
                        withCredentials([string(
                            credentialsId: 'IEV_JENKINS_TOKEN',
                            variable:'jobtoken')]) {
                            def HOST="kyiv-vega.rad.ubnt.com"
                            def JOB="Udm_FW_Dispatcher"
                            def job_info = m.upload_info.path.join('_')
                            def fw_name = "${m.git_args.local_branch}.${m.git_args.rev_num}"
                            def data = "BRANCH=${m.git_args.local_branch}" +
                                "&FW_NAME=${fw_name}" +
                                "&FW_DIR=${m.upload_info.dir_name}" +
                                "&BUILD_TYPE=${target_map.product}" +
                                "&FW_VERSION=${job_info}" +
                                "&FW_URL=${url_domain}/${relative_path}" +
                                "&FW_COMMIT=${m.git_args.revision}"
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
                    if (name == 'UCKP') {
                        withCredentials([string(
                                credentialsId: 'NET_JENKINS_TOKEN',
                                variable: 'jobtoken')]) {
                            def HOST = "jenkins.network-controller-prod.a.uidev.tools"
                            def JOB = "unifi-network-be-e2e-tests/network-e2e-cloudkey-firmware-trigger"
                            def job_info = m.upload_info.path.join('_')
                            def fw_name = "${m.git_args.local_branch}.${m.git_args.rev_num}"
                            def data = "BRANCH=${m.git_args.local_branch}" +
                                    "&FW_NAME=${fw_name}" +
                                    "&FW_DIR=${m.upload_info.dir_name}" +
                                    "&BUILD_TYPE=${target_map.product}" +
                                    "&FW_VERSION=${job_info}" +
                                    "&FW_URL=${url}" +
                                    "&FW_COMMIT=${m.git_args.revision}"
                            print data
                            sh "curl -k -d \"${data}\" \"https://${HOST}/buildByToken/buildWithParameters/build?job=${JOB}&token=${jobtoken}\""
                        }
                    }

                    // skip UDMPSE, UDR and UDW test
                    if (name == 'UDMPROSE' || name == 'UDR' || name == 'UDW' || name == 'UDMPRO') {
                    	return
                    }

                    def params = "fw_url=${url}"
                    def job = null
                    if (name == 'UNVR') {
                        job = triggerRemoteJob job: "https://tpe-pbsqa-ci.rad.ubnt.com:8443/job/Debbox/job/UNVR_smoke_entry",
                                               blockBuildUntilComplete: false,
                                               parameters: params,
                                               auth: CredentialsAuth(credentials: 'jenkins8787-trigger')
                    } else {
                        job = triggerRemoteJob job: "https://tpe-pbsqa-ci.rad.ubnt.com:8443/job/Debbox/job/${name}_smoke_test",
                                               blockBuildUntilComplete: false,
                                               parameters: params,
                                               auth: CredentialsAuth(credentials: 'jenkins8787-trigger')
                    }
                    
                    // currentBuild.result = job.getBuildResult().toString()
                }
            }

        ])
    }
    return build_jobs
}

def disk_smart_mon_builder(String productSeries, Map job_options=[:], Map build_series=[:]) {
    echo "build $productSeries"
    return debpkg(job_options, ['stretch/arm64', 'buster/arm64'])
}

def disk_quota_builder(String productSeries, Map job_options=[:], Map build_series=[:]) {
    echo "build $productSeries"
    return debpkg(job_options, ['stretch/arm64', 'buster/arm64'])
}

def analytic_report_builder(String productSeries, Map job_options=[:], Map build_series=[:]) {
    echo "build $productSeries"
    return debpkg(job_options, ['stretch/arm64', 'buster/arm64'])
}

def debbox_base_files_builder(String productSeries, Map job_options=[:], Map build_series=[:]) {
    echo "build $productSeries"
    return debpkg(job_options, ['stretch/all', 'buster/all'])
}

def cloudkey_apq8053_initramfs_builder(String productSeries, Map job_options=[:], Map build_series=[:]) {
    echo "build $productSeries"
    return debpkg(job_options, ['stretch/all', 'buster/all'])
}

def ubnt_archive_keyring_builder(String productSeries, Map job_options=[:], Map build_series=[:]) {
    echo "build $productSeries"
    return debpkg(job_options, ['stretch/all', 'buster/all'])
}

def ubnt_zram_swap_builder(String productSeries, Map job_options=[:], Map build_series=[:]) {
    echo "build $productSeries"
    return debpkg(job_options, ['stretch/all', 'buster/all'])
}

def ubnt_tools_builder(String productSeries, Map job_options=[:], Map build_series=[:]) {
    echo "build $productSeries"

    // stretch/amd64 could not build successfully
    return debpkg(job_options, ['stretch/arm64', 'buster/arm64'])
}
/*
 * A general package build function
 * job_options must contains the following params (key-value)
 * name: project name
 * dist: path of output dir  (default is "dist")
 *
 */
def debpkg(Map job_options, configs=['stretch/all']) {
    def build_jobs = []

    verify_required_params('debpkg', job_options, ['name'])

    configs.each { config ->
        def extra = ''
        def artifact_prefix = config
        def (distribution, arch) = config.split('/')
        def builder = "$distribution-arm64"

        if (arch != 'all') {
            builder = "${distribution}-${arch}"
            extra = "DEB_TARGET_ARCH=${arch}"
        }

        build_jobs.add([
            node: job_options.node ?: 'fwteam',
            name: job_options.name + "-${builder}",
            artifact_dir: job_options.artifact_dir ?: "${env.JOB_BASE_NAME}_${env.BUILD_TIMESTAMP}_${env.BUILD_NUMBER}_${builder}",
            dist: job_options.dist ?: 'dist',
            execute_order: 1,
            upload: job_options.upload ?: false,
            non_cross: job_options.non_cross ?: false,
            pre_checkout_steps: { m->
                m.build_dir = "${m.name}-${env.BUILD_NUMBER}-${env.BUILD_TIMESTAMP}"
            },
            build_steps: { m ->
                sh "mkdir -p ${m.artifact_dir}"
                m.absolute_artifact_dir = sh_output("readlink -f ${m.artifact_dir}")

                def deleteWsPath
                ws("${m.build_dir}") {
                    deleteWsPath = env.WORKSPACE
                    def dockerImage = docker.image("debbox-builder-cross-${builder}:latest")

                    if (m.non_cross) {
                        if (builder != 'stretch-arm64') { // TODO qemu buster builder
                            return false
                        }
                        dockerImage = docker.image('debbox-builder-qemu-stretch-arm64:latest')
                    }

                    dockerImage.inside(get_docker_args(m.absolute_artifact_dir)) {
                        sh 'pwd'
                        def co_map = checkout scm
                        sh 'ls -alhi'
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
                        try {
                            bash "make package RELEASE_BUILD=${is_atag} ${extra} 2>&1 | tee make.log"
                        }
                        catch (Exception e) {
                            throw e
                        }
                        finally {
                            sh "mkdir -p /root/artifact_dir/${distribution}"
                            sh "cp -rT ${m.dist} /root/artifact_dir/${distribution} || true"
                            sh 'chmod -R 777 . /root/artifact_dir/'
                            sh "mv make.log /root/artifact_dir/${distribution} || true"
                            dir_cleanup("${deleteWsPath}") {
                                echo "cleanup ws ${deleteWsPath}"
                                deleteDir()
                            }
                        }
                    }
                }
                return true
            },
            archive_steps: { m ->
                stage("Artifact ${m.name}") {
                    if (fileExists("$m.artifact_dir/${distribution}/make.log")) {
                        archiveArtifacts artifacts: "${m.artifact_dir}/**"
                        if (m.upload && m.containsKey('upload_info')) {
                            def upload_path = m.upload_info.path.join('/')
                            def latest_path = m.upload_info.latest_path.join('/')
                            def ref_path = m.upload_info.ref_path.join('/')

                            if (m.git_args.is_tag) {
                                writeFile file:'pkg-arrange2.py', text:libraryResource("pkg-arrange2.py")
                                sh "mkdir -p ${ubnt_nas.get_nasdir()}/${ref_path}/../.makefile/${distribution}"
                                sh "mkdir -p ${ubnt_nas.get_nasdir()}/${ref_path}/../.makefile.bkp/${distribution}"
                                def makefile_path = sh_output("realpath ${ubnt_nas.get_nasdir()}/${ref_path}/../.makefile/${distribution}")
                                def makefile_bkp_path = sh_output("realpath ${ubnt_nas.get_nasdir()}/${ref_path}/../.makefile.bkp/${distribution}")
                                sh "python3 ./pkg-arrange2.py " +
                                    "-o ${makefile_path} " +
                                    "-c ${makefile_bkp_path} " +
                                    "-d ${distribution} " +
                                    "-u ${ubnt_nas.get_nasdomain()}/${upload_path} " +
                                    "${m.artifact_dir}/"
                            }

                            println "upload: $upload_path, artifact_path: ${m.artifact_dir}/* latest_path: $latest_path"
                            ubnt_nas.upload("${m.artifact_dir}/*", upload_path, latest_path)
                        }
                    }
                }
            }
        ])
    }
    return build_jobs
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
            node: job_options.node ?: 'fwteam',
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
                sh "mkdir -p ${m.artifact_dir}/${m.artifact_prefix}"
                m.absolute_artifact_dir = sh_output("readlink -f ${m.artifact_dir}/${m.artifact_prefix}")

                def deleteWsPath
                ws("${m.build_dir}") {
                    deleteWsPath = env.WORKSPACE
                    def dockerImage = docker.image('debbox-builder-cross-stretch-arm64:latest')

                    dockerImage.inside(get_docker_args(m.absolute_artifact_dir)) {
                        sh 'pwd'
                        def co_map = checkout scm
                        sh 'git submodule update --init --recursive'
                        sh 'ls -alhi'
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
                            sh 'rm -rf /root/artifact_dir/input || true'
                            dir_cleanup("${deleteWsPath}") {
                                echo "cleanup ws ${deleteWsPath}"
                                deleteDir()
                            }
                        }
                    }
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
    def config = [:]

    verify_required_params('mt7622_boot_builder', job_options, ['name'])

    build_jobs.add([
        node: job_options.node ?: 'fwteam',
        name: "mt7622-boot",
        artifact_dir: job_options.artifact_dir ?: "${env.JOB_BASE_NAME}_${env.BUILD_TIMESTAMP}_${env.BUILD_NUMBER}",
        dist: 'u-boot-mtk.bin',
        execute_order: 1,
        upload: job_options.upload ?: false,
        pre_checkout_steps: { m->
            m.artifact_prefix = m.name
            m.build_dir = "${m.name}-${env.BUILD_NUMBER}-${env.BUILD_TIMESTAMP}"
        },
        build_steps: { m ->
            sh "mkdir -p ${m.artifact_dir}/${m.artifact_prefix}"
            m.absolute_artifact_dir = sh_output("readlink -f ${m.artifact_dir}/${m.artifact_prefix}")

            def deleteWsPath
            ws("${m.build_dir}") {
                deleteWsPath = env.WORKSPACE
                def dockerImage = docker.image('debbox-builder-cross-stretch-arm64:latest')

                dockerImage.inside(get_docker_args(m.absolute_artifact_dir)) {
                    sh 'pwd'
                    def co_map = checkout scm
                    sh 'ls -alhi'
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
                        bash "ln -f config-udr .config"
                        bash "MENUCONFIG_SAVE_IMMEDIATELY=1 make menuconfig 2>&1 | tee make.log"
                        bash "make -j8 2>&1 | tee make.log"
                    }
                    catch (Exception e) {
                        throw e
                    }
                    finally {
                        sh 'chmod -R 777 .'
                        sh "cp ${m.dist} /root/artifact_dir || true"
                        sh 'mv make.log /root/artifact_dir'
                        sh 'rm -rf /root/artifact_dir/input || true'
                        dir_cleanup("${deleteWsPath}") {
                            echo "cleanup ws ${deleteWsPath}"
                            deleteDir()
                        }
                    }
                }
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
                    }
                }
            }
        }
    ])


    return build_jobs
}

def preload_image_builder(String productSeries, Map job_options=[:], Map build_series=[:]) {
    def build_jobs = []
    echo "build $productSeries"

    build_jobs.add([
        node: job_options.node ?: 'fwteam',
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
        node: job_options.node ?: 'fwteam',
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
            sh "mkdir -p ${m.artifact_dir}/stretch/arm64"
            m.absolute_artifact_dir = sh_output("readlink -f ${m.artifact_dir}/stretch/arm64")

            def return_status = false
            dir("${m.build_dir}/ustd") {
                stage('checkout source') {
                    sh 'pwd'
                    checkout scm
                    sh 'ls -alhi'
                }
                stage('pylint check error') {
                    def dockerImage = docker.image('debbox-builder-cross-stretch-arm64:latest')
                    dockerImage.inside(get_docker_args(m.absolute_artifact_dir)) {
                        sh 'apt-get update && apt-get install python3-systemd python3-cryptography -y'
                        sh 'pylint3 -E ustd/*.py ustd/*/*.py'
                    }
                }
            }

            dir("${m.build_dir}/debfactory") {
                dockerImage = docker.image('debbox-builder-qemu-stretch-arm64:latest')
                stage('checkout debfactory helper') {
                    git branch: 'master',
                        credentialsId: 'ken.lu-ssh',
                        url: 'git@github.com:ubiquiti/debfactory.git'
                    sh 'mkdir -p ./source && cp -rl ../ustd ./source/ustd'
                }

                stage('build deb package') {
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
            }
        }
    ])

    return build_jobs
}
