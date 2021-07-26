def call(path, body) {
    try {
        dir(path) {
            body()
        }
    } finally {
        dir(path) {
            echo "cleanup dir ${path}"
            deleteDir()
        }
        sh "rm -rf ${path} || true"
        def tmp_dir = "${path}@tmp"
        dir(tmp_dir) {
            echo "cleanup dir ${tmp_dir}"
            deleteDir()
        }
    }
}
