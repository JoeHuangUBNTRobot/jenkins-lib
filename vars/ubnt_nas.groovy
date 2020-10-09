def generate_buildinfo(Map git_args) {
	println git_args
	verify_required_params('generate_buildinfo', git_args, ['repository', 'is_pr', 'is_tag', 'ref', 'rev_num'])
	def output = [:]
	println git_args
	def ref_path = [] + git_args.repository
	def latest_path = [] + git_args.repository
	def ref = git_args.ref
	def ref_sha = git_helper.sha(ref)
	def email_cmd = "git log --pretty=format:%ae -1 ${ref_sha}"
	def date_cmd = "git log --date=format:%F_%H%M%S --pretty=format:%ad -1 ${ref_sha}"

	if(git_args.is_tag) {
		if (git_args.is_atag) {
			email_cmd = "git tag -l --format='%(taggeremail)' ${ref}"
			date_cmd = "git tag -l --format='%(taggerdate:format:%F_%H%M%S)' ${ref}"
		}
		ref_path = ref_path + 'tags'+ ref
		latest_path = latest_path + 'tags' + ref + 'latest'
	} else if (git_args.is_pr) {
		ref_path = ref_path + 'prs' + "PR-${env.CHANGE_ID}"
		latest_path = latest_path + 'prs' + "PR-${env.CHANGE_ID}" + 'latest'
	} else {
		def branch = ref.replaceAll("^origin/", "")
		ref_path = ref_path + 'heads' + branch
		latest_path = latest_path + 'heads' + branch + 'latest'
	}

	println "PATH" + ref_path.join('/')

	def email = sh_output(email_cmd).replaceAll("(^<|>\$)", "")
	def date = sh_output(date_cmd)
	def username = safe_regex(email, /^\d*\+?(.*)@(tpe-unifi-sqa.rad.ubnt|ui|ubnt|users.noreply.github).com$/).with { it ? it[0][1] : email }
	def short_sha = git_helper.short_sha(ref_sha)

	def output_dir = [git_args.rev_num, BUILD_NUMBER, date, username, git_helper.short_sha(ref_sha)].join('_')
	output.path = ref_path + output_dir
	output.latest_path = latest_path
	return output
}


def upload(src_path, dst_path, latest_path, link_subdir = false)
{
	def nasinfo = [:]
	def nasdomain = "http://tpe-judo.rad.ubnt.com/build"
	def nasdir = "$HOME/builder"
	def notmounted = sh_output.status_code("mountpoint -q $nasdir")
	if(!notmounted) {
		def nas_path = "$nasdir/$dst_path"
		latest_path = "$nasdir/$latest_path"
		println "upload from $src_path to $nas_path"
		sh "mkdir -p $nas_path"
		sh "cp -rp $src_path $nas_path"
		if (nas_path.contains("firmware.debbox")) {
			def src_basename = src_path.tokenize("/").pop()
			def output_path = sh_output("realpath ${nas_path}/${src_basename}/* || true")
			output_path.split('\n').each {
				artifact_name = it.tokenize("/").pop()
				artifact_url = it.replace(nasdir, nasdomain)
				nasinfo[artifact_name] = artifact_url
			}
			println "nasinfo: $nasinfo"
		}
		if (link_subdir) {
			sh "mkdir -p $latest_path"
			sh "for subdir in $nas_path/*; do ln -srf -t $latest_path \$subdir; done"
		} else {
			sh "ln -srfT $nas_path $latest_path"
		}
	}
}

def get_fw_build_date(project_name, product_name)
{
	def nasdir = "$HOME/builder"
	def fw_path = "$nasdir/$project_name/latest_tag/$product_name/FW.LATEST.bin"
	fw_path = sh_output("readlink -f $fw_path")
	def fw_name = fw_path.tokenize("/").pop()
	def build_date_pattern = ~/(\d+)\.(\d+)\.bin$/
	def matcher = (fw_name =~ build_date_pattern)
	if (matcher.size() == 1) {
		return matcher[0][1] +'.'+matcher[0][2]
	}
	return
}
