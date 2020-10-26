def notification(String buildResult, slackBody  = "")
{
	msg = "Job: ${env.JOB_NAME} buildnumber: ${env.BUILD_NUMBER}"
	if (buildResult == "SUCCESS") {
		msg += " was successful\n"
		msg += slackBody
		slackSend color: "good", message: "${msg}"
	} else {
		msg += " was failed\n"
		msg += slackBody
		slackSend color: "danger", message: "${msg}"
	}
}
