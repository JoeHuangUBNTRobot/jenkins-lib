def call(String project, String build_target, Map build_series=[:], Map job_options=[:]) {
	
	timestamps {
		def parallel_jobs = [:]
		
		// fill job options here
		job_options << ubnt_builders.get_job_options(project)

		println "running ${project} productSeries: ${build_target}"
		println "job_options: $job_options"

		def build_jobs = ubnt_builders."${project}"(build_target, job_options, build_series)

		println "job_options: $build_jobs"
		
		for (build_job in build_jobs) {
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
			// TODO: notification or something else 
			def job_names = parallel_jobs.keySet().sort()
			def mail_body=''
			def project_build_status='Success'
			def tag_build=false
			job_names.each{ k-> 
				def build_job = parallel_jobs[k].build_job
				if (build_job.build_status == false) {
					project_build_status = 'Failed'
				}
				if(build_job.is_atag) {
					tag_build = true
				}
				mail_body = mail_body + build_job.name + '--- ' +  build_job.build_status + '\n'
			}

			if (tag_build || (job_options.containsKey('mail') && job_options.mail)) {
				// mail notification
				mail bcc:'', cc:'', from:'', to:'steve.chen@ui.com',replyTo:'', subject: "${env.JOB_NAME}-${env.BUILD_NUMBER}--${project_build_status}", body: "${mail_body}"
				// mail bcc: '', body: "$m.branch_name", cc: '', from: '', replyTo: '', subject: 'test Mail', to: 'steve.chen@ui.com'
			}

		}
	} // timestamps
}
