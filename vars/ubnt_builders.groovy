def bash(String cmd) { sh("#!/usr/bin/env bash\nset -euo pipefail\n${cmd}") }

def get_job_options(String project)
{
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
			node: 'fwteam_arm64',
			build_archs: ['arm64'],
			upload: true
		],
		analytic_report_builder:[
			name: 'analytic_report',
			node: 'fwteam',
			upload: true
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
		preload_image_builder:[
			name: 'preload_image',
			node: 'fwteam',
			upload: true
		]
	]

	return options.get(project, [:])

}
/*
 * resultpath: the output dir that indicate where the binary generated
 * artifact_dir: this is the directory created in our build machine for storing the binary (relative path for artifact)
 * build_archs can be self defined
 */
def debfactory_builder(String productSeries, Map job_options=[:], Map build_series=[:])
{
	def build_jobs = []
	verify_required_params("debfactory_builder", job_options, [ 'build_archs' ])
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
			resultpath: "build/${build_dist}-${arch}",
			execute_order: 1,
			artifact_dir: job_options.job_artifact_dir ?: "${env.JOB_BASE_NAME}_${env.BUILD_TIMESTAMP}_${env.BUILD_NUMBER}",
			build_record: job_options.build_record ?: false,
			arch: arch,
			build_status:false,
			upload: job_options.upload ?: false,
			build_steps:{ m->
				sh "export"
				def buildPackages = []
				m.pkginfo = [:]
				stage ("checkout $m.name") {
					m.build_dir = "${m.name}-${env.BUILD_NUMBER}-${env.BUILD_TIMESTAMP}"
					sh "mkdir -p ${m.artifact_dir}"
					m.absolute_artifact_dir = sh_output("readlink -f ${m.artifact_dir}")

					dir("$m.build_dir") {
						def co_map = checkout scm
						def url = co_map.GIT_URL
						def git_args = git_helper.split_url(url)
						def repository = git_args.repository

						echo "URL: ${url} -> site: ${git_args.site} " + "owner:${git_args.owner} repo: ${repository}"
						git_args.revision = git_helper.sha()
						git_args.rev_num = git_helper.rev()

						def is_pr = env.getProperty("CHANGE_ID") != null
						def is_tag = env.getProperty("TAG_NAME") != null
						def is_atag = env.getProperty("TAG_NAME") != null

						if (is_pr && is_atag) {
							error "Unexpected environment, cannot be both PR and TAG"
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
							if(is_atag) {
								is_release = true
							} else {
								is_release = TAG_NAME.contains("release")
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

						def last_successful_commit = utility.getLastSuccessfulCommit()
						if (env.commitHash && !env.commitHash.isEmpty()) {
							last_successful_commit = env.commitHash
						} else if (!last_successful_commit) {
							last_successful_commit = git_helper.first_commit()
						}
						print last_successful_commit

						if (env.JOB_NAME.startsWith('debfactory-non-cross'))
							sh_output("./pkg-tools.py -nc -lf -rg $last_successful_commit").tokenize('\n').each {
								buildPackages << it
							}
						else {
							sh_output("./pkg-tools.py -lf -rg $last_successful_commit").tokenize('\n').each {
								buildPackages << it
							}
						}
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
							if (env.JOB_NAME.startsWith('debfactory-non-cross')) {
								sh "rm -rf build"
							} else {
								sh "./debfactory clean container && ./debfactory setup"
							}
							def build_list = buildPackages.join(" ")
							m.build_failed = []
							for (pkg in buildPackages) {
								def cmd = 'true'
								if (env.JOB_NAME.startsWith('debfactory-non-cross')) {
									cmd = "make ARCH=$m.arch DIST=$m.dist BUILD_DEPEND=yes $pkg -j8 2>&1"
								} else {
									cmd = "./debfactory build arch=$m.arch dist=$m.dist builddep=yes $pkg 2>&1"
								}
								def status = sh_output.status_code(cmd)
								if (status) {
									m.build_failed << pkg
								}
							}
							println "build_failed pkg: ${m.build_failed}"

							buildPackages.each { pkg ->
								sh_output("find ${m.resultpath} -name ${pkg}_*.deb -printf \"%f\n\"").tokenize('\n').findResults {
									println it
									def list = it.replaceAll(".deb","").tokenize('_')
									if (list.size() == 3) {
										m.pkginfo[it] = [ name: list[0].replaceAll("-dev",""), hash: list[1], arch: list[2] ]
									}
								}
							}
							m.pkginfo.each { pkgname, pkgattr ->
								println "name: ${pkgattr.name} hash: ${pkgattr.hash} arch: ${pkgattr.arch}"
								sh "find ${m.resultpath} -maxdepth 1 -type f -name ${pkgattr.name}* | xargs -I {} cp {} ${m.absolute_artifact_dir}"
							}
						}
						catch(Exception e) {
							m.build_status = false
							throw e
						}
						finally {
							if (!env.JOB_NAME.startsWith('debfactory-non-cross')) {
								sh "./debfactory clean full"
							}
							deleteDir()
							if (m.build_failed.size() == 0) {
								m.build_status = true
							} else {
								println "build failed package: ${m.build_failed}"
							}
							return m.build_status
						}
					}
				}
			},
			archive_steps:{ m->
				if (m.pkginfo.size() > 0) {
					stage("Artifact ${m.name}") {
						archiveArtifacts artifacts: "${m.artifact_dir}/*"
					}
					stage("Upload to server") {
						if(m.upload && m.containsKey('upload_info')) {
							def tmpdir = 'tmppkg'
							sh "mkdir -p $tmpdir"
							tmpdir = sh_output("readlink -f ${tmpdir}")
							m.pkginfo.each { pkgname, pkgattr->
								def upload_prefix = m.upload_info.path.join('/')
								def latest_prefix = m.upload_info.latest_path.join('/')
								sh "ls -alhi ${m.absolute_artifact_dir}/"
								sh "find ${m.absolute_artifact_dir}/ -maxdepth 1 -name ${pkgattr.name}_* | xargs -I {} cp {} ${tmpdir}"
								sh "find ${m.absolute_artifact_dir}/ -maxdepth 1 -name ${pkgattr.name}-* | xargs -I {} cp {} ${tmpdir}"
								def src_path = "$tmpdir/${pkgattr.name}*"
								def dst_path = "${upload_prefix}/${pkgattr.name}/${m.dist}/${pkgattr.arch}/${env.BUILD_TIMESTAMP}_${pkgattr.hash}/"
								def latest_path = "${latest_prefix}/${pkgattr.name}/${m.dist}/${pkgattr.arch}"
								ubnt_nas.upload(src_path, dst_path, latest_path, true)
								if(m.build_record) {
									def ref_path = m.upload_info.ref_path.join('/')
									nas_dir = ubnt_nas.get_nasdir()
									ref_path = "${nas_dir}/${ref_path}"
									dst_path = "${nas_dir}/${dst_path}"
									def relative_path = sh_output("realpath --relative-to=${ref_path} ${dst_path}")
									sh "echo ${pkgattr.name} ${relative_path} >> ${ref_path}/.pkgupdate"
								}
								sh "rm -f $tmpdir/*"
							}
							sh "rm -rf $tmpdir"
						}
					}
				}
			}
		])
	}
	return build_jobs
}

def debfactory_non_cross_builder(String productSeries, Map job_options=[:], Map build_series=[:]) {
	return debfactory_builder(productSeries,job_options, build_series)
}

def debbox_builder(String productSeries, Map job_options=[:], Map build_series=[:])
{
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
			UNX: [product: 'unifi-nx.nvidia', resultpath: 'target-unifi-nx.nvidia', additional_store: ["image/unx-image/Image", "image/unx-image/rootfs.img", "image/unx-image/initrd"]]
		],
		UNIFICORE:
		[
			UCKG2: [product: 'cloudkey-g2.apq8053', resultpath: 'target-cloudkey-g2.apq8053', tag_prefix: 'unifi-cloudkey'],
			UCKP: [product: 'cloudkey-plus.apq8053', resultpath: 'target-cloudkey-plus.apq8053', tag_prefix: 'unifi-cloudkey'],
			UNVR: [product: 'unifi-nvr4-protect.alpine', resultpath:'target-unifi-nvr4.alpine', tag_prefix: 'unifi-nvr'],
			UNVRPRO: [product: 'unifi-nvr-pro-protect.alpine', resultpath:'target-unifi-nvr-pro.alpine', tag_prefix: 'unifi-nvr'],
			// UNVRAI: [product: 'unifi-nvr-ai-protect.alpine', resultpath:'target-unifi-nvr-ai.alpine', tag_prefix: 'unifi-nvr'],
			// UNVRNK: [product: 'unifi-nvr-pro-nk.alpine', resultpath:'target-unifi-nvr-pro.alpine', tag_prefix: 'unifi-nvr'],
			UNVRFCD: [product: 'unifi-nvr4-fcd.alpine', resultpath:'target-unifi-nvr4.alpine', tag_prefix: 'unifi-nvr'],
			UNVRPROFCD: [product: 'unifi-nvr-pro-fcd.alpine', resultpath:'target-unifi-nvr-pro.alpine', tag_prefix: 'unifi-nvr'],
			// UNVRAIFCD: [product: 'unifi-nvr-ai-fcd.alpine', resultpath:'target-unifi-nvr-ai.alpine', tag_prefix: 'unifi-nvr']
			// UNVRNKFCD: [product: 'unifi-nvr-pro-nk-fcd.alpine', resultpath:'target-unifi-nvr-pro-nk.alpine', tag_prefix: 'unifi-nvr']
		]
	]

	if (build_series.size() == 0) {
		build_series = debbox_series.clone()
	}
	verify_required_params("debbox_builder", build_series, [ productSeries ])
	echo "build $productSeries"

	def is_pr = env.getProperty("CHANGE_ID") != null
	def is_tag = env.getProperty("TAG_NAME") != null
	def is_atag = env.getProperty("TAG_NAME") != null
	def build_product = build_series[productSeries]
	def build_jobs = []

	build_product.each { name, target_map ->

		if (is_tag && productSeries == "UNIFICORE" && !TAG_NAME.startsWith(target_map.tag_prefix)) {
			return
		}

		build_jobs.add([
			node: job_options.node ?: 'debbox',
			name: target_map.product,
			resultpath: target_map.resultpath,
			additional_store: target_map.additional_store ?: [],
			execute_order: 1,
			artifact_dir: job_options.job_artifact_dir ?: "${env.JOB_NAME}_${env.BUILD_TIMESTAMP}_${env.BUILD_NUMBER}_${name}",
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
					m.docker_artifact_path = m.artifact_dir + "/" + m.name

					sh "mkdir -p ${m.build_dir} ${m.docker_artifact_path}"
					m.docker_artifact_path = sh_output("readlink -f ${m.docker_artifact_path}")
				}
				stage("Build ${m.name}") {
					dir_cleanup("${m.build_dir}") {
						def dockerImage
						if (productSeries == "NX") {
							dockerImage = docker.image('registry.ubnt.com.tw:6666/ubuntu:nx')
						} else {
							dockerImage = docker.image('debbox-builder-stretch-arm64:latest')
						}
						dockerImage.inside("-u 0 --privileged=true " + \
							"-v $HOME/.jenkinbuild/.ssh:/root/.ssh:ro " + \
							"-v $HOME/.jenkinbuild/.aws:/root/.aws:ro " + \
							"-v /ccache:/ccache:rw " + \
							"-v $m.docker_artifact_path:/root/artifact_dir:rw " + \
							"--env CCACHE_DIR=/ccache " + \
							"--env PATH=/usr/lib/ccache:/usr/local/sbin:/usr/local/bin:/usr/sbin:/usr/bin:/sbin:/bin") {
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
								error "Unexpected environment, cannot be both PR and TAG"
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
								if(is_atag) {
									is_release = true
								} else {
									is_release = TAG_NAME.contains("release")
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
								withEnv(["AWS_SHARED_CREDENTIALS_FILE=/root/.aws/credentials", "AWS_CONFIG_FILE=/root/.aws/config"]) {
									// if bootloader url is changed please also modify the daily build script together
									def bootloader_url = "\"http://tpe-judo.rad.ubnt.com/build/amaz-alpinev2-boot/heads/master/latest/ubnt_unvr_all-1/boot.img\""
									bash "AWS_PROFILE=default BOOTLOADER=$bootloader_url make PRODUCT=${m.name} RELEASE_BUILD=${is_release} 2>&1 | tee make.log"
								}
								sh "cp make.log /root/artifact_dir/"
								sh "cp -r build/${m.resultpath}/dist/* /root/artifact_dir/"
								sh "cp build/${m.resultpath}/bootstrap/root/usr/lib/version /root/artifact_dir/"
								if (productSeries == "UNVR" || name.contains("UNVR")) {
									sh "cp -r build/${m.resultpath}/image/unvr-image/uImage /root/artifact_dir/"
									sh "cp -r build/${m.resultpath}/image/unvr-image/vmlinux /root/artifact_dir/"
									sh "cp -r build/${m.resultpath}/image/unvr-image/vmlinuz-*-ubnt /root/artifact_dir/"
								}
								m.additional_store.each { additional_file ->
									sh "cp -r build/${m.resultpath}/$additional_file /root/artifact_dir/"
								}
							}
							catch(Exception e) {
								throw e
							}
							finally {
								// In order to cleanup the dl and build directory
								sh "chmod -R 777 ."
								deleteDir()
							}
						}
					}
				}
				return true
			}
		])
	}
	build_product.each { name, target_map ->

		if (is_tag && productSeries == "UNIFICORE" && !TAG_NAME.startsWith(target_map.tag_prefix)) {
			return
		}

		build_jobs.add([
			node: job_options.node ?: 'debbox',
			name: target_map.product + "__UPLOAD",
			product: target_map.product,
			execute_order: 2,
			archive_steps: { m->
				stage("Upload to server") {
					if (m.upload && m.containsKey('upload_info')) {
						def upload_path = m.upload_info.path.join('/')
						def latest_path = m.upload_info.latest_path.join('/')
						m.nasinfo = ubnt_nas.upload(m.docker_artifact_path, upload_path, latest_path)
					}
				}
			},
			archive_cleanup_steps: { m->
				stage("Cleanup archive") {
					try {
						dir_cleanup("${m.docker_artifact_path}") {
							deleteDir()
						}
					}
					catch(Exception e) {
						// do nothing
					}
				}
			}
		])
	}
	build_product.each { name, target_map ->

		if (is_tag && productSeries == "UNIFICORE" && !TAG_NAME.startsWith(target_map.tag_prefix)) {
			return
		}

		build_jobs.add([
			node: job_options.node ?: 'debbox',
			name: target_map.product + "__QA",
			product: target_map.product,
			execute_order: 3,
			qa_test_steps: { m->
				if (m.name.contains("fcd"))
					return
				def url_domain = "http://tpe-judo.rad.ubnt.com/build"
				if (m.containsKey('upload_info')) {
					def upload_path = m.upload_info.path.join('/')
					def relative_path = "${upload_path}/${m.product}/FW.LATEST.bin"
					def build_date = ubnt_nas.get_fw_build_date(relative_path)
					def url = "${url_domain}/${relative_path}"
					echo "url: $url, build_date: $build_date"
					withCredentials([string(
						credentialsId: "UNASHACKER_TOKEN",
						variable:'jobtoken')]) {
						if (name == "UNVR") {
							sh "curl -X POST http://tpe-pbsqa-ci.rad.ubnt.com/job/UNVR-FW-CI-Test/buildWithParameters\\?token\\=UNVR-CI-test\\&url\\=$url\\&date\\=$build_date --user unashacker:$jobtoken"
						} else if (name == "UNVRPRO") {
							json = "\'{\"parameter\": [{\"name\":\"url\", \"value\":\"${url}\"}, {\"name\":\"date\", \"value\":\"${build_date}\"}, {\"name\":\"model\", \"value\":\"UNVR-Pro\"}, {\"name\":\"product\", \"value\":\"unvr\"}]}\'"
							sh "curl -X POST http://tpe-pbsqa-ci.rad.ubnt.com/job/UNVR-Pro-FW-CI-test/build --data-urlencode json=$json --user unashacker:$jobtoken"
						}
					}
				}
			}
		])
	}
	return build_jobs
}

