def get_job_options(String project)
{
	def options = [
		debbox_builder: [
			job_artifact_dir: "${env.JOB_NAME}_${env.BUILD_TIMESTAMP}_${env.BUILD_NUMBER}",
			node: 'debbox'
		]
	]
	
	return options.get(project, [:])

}

def debbox_builder(String productSeries, Map job_options=[:], Map build_series=[:])
{
	def debbox_series = [
		UCK: [
			// UCK: 'unifi-cloudkey.mtk',
			UCKG2: 'cloudkey-g2.apq8053',
			UCKP: 'cloudkey-plus.apq8053'
		],
		UNVR: [
			UNVR: 'unifi-nvr4-protect.alpine',
			UNVRPRO: 'unifi-nvr-pro-protect.alpine'
		]
	]

	if (build_series.size() == 0) {
		build_series = debbox_series.clone()
	}
	verify_required_params("debbox_builder", build_series, [ productSeries ])
	echo 'build $productSeries'

	def build_product = build_series[productSeries]
	def build_jobs = []

	build_product.each { name, target ->
		build_jobs.add([
			node: 'debbox',
			name: target,
			execute_order: 1,
			artifact_dir: target,
			pre_checkout_steps: { m->
				stage('pre_checkout_steps') {
					echo "In pre_checkout_steps"
				}
				return true
			},
			build_steps: { m->
				stage("Prepare ${m.name}") {
					
					m.branch_name = env.BRANCH_NAME
					m.build_number = env.BUILD_NUMBER
					m.build_dir = "${m.name}-${m.build_number}"
					if(job_options.containsKey('job_artifact_dir')) {
						// job_artifact_dir is unique
						m.fw_dir = job_options['job_artifact_dir'] + "/${m.name}"
					} else {
						// for unique dir
						m.fw_dir = "${env.JOB_NAME}_${env.BUILD_TIMESTAMP}_${env.BUILD_NUMBER}_${m.name}"
					}
					sh "mkdir -p ${m.build_dir} ${m.fw_dir}"
                    sh "ls -alhi"
					m.fw_dir = sh_output("readlink -f ${m.fw_dir}")
				}
				stage("Build ${m.name}") {
					dir("${m.build_dir}") {
						docker.image('debbox-arm64:v3').inside("-u 0 --privileged=true -v $HOME/.jenkinbuild/.ssh:/root/.ssh:ro -v $HOME/.jenkinbuild/.aws:/root/.aws:ro -v $m.fw_dir:/root/artifact_dir:rw") {
                            checkout scm 
                            withEnv(["AWS_SHARED_CREDENTIALS_FILE=/root/.aws/credentials", "AWS_CONFIG_FILE=/root/.aws/config"]) {
                                sh "AWS_PROFILE=default make PRODUCT=$target 2>&1 | tee make.log"
                            }
                            sh "cp -r build/target-${m.name}/dist/${name}* /root/artifact_dir/"
                            sh "cp make.log /root/artifact_dir/"
                   		}
					}
				}
				return true
			},
			archive_steps: { m->
				echo 'mount nas and cp our fw-image'
				// TODO mount nas
				// sh "tar -zcf ${m.fw_dir}"
			}

		])

	}
	return build_jobs
}
