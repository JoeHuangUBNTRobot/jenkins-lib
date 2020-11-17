def call(Map args) {
    verify_required_params('ubnt_general_build', args, ['node', 'name'])

    def m = [ws: '', is_atag: false]
    m << args

    if (m.node) {
        def node_label = m.node
        get_node = { b->
            node(node_label) {
                b()
            }
        }
    } else {
        def node_label = 'fwteam'
        m.node = ${ node_label }
        get_node = { b->
            node(node_label) {
                b()
            }
        }
    }

    return [build_job: m, closure: {
            get_node {
                try {
                    echo "ubnt_general_build run ${m.name}"
                    stage("Running ${m.name} build process") {
                        // do pre_checkout_steps
                        if (m.containsKey('pre_checkout_steps')) {
                            m['pre_checkout_steps'](m)
                        }
                        // do build_steps
                        if (m.containsKey('build_steps')) {
                            m.build_status = m['build_steps'](m)
                        }
                    }
                } catch (Exception e) {
                    echo "Caught build Exception ${e}"
                    m.build_status = false
                    throw e
                } finally {
                    try {
                        if (m.containsKey('archive_steps')) {
                            m['archive_steps'](m)
                        }
                        if (m.containsKey('archive_cleanup_steps')) {
                            m['archive_cleanup_steps'](m)
                        }
                        if (m.containsKey('qa_test_steps')) {
                            stage("QA-Test ${m.name}") {
                                m['qa_test_steps'](m)
                            }
                        }
                    } catch (Exception e) {
                        echo "Caught archieve Exception ${e}"
                        throw e
                    } finally {
                        if (m.is_atag) {
                            // mail notification
                            mail bcc: '', body: "$m.branch_name", cc: '', from: '', replyTo: '', subject: 'test Mail', to: 'steve.chen@ui.com'
                        }
                    }
                }
            }
        }]
}
