def call(path, body) {
    try {
        dir(path) {
            body()
        }
    } finally {
        dir(path + '@tmp') {
            deleteDir()
        }
    }
}
