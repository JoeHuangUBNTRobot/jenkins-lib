def call(String project, String build_target, Map build_series=[:]) {
	
	def parallel_jobs = [:]
	
	println "running ${project} productSeries: ${build_target}"
	def build_jobs = ubnt_builders."${project}"(build_target)
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
		// TODO: notification or something else 
	}
}