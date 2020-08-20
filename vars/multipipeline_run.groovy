def call(String project, String build_target, Map build_series=[:]) {
	
	timestamps {
		def job_options = [:]
		def parallel_jobs = [:]
		def job_artifact_dir=""
		
		// fill job options here
		job_options = ubnt_builders.get_job_options(project)

		println "running ${project} productSeries: ${build_target}"
		println "job_options: $job_options"

		if(job_options.containsKey('job_artifact_dir') {
			job_artifact_dir = job_options['job_artifact_dir']
			sh 'mkdir -p $job_artifact_dir'
		}

		def build_jobs = ubnt_builders."${project}"(build_target, job_options, build_series)
		// def build_map = [debbox_builder:{b_t, b_s->debbox_builder(b_t, b_s)}] 
		// def build_jobs = build_map[project](build_target, build_series)
		
		for (build_job in build_jobs) {
			// [build_job: m, closure]
			parallel_jobs[build_job.name] = ubnt_general_build(build_job)
		}
		try {
			def execute_orders = parallel_jobs.collect{ it.value.build_job.execute_order}
			def max_execute_order = execute_orders.max{ it }
			def min_execute_order = execute_orders.min{ it }

			for (curr_execute_order = min_execute_order;
				curr_execute_order <= max_execute_order;
				curr_execute_order++) {
				def current_jobs = parallel_jobs.collectEntries { k, v ->
					if(v.build_job.execute_order == curr_execute_order) {
						return [k, v.closure]
					}
					return [:]
				}

				if(current_jobs.size() > 0) {
					parallel current_jobs
				}
			}
		} catch (Exception e) {
			throw e
		} finally {
			if(job_options.containsKey('job_artifact_dir')) {
				archiveArtifacts artifacts: "$job_artifact_dir/*"
			}

			// TODO: notification or something else 
			// def job_names = parallel_jobs.keySet().sort()
			// // summary row 
			// job_names.each{ k-> 
			// 	def build_job = parallel_jobs[k].build_job
			// }

		}
	}
}