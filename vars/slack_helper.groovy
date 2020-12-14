def notification(String buildResult, slackBody  = '') {
    title_msg = "${env.BRANCH_NAME} <${env.BUILD_URL}|Build #${env.BUILD_NUMBER}>"
    if (buildResult == 'SUCCESS') {
        title_msg += ' was successful\n'
        title_msg += slackBody
        title_msg += '\ncc <!here>\n'
        slackSend color: 'good', message: "${title_msg}"
    } else {
        title_msg += ' was failed\n'
        title_msg += slackBody
        title_msg += '\ncc <!here>\n'
        slackSend color: 'danger', message: "${title_msg}"
    }
}

def fw_release_changelog() {
    def pkg_list = ['unifi', 'unifi-core', 'unifi-protect', 'ulp-go', 'ulcmd']
    def tag_version = env.TAG_VERSION
    def cc_list = env.CCLIST
    def qa_list = env.QALIST
    def changelog_url = env.Changelog
    def tokenCredentialId = env.Token
    (product, tag) = tag_version.split('/')
    if (product == 'unifi-cloudkey') {
        product = 'UCK'
        branch = 'cloudkey-plus.apq8053'
    } else if (product == 'unifi-nvr') {
        product = 'UNVR'
        branch = 'unifi-nvr-pro-protect.alpine'
    } else {
        return
    }
    def qa_msg = ''
    for (name in qa_list.split("\\r?\\n")) {
        qa_msg = qa_msg + "<@${name}> "
    }
    qa_msg = qa_msg + '\n'

    def title_msg = "${product} ${tag} has been tagged\n"
    def changelog_msg = "Changelog: ${changelog_url}\n"

    def nas_domain = ubnt_nas.get_nasdomain()
    def nas_dir = ubnt_nas.get_nasdir()

    def fw_path = "firmware.debbox/tags/${tag_version}/latest"
    def nas_url = "${nas_domain}/${fw_path}"
    def nas_path = "${nas_dir}/${fw_path}"

    // code block section
    pkg_info = []
    for (pkg in pkg_list) {
        libversion = sh_output("cat ${nas_path}/${branch}/version")
        output = sh_output("awk \'{if (\$1 == \"$pkg\") {print \$1,\$2}}\' ${nas_path}/${branch}/${libversion}.package-list.txt")
        pkg_info = pkg_info + output
    }
    pkg_msg = pkg_info.join('\n')
    def codeblock_msg = "```${pkg_msg}```"

    // end msg section
    def end_msg = "The build can be found in <${nas_url}|here> and the internal channel\n"

    // cc section
    def cc_msg = 'cc '
    for (name in cc_list.split("\\r?\\n")) {
        cc_msg = cc_msg + "<@${name}> "
    }
    cc_msg = cc_msg + '\n'

    slackSend(color: '#199515', message: "${qa_msg} ${title_msg} ${changelog_msg} ${codeblock_msg} ${end_msg} ${cc_msg}", tokenCredentialId: tokenCredentialId)
}
