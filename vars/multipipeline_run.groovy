sz = 0
def call(String project, String build_target, Map build_series=[:], Map job_options=[:]) {
    // define job properties here
    if ( project == "debfactory_builder" || 
        project == "debfactory_non_cross_builder" ||
        project == "debbox_builder" ) {
        properties([disableConcurrentBuilds()])
        //properties([disableConcurrentBuilds(abortPrevious: true)])
    }
    timestamps {
        def parallel_jobs = [:]

        // fill job options here
        job_options << ubnt_builders.get_job_options(project)

        println "running ${project} productSeries: ${build_target}"
        println "job_options: $job_options"

        def build_jobs = ubnt_builders."${project}"(build_target, job_options, build_series)

        if (build_jobs.size() == 0) {
            return
        }

        for (build_job in build_jobs) {
            parallel_jobs[build_job.name] = ubnt_general_build(build_job)
        }

        try {
            def execute_orders = parallel_jobs.collect { it.value.build_job.execute_order }
            def max_execute_order = execute_orders.max { it }
            def min_execute_order = execute_orders.min { it }

            for (curr_execute_order = min_execute_order;
                curr_execute_order <= max_execute_order;
                curr_execute_order++) {
                def current_jobs = parallel_jobs.collectEntries { k, v ->
                    if (v.build_job.execute_order == curr_execute_order) {
                        return [k, v.closure]
                    }
                    return [:]
                }
                if (current_jobs.size() > 0) {
                    sz = current_jobs.size()
                    println "current_job.size() = $sz"
                    if (curr_execute_order == 1 && project == "debbox_builder") {
                        current_jobs = current_jobs.plus([failFast: true])
                    }
                    parallel current_jobs
                }
            }
        } catch (Exception e) {
            throw e
        } finally {
            // TODO: notification or something else
            def job_names = parallel_jobs.keySet().sort()
            def mail_body = ''
            def project_build_status = 'Success'
            def tag_build = false
            def jobDesc = ''
            def slackBody = ''
            def job_build_status = currentBuild.currentResult

            job_names.each { k->
                def build_job = parallel_jobs[k].build_job
                if (build_job.is_release) {
                    tag_build = true
                }

                if (!build_job.name.contains('__')) {
                    if (build_job.build_status == false) {
                        job_build_status = 'FAILED'
                        project_build_status = 'Failed'
                    } else {
                        project_build_status = 'Success'
                    }
                    slackBody += ">- ${build_job.name}: ${project_build_status}\n"
                }

                mail_body = mail_body + build_job.name + '--- ' +  build_job.build_status + '\n'

                if (build_job.containsKey('nasinfo') && build_job.name == 'upload') {
                    def arrange_nasinfo = [:]
                    println(build_job.nasinfo)
                    build_job.nasinfo.each { url, name ->
                        // product: link
                        def url_token = url.tokenize('/')
                        def display_name = 'artifact'
                        if(url_token.size() >= 2) {
                            display_name = url_token[-2]
                        }
                        if(!arrange_nasinfo.containsKey(display_name)) {
                            arrange_nasinfo[display_name] = [:]
                        }
                        arrange_nasinfo[display_name][url] = name
                    }
                    println(arrange_nasinfo)

                    arrange_nasinfo.each { display_name, entry -> 
                        jobDesc += "<h5> ${display_name} <h5>"
                        entry.each { url, name ->
                            jobDesc += "<a href=\"${url}\">${name}</a>"
                            jobDesc += '<br>'
                        }
                    }
                }
            }

            if (job_options.containsKey('slackNotify') && job_options.slackNotify && tag_build) {
                slack_helper.notification(job_build_status, slackBody)
            }

            // if (tag_build || (job_options.containsKey('mail') && job_options.mail)) {
            //     // mail notification
            //     mail bcc:'', cc:'', from:'', to:'steve.chen@ui.com',replyTo:'', subject: "${env.JOB_NAME}-${env.BUILD_NUMBER}--${project_build_status}", body: "${mail_body}"
            //     // mail bcc: '', body: "$m.branch_name", cc: '', from: '', replyTo: '', subject: 'test Mail', to: 'steve.chen@ui.com'
            // }

            currentBuild.setDescription jobDesc
        }
    } // timestamps
}