def disk_smart_mon_builder(String productSeries, Map job_options=[:], Map build_series=[:])
{
	echo "build $productSeries"
	return debpkg(job_options, ["stretch/arm64"])
}

def disk_quota_builder(String productSeries, Map job_options=[:], Map build_series=[:])
{
	echo "build $productSeries"
	return debpkg(job_options, ["stretch/arm64"])
}

def analytic_report_builder(String productSeries, Map job_options=[:], Map build_series=[:])
{
	echo "build $productSeries"
	return debpkg(job_options)
}

def debbox_base_files_builder(String productSeries, Map job_options=[:], Map build_series=[:])
{
	echo "build $productSeries"
	return debpkg(job_options)
}

def cloudkey_apq8053_initramfs_builder(String productSeries, Map job_options=[:], Map build_series=[:])
{
	echo "build $productSeries"
	return debpkg(job_options)
}

def ubnt_archive_keyring_builder(String productSeries, Map job_options=[:], Map build_series=[:])
{
	echo "build $productSeries"
	return debpkg(job_options)
}

def ubnt_zram_swap_builder(String productSeries, Map job_options=[:], Map build_series=[:])
{
	echo "build $productSeries"
	return debpkg(job_options)
}

def ubnt_tools_builder(String productSeries, Map job_options=[:], Map build_series=[:])
{
	echo "build $productSeries"

	// stretch/amd64 could not build successfully
	return debpkg(job_options, ["stretch/arm64"])
}
/*
 * A general package build function
 * job_options must contains the following params (key-value)
 * name: project name
 * dist: path of output dir  (default is "dist")
 *
 */
