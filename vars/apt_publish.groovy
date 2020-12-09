def call() {
    def apt_server = 'https://pdx-artifacts.rad.ubnt.com'
    def pkg = env.Package
    def component = env.Component
    def dist = env.Distro //array based
    def upload_repo
    def upload_component
    def deb_platform // dist_arch

    def upload_components = [
        'internal': true,
        'alpha': false,
        'beta': false,
        'release': false
    ]
    def upload_repos = [
        'internal': 'apt-internal',
        'alpha': 'apt-alpha',
        'beta': 'apt-beta',
        'release': 'apt'
    ]
    def upload_dist = [
        'jessie': true,
        'stretch': true,
        'xenial': false,
        'bionic': false
    ]

    upload_component = component
    upload_repo = upload_repos.get(component)

    if (!upload_components.get(upload_component) ||  !upload_repo) {
        println 'no allowed to upload this component !'
    }
    println "dist: $dist"
    dist = dist.tokenize(', ')
    for (i in dist) {
        if (!upload_dist.get(i)) {
            println "no allowed to upload this dist: $i"
        }
    }

    def file = pkg.tokenize('/').pop()
    def ext = file.tokenize('.').pop()
    def filename = file - ".$ext"
    (product, version, arch) = filename.tokenize('_')
    deb_platform = dist.join('~') + '_' + arch

    def apt_string = "${apt_server}/${upload_repo}/pool/${upload_component}/${product[0]}/${product}/${product}.${deb_platform}.v${version}.${ext}"
    def props = [
        'deb.component': upload_component,
        'deb.distribution': dist,
        'deb.arch': arch
    ]
    def apt_props = ''
    props.each { k, v->
        if (v instanceof List) {
            v.each {
                apt_props += ";${k}=${it}"
            }
        } else if (v instanceof String) {
            apt_props += ";${k}=${v}"
        }
    }

    def upload_path = "${apt_string}${apt_props}"
    def file_path = pkg.replace(ubnt_nas.get_nasdomain(), ubnt_nas.get_nasdir())

    println "upload_path: ${upload_path}, file_path: ${file_path}"
    withCredentials([string(credentialsId: 'FWTEAM_APT_TOKEN', variable: 'token'), string(credentialsId: 'FWTEAM_APT_USER', variable: 'user')]) {
        print "user: $user, token: $token\n"
    // res = sh_output("curl -H \"Connection: keep-alive\" -u ${user}:${token} -X PUT -T ${file_path} ${upload_path}")
    // print "\nresponsible: \n${res}"
    }
}
