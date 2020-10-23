def notification(String buildResult)
{
	if (buildResult == "SUCCESS") {
		slackSend color: "good", message: "Job: ${env.JOB_NAME} buildnumber: ${env.BUILD_NUMBER} was successful"
	} else {
		slackSend color: "danger", message: "Job: ${env.JOB_NAME} buildnumber: ${env.BUILD_NUMBER} was failed"
	}
}