def debpkg(Map job_options, configs=["all"])
{
	def build_jobs = []

	verify_required_params('debpkg', job_options, ['name'])

	configs.each { config ->
		def extra = ''
		def builder = 'stretch-arm64'
		def artifact_prefix = config

		if (config != 'all') {
			def (dist, arch) = config.split('/')
			builder = "${dist}-${arch}"
			extra = "DEB_TARGET_ARCH=${arch}"
		}

		build_jobs.add([
			node: job_options.node ?: "fwteam",
			name: job_options.name,
			artifact_dir: job_options.artifact_dir ?: "${env.JOB_BASE_NAME}_${env.BUILD_TIMESTAMP}_${env.BUILD_NUMBER}",
			dist: job_options.dist ?: 'dist',
			execute_order: 1,
			upload: job_options.upload ?: false,
			pre_checkout_steps: { m->
				m.build_dir = "${m.name}-${env.BUILD_NUMBER}-${env.BUILD_TIMESTAMP}"
			},
			build_steps: { m ->
				sh "mkdir -p ${m.artifact_dir}/${artifact_prefix}"
				m.absolute_artifact_dir = sh_output("readlink -f ${m.artifact_dir}/${artifact_prefix}")

				def deleteWsPath
				ws("${m.build_dir}"){
					deleteWsPath = env.WORKSPACE
					def dockerImage = docker.image('debbox-builder-stretch-arm64:latest');
					def dockerArgs = "-u 0 --privileged=true -v $HOME/.jenkinbuild/.ssh:/root/.ssh:ro -v ${m.absolute_artifact_dir}:/root/artifact_dir:rw"

					dockerImage.inside(dockerArgs) {
						sh "pwd"
						def co_map = checkout scm
						sh "ls -alhi"
						def url = co_map.GIT_URL
						def git_args = git_helper.split_url(url)
						def repository = git_args.repository
						echo "URL: ${url} -> site: ${git_args.site} " + "owner:${git_args.owner} repo: ${repository}"
						git_args.revision = git_helper.sha()
						git_args.rev_num = git_helper.rev()

						def is_pr = env.getProperty("CHANGE_ID") != null
						def is_atag = env.getProperty("TAG_NAME") != null
						if (is_pr && is_atag) {
							error "Unexpected environment, cannot be both PR and TAG"
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
						catch(Exception e) {
							throw e
						}
						finally {
							sh "chmod -R 777 ."
							sh "cp -rT ${m.dist} /root/artifact_dir || true"
							sh "mv make.log /root/artifact_dir || true"
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
					if (fileExists("$m.artifact_dir/${artifact_prefix}/make.log")) {
						archiveArtifacts artifacts: "${m.artifact_dir}/**"
						if (m.upload && m.containsKey('upload_info')) {
							def upload_path = m.upload_info.path.join('/')
							def latest_path = m.upload_info.latest_path.join('/')
							println "upload: $upload_path ,artifact_path: ${m.artifact_dir}/* latest_path: $latest_path"
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

def amaz_alpinev2_boot_builder(String build_target, Map job_options=[:], Map build_config=[:])
{
	def build_jobs = []
	def config = [:]

	verify_required_params('amaz_alpinev2_boot_builder', job_options, ['name'])

	// amaz_alpinev2_boot_config = [ubnt_nas:'5', ubnt_nas_pro:'2', ubnt_nas_ai:'3', ubnt_unvr_all:'1']
	amaz_alpinev2_boot_config = [ubnt_unvr_all:'1']


	if (build_config.size() > 0) {
		if(build_config.containsKey(build_target)) {
			config.put(build_target, build_config[build_target])
		} else {
			config = build_config.clone()
		}
	} else {
		if(amaz_alpinev2_boot_config.containsKey(build_target)) {
			config.put(build_target, amaz_alpinev2_boot_config[build_target])
		} else {
			config = amaz_alpinev2_boot_config.clone()
		}
	}
	echo "build $build_target $config"
	config.each { model, hw_ver->
		build_jobs.add([
			node: job_options.node ?: "fwteam",
			name: model,
			artifact_dir: job_options.artifact_dir ?: "${env.JOB_BASE_NAME}_${env.BUILD_TIMESTAMP}_${env.BUILD_NUMBER}",
			dist: 'output',
			execute_order: 1,
			upload: job_options.upload ?: false,
			pre_checkout_steps: { m->
				m.artifact_prefix = "$model-$hw_ver"
				m.build_dir = "${m.name}-${env.BUILD_NUMBER}-${env.BUILD_TIMESTAMP}"
			},
			build_steps: { m ->
				sh "mkdir -p ${m.artifact_dir}/${m.artifact_prefix}"
				m.absolute_artifact_dir = sh_output("readlink -f ${m.artifact_dir}/${m.artifact_prefix}")

				def deleteWsPath
				ws("${m.build_dir}"){
					deleteWsPath = env.WORKSPACE
					def dockerImage = docker.image('debbox-builder-stretch-arm64:latest');
					def dockerArgs = "-u 0 --privileged=true -v $HOME/.jenkinbuild/.ssh:/root/.ssh:ro -v ${m.absolute_artifact_dir}:/root/artifact_dir:rw"

					dockerImage.inside(dockerArgs) {
						sh "pwd"
						def co_map = checkout scm
						sh "ls -alhi"
						def url = co_map.GIT_URL
						def git_args = git_helper.split_url(url)
						def repository = git_args.repository
						echo "URL: ${url} -> site: ${git_args.site} " + "owner:${git_args.owner} repo: ${repository}"
						git_args.revision = git_helper.sha()
						git_args.rev_num = git_helper.rev()

						def is_pr = env.getProperty("CHANGE_ID") != null
						def is_atag = env.getProperty("TAG_NAME") != null
						def is_tag = env.getProperty("TAG_NAME") != null
						if (is_pr && is_atag) {
							error "Unexpected environment, cannot be both PR and TAG"
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
							bash "./release.sh $model $hw_ver 2>&1 | tee make.log"
						}
						catch(Exception e) {
							throw e
						}
						finally {
							sh "chmod -R 777 ."
							sh "cp -rT ${m.dist} /root/artifact_dir || true"
							sh "mv make.log /root/artifact_dir"
							sh "rm -rf /root/artifact_dir/input || true"
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
							if(m.upload) {
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

def preload_image_builder(String productSeries, Map job_options=[:], Map build_series=[:])
{
	def build_jobs = []
	echo "build $productSeries"

	build_jobs.add([
		node: job_options.node ?: "fwteam",
		name: job_options.name,
		artifact_dir: job_options.artifact_dir ?: "${env.JOB_BASE_NAME}_${env.BUILD_TIMESTAMP}_${env.BUILD_NUMBER}/arm64",
		execute_order: 1,
		upload: job_options.upload ?: false,
		pre_checkout_steps: { m->
			m.build_dir = "${m.name}-${env.BUILD_NUMBER}-${env.BUILD_TIMESTAMP}"
			m.upload_info = [
				path: ["${m.name}", "arm64", "${m.build_dir}"],
				latest_path: ["${m.name}", "arm64", "latest"]
			]
		},
		build_steps: { m ->
			sh "mkdir -p ${m.artifact_dir}"
			m.artifact_dir_absolute_path = sh_output("readlink -f ${m.artifact_dir}")
			def deleteWsPath
			ws("${m.build_dir}") {
				def co_map = checkout scm
				deleteWsPath = env.WORKSPACE
				bootload_path = "/home/dio/builder/amaz-alpinev2-boot/${env.ub_path}/latest/ubnt_unvr_all-1/boot.img"
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
				catch(Exception e) {
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
						if(m.upload) {
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
