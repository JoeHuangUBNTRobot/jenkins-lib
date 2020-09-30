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
 	verify_required_params("debfactory_builder", job_options, [ 'build_archs'])
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
 			arch: arch,
 			build_status:false,
 			upload: job_options.upload ?: false,
 			build_steps:{ m->
 				sh "export"
 				def buildPackages = []
 				def diffPackages = []
 				def pkginfo = [:]
 				stage ("checkout $m.name") {
 					m.build_dir = "${m.name}-${env.BUILD_NUMBER}-${env.BUILD_TIMESTAMP}"
 					sh "mkdir -p ${m.artifact_dir}"
 					m.absolute_artifact_dir = sh_output("readlink -f ${m.artifact_dir}")

 					dir("$m.build_dir") {
 						def packagesName = []
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
 						if(!last_successful_commit) {
 							last_successful_commit = git_helper.first_commit()
 						}
 						print last_successful_commit
 						def fileChanges = git_helper.get_file_changes(last_successful_commit)
 						println "fileChanges: $fileChanges"
 						println "split_lines: "
 						def pattern = ~/package\/\S*\//
 						diffPackages = fileChanges.tokenize('\n').findResults {
 							def matcher = (it =~ pattern)
 							if(matcher.size()) {
 								return matcher[0].tokenize('/')[1]
							} else {
								return null
							}
 						}
 						diffPackages = diffPackages.unique()

						diffPackages.each  {
							def path = "package/$it/Makefile"
							sh_output("grep PKG_NAMES $path").split(':=')[1].split(' ').each {
								packagesName << it
							}
						}
 						println packagesName

						buildPackages = packagesName.each {
							def dependency = sh_output("grep -lr package/ -e $it")
							return dependency.tokenize('\n').findResults {
										def matcher = (it =~ pattern)
										if(matcher.size()) {
											return matcher[0].tokenize('/')[1]
										} else {
											return null
										}
									}
						}
						println "build packages start "
						println buildPackages
						println "build packages end "

						sh 'ls -lahi'
						println "resultpath: $m.resultpath"
						println "artifact_dir: $m.artifact_dir"

 					}
 				}
 				stage("build $m.name") {
 					dir_cleanup("$m.build_dir") {
 						try {
 							sh "./debfactory clean container && ./debfactory setup"
 							def build_list = buildPackages.join(" ")
 							m.build_failed = []
 							for (pkg in buildPackages) {
 								def cmd = "./debfactory build arch=$m.arch dist=$m.dist builddep=yes $pkg 2>&1 >> make.log"
 								def status = sh_output.status_code(cmd)
 								if(status) {
 									m.build_failed << pkg
 								}
 							}
 							println "build_failed pkg: ${m.build_failed}"

							buildPackages.each { pkg ->
								sh_output("find ${m.resultpath} -name ${pkg}_*.deb -printf \"%f\n\"").tokenize('\n').findResults {
									println it
									def list = it.replaceAll(".deb","").tokenize('_')
									if (list.size() == 3) {
										pkginfo[it] = [ name: list[0].replaceAll("-dev",""), hash: list[1], arch: list[2] ]
									}
								}
							}
							pkginfo.each { pkgname, pkgattr ->
								println "name: ${pkgattr.name} hash: ${pkgattr.hash} arch: ${pkgattr.arch}"
								sh "find ${m.resultpath} -maxdepth 1 -type f -name ${pkgattr.name}* | xargs -I {} cp {} ${m.absolute_artifact_dir}"
							}
							m.pkginfo = pkginfo.clone()
						}
						catch(Exception e) {
							m.build_status = false
							throw e
						}
						finally {
							sh "./debfactory clean full"
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
								sh "ls -alhi $tmpdir/"
								sh "find ${m.absolute_artifact_dir}/ -maxdepth 1 -name '${pkgattr.name}*'"
								sh "ls -alhi $tmpdir/"
								def src_path = "$tmpdir/${pkgattr.name}*"
								def dst_path = "${upload_prefix}/${pkgattr.name}/${m.dist}/${pkgattr.arch}/${env.BUILD_TIMESTAMP}_${pkgattr.hash}/"
								def latest_path = "${latest_prefix}/${pkgattr.name}/${m.dist}/${pkgattr.arch}"
								ubnt_nas.upload(src_path, dst_path, latest_path, true)
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

	if (is_tag && TAG_NAME.startsWith("unifi-cloudkey") && productSeries != "UCK") {
		return build_jobs
	} else if (is_tag && TAG_NAME.startsWith("unifi-nvr") && productSeries != "UNVR") {
		return build_jobs
	}

	build_product.each { name, target_map ->
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
							dockerImage = docker.image('debbox-arm64:v3')
						}

						dockerImage.inside("-u 0 --privileged=true -v $HOME/.jenkinbuild/.ssh:/root/.ssh:ro -v $HOME/.jenkinbuild/.aws:/root/.aws:ro -v $m.docker_artifact_path:/root/artifact_dir:rw") {
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
						 	}
						 	m.is_release = is_release
						 	git_args.is_pr = is_pr
						 	git_args.is_tag = is_tag
						 	git_args.is_atag = is_atag
						 	git_args.ref = ref
						 	m['git_args'] = git_args.clone()
						 	m.upload_info = ubnt_nas.generate_buildinfo(m.git_args)
						 	print m.upload_info
						 	withEnv(["AWS_SHARED_CREDENTIALS_FILE=/root/.aws/credentials", "AWS_CONFIG_FILE=/root/.aws/config"]) {
						 		def bootloader_url = "\"http://tpe-judo.rad.ubnt.com/build/amaz-alpinev2-boot/heads/master/latest/ubnt_unvr_all-1/boot.img\""
						 		if (productSeries == "UNVR") {
						 			sh "AWS_PROFILE=default BOOTLOADER=$bootloader_url make PRODUCT=${m.name} RELEASE_BUILD=${is_release} 2>&1 | tee make.log"
						 		}
						 		else {
						 			sh "AWS_PROFILE=default make PRODUCT=${m.name} RELEASE_BUILD=${is_release} 2>&1 | tee make.log"
						 		}

						 	}

						 	sh "cp -r build/${m.resultpath}/dist/* /root/artifact_dir/"
						 	sh "cp make.log /root/artifact_dir/"
						 	if (productSeries == "UNVR") {
						 		sh "cp -r build/${m.resultpath}/image/unvr-image/uImage /root/artifact_dir/"
						 		sh "cp -r build/${m.resultpath}/image/unvr-image/vmlinux /root/artifact_dir/"
						 		sh "cp -r build/${m.resultpath}/image/unvr-image/vmlinuz-4.1.37-ubnt /root/artifact_dir/"
						 	}

						 	m.additional_store.each { additional_file ->
						 		sh "cp -r build/${m.resultpath}/$additional_file /root/artifact_dir/"
						 	}
							// In order to cleanup the dl and build directory
							sh "chmod -R 777 ."
						}
						deleteDir()
					}
				}
				return true
			},
			archive_steps: { m->
				stage("Upload to server") {
					if (m.upload && m.containsKey('upload_info')) {
						def upload_path = m.upload_info.path.join('/')
						def latest_path = m.upload_info.latest_path.join('/')
						ubnt_nas.upload(m.docker_artifact_path, upload_path, latest_path, is_tag)
					}
					if (productSeries == "UNVR") {
						sh "rm -f ${m.docker_artifact_path}/uImage"
						sh "rm -f ${m.docker_artifact_path}/vmlinux"
						sh "rm -f ${m.docker_artifact_path}/vmlinuz-4.1.37-ubnt"
					}
					if (productSeries == "NX") {
						sh "rm -f ${m.docker_artifact_path}/*.bin"
						sh "rm -f ${m.docker_artifact_path}/*.img"
					}
					/*
					m.additional_store.each { additional_file->
						filename = additional_file.tokenize('/').pop()
						sh "rm -f ${m.docker_artifact_path}/$filename"
					}
					*/
				}
				stage("Artifact ${m.name}") {
					archiveArtifacts artifacts: "${m.artifact_dir}/**"
				}
			}
		])
	}
	build_product.each { name, target_map ->
		build_jobs.add([
			node: job_options.node ?: 'debbox',
			name: target_map.product + "-QA",
			product: target_map.product,
			execute_order: 2,
			build_steps: { m ->
				// only UNVR can have the release flag
				m.is_release = false
				if (env.getProperty("TAG_NAME") != null) {
					if (productSeries == "UNVR") {
						if(TAG_NAME.contains("release")) {
							m.is_release = true
						}
					} else {
						m.is_release = true
					}
				}
				return true
			},
			qa_test_steps: { m->
				if (m.name.contains("fcd") || productSeries != "UNVR" || !m.is_release)
					return
				build_date = ubnt_nas.get_fw_build_date('firmware.debbox', m.product)
				url_prefix = "http://tpe-judo.rad.ubnt.com/build/firmware.debbox/latest_tag"
				url = "$url_prefix/${m.name}/FW.LATEST.bin"
				echo "url: $url, build_date: $build_date"
				sh "curl -X POST http://tpe-pbsqa-ci.rad.ubnt.com:8080/job/UNVR-FW-CI-Test/buildWithParameters\\?token\\=UNVR-CI-test\\&url\\=$url\\&date\\=$build_date --user scott:117c7831d9ba3fabf15b0a2b05e71f5cdb"
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
					def dockerImage = docker.image('debbox-arm64:v3');
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

						sh "make package RELEASE_BUILD=${m.is_atag} ${extra} > make.log 2>&1"
						sh "chmod -R 777 ."
						sh "cp -rT ${m.dist} /root/artifact_dir || true"
						sh "mv make.log /root/artifact_dir"
					}
				}

				dir_cleanup("${deleteWsPath}") {
					echo "cleanup ws ${deleteWsPath}"
					deleteDir()
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

	amaz_alpinev2_boot_config = [ubnt_nas:'5', ubnt_nas_pro:'2', ubnt_nas_ai:'3', ubnt_unvr_all:'1']


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
					def dockerImage = docker.image('debbox-arm64:v3');
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

						sh "./release.sh $model $hw_ver > make.log 2>&1"
						sh "chmod -R 777 ."
						sh "cp -rT ${m.dist} /root/artifact_dir || true"
						sh "mv make.log /root/artifact_dir"
						sh "rm -rf /root/artifact_dir/input || true"
					}
				}

				dir_cleanup("${deleteWsPath}") {
					echo "cleanup ws ${deleteWsPath}"
					deleteDir()
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
				bootload_path = "/home/dio/builder/amaz-alpinev2-boot/latest_tag/ubnt_unvr_all-1/boot.img"
				unvr4_fcd_uImage = "/home/dio/builder/firmware.debbox/latest_tag/unifi-nvr4-fcd.alpine/uImage"
				unvr4_preload = "spi-unifi-nvr4-${env.BUILD_TIMESTAMP}.bin"
				unvrpro_fcd_uImage = "/home/dio/builder/firmware.debbox/latest_tag/unifi-nvr-pro-fcd.alpine/uImage"
				unvrpro_preload = "spi-unifi-nvr-pro-${env.BUILD_TIMESTAMP}.bin"
				unvrai_fcd_uImage = "/home/dio/builder/firmware.debbox/latest_tag/unifi-nvr-ai-fcd.alpine/uImage"
				unvrai_preload = "spi-unifi-nvr-ai-${env.BUILD_TIMESTAMP}.bin"

				bootload_path = sh_output("realpath $bootload_path")
				unvr4_fcd_uImage = sh_output("realpath $unvr4_fcd_uImage")
				unvrpro_fcd_uImage = sh_output("realpath $unvrpro_fcd_uImage")
				unvrai_fcd_uImage = sh_output("realpath $unvrai_fcd_uImage")

				sh "echo boot_img: $bootload_path >> make.log"
				sh "echo unvr4_fcd: $unvr4_fcd_uImage >> make.log"
				sh "echo pro_fcd: $unvrpro_fcd_uImage >> make.log"
				sh "echo ai_fcd: $unvrai_fcd_uImage >> make.log"
				sh "./preload_image.py $bootload_path $unvr4_fcd_uImage $unvr4_preload $unvrpro_fcd_uImage $unvrpro_preload $unvrai_fcd_uImage $unvrai_preload"

				sh "mv $unvr4_preload $unvrpro_preload $unvrai_preload ${m.artifact_dir_absolute_path}"
				sh "mv make.log ${m.artifact_dir_absolute_path}"
			}

			dir_cleanup("${deleteWsPath}") {
				echo "cleanup ws ${deleteWsPath}"
				deleteDir()
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
