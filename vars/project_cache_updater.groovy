/*
 * project: <dirname: repo> 
 */
def call() {
    project = [
        "debbox.git": "git@github.com:ubiquiti/debbox.git",
        "debbox-kernel.git":"git@github.com:ubiquiti/debbox-kernel.git"
    ]
    def slaves = get_slave_node()
    parallel(generate_parallel_stage(slaves, project))
}

def get_slave_node() {
    def slaves = []
    for (i=0; i < 2; i++) {
        slaves[i] = "project-cache-updater-$i"
    }
    return slaves
}

def generate_parallel_stage(slaves, project) {
    def parallel_stages=[:]
    for (slave_node in slaves) {
        run_stage = generate_stage(slave_node, project)
        parallel_stages.put("update-$slave_node", run_stage)
    }
    return parallel_stages
}

def generate_stage(slave_node, project) {
    return {
        node(slave_node) {
            def project_cache_dir = get_project_cache_dir()
            project.each {repodir, repo->
                stage('Initial clone') {
                    dir(project_cache_dir) {
                        if(!fileExists("$repodir")) {
                            println "clone repo:$repo"
                            sh_output("git clone --mirror $repo $repodir")
                        }
                    }
                }
                stage('Update cache') {
                    dir("$project_cache_dir/$repodir") {
                        sh_output("git fetch --all --prune")
                    }
                }
            }
        }
    }
}

def get_project_cache_dir() {
    return "$HOME/.project-cache/"
}
