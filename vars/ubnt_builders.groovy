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
			UCKG2: [product: 'cloudkey-g2.apq8053', resultpath: 'target-cloudkey-g2.apq8053'],
			UCKP: [product: 'cloudkey-plus.apq8053', resultpath: 'target-cloudkey-plus.apq8053']
		],
		UNVR: [
			UNVR: [product: 'unifi-nvr4-protect.alpine', resultpath:'target-unifi-nvr4.alpine'],
			UNVRPRO: [product: 'unifi-nvr-pro-protect.alpine', resultpath:'target-unifi-nvr-pro.alpine']
		]
	]

	if (build_series.size() == 0) {
		build_series = debbox_series.clone()
	}
	verify_required_params("debbox_builder", build_series, [ productSeries ])
	echo "build $productSeries"

	def build_product = build_series[productSeries]
	def build_jobs = []

	build_product.each { name, target_map ->

		def artifact
		if(job_options.containsKey('job_artifact_dir')) {
			artifact = job_options.job_artifact_dir
		} else {
			artifact = "${env.JOB_NAME}_${env.BUILD_TIMESTAMP}_${env.BUILD_NUMBER}_${m.name}"
		}

		build_jobs.add([
			node: 'debbox',
			name: target_map.product,
			resultpath: target_map.resultpath,
			execute_order: 1,
			artifact_dir: artifact,
			build_status:false,
			pre_checkout_steps: { m->
				
				// do whatever you want before checkout step
				
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
						docker.image('debbox-arm64:v3').inside("-u 0 --privileged=true -v $HOME/.jenkinbuild/.ssh:/root/.ssh:ro -v $HOME/.jenkinbuild/.aws:/root/.aws:ro -v $m.docker_artifact_path:/root/artifact_dir:rw") {
                            checkout scm 
                            withEnv(["AWS_SHARED_CREDENTIALS_FILE=/root/.aws/credentials", "AWS_CONFIG_FILE=/root/.aws/config"]) {
                                sh "AWS_PROFILE=default make PRODUCT=${m.name} 2>&1 | tee make.log"
                            }
                            sh "cp -r build/${m.resultpath}/dist/${name}* /root/artifact_dir/"
                            sh "cp make.log /root/artifact_dir/"
                            // In order to cleanup the dl and build directory 
                            sh "chmod -R 777 ."
                   		}
                   		deleteDir()
					}
                }
				return true
			},
			archive_steps: { m->
				stage("Artifact ${m.name}") {
					archiveArtifacts artifacts: "${m.artifact_dir}/**"
                }
				// TODO: upload image to our server 
			}
		])

	}
	return build_jobs
}
