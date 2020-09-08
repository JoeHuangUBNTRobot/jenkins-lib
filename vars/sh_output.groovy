// vars/sh_output.groovy
// vim: ts=4 sw=4 expandtab
def call(script, trim = true)
{
    def output = sh (script: script, returnStdout:true)
    if (trim) {
        return output.trim()
    }
    return output
}



def status_code(script, trim=true)
{
	def output = sh (script: script, returnStatus: true)
	return output
	// mountpoint -q -- "$dir"
}