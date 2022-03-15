/*
 * project: <dirname: repo> 
 */
def call() {
    node('AWS_DEBBOX') {
        def dockerRegistry = ubnt_builders.get_docker_registry()
        def dockerImage = docker.image("$dockerRegistry/debbox-builder-cross-stretch-arm64:latest")
        def docker_args = "-u 0 --privileged=true -v ${get_aws_dir()}:/root/.aws:rw"
        dockerImage.pull()
        dockerImage.inside(docker_args) {
            writeFile file:'aws-rotate.sh', text:libraryResource("aws-rotate.sh")
            sh "chmod 777 ./aws-rotate.sh"
            sh "./aws-rotate.sh default"
            sh "rm ./aws-rotate.sh "
        }
    }
}

def get_aws_dir() {
    def nas_dir = ubnt_nas.get_nasdir()
    return "${nas_dir}/.aws/"
}