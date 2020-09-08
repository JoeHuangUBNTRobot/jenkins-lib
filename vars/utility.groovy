def getLastSuccessfulCommit() {
	def lastSuccessfulHash = null
	def lastSuccessfulBuild = currentBuild.rawBuild.getPreviousSuccessfulBuild()
	if ( lastSuccessfulBuild ) {
		lastSuccessfulHash = commitHashForBuild( lastSuccessfulBuild )
	}
	return lastSuccessfulHash
}

/**
 * Gets the commit hash from a Jenkins build object, if any
 */
@NonCPS
def commitHashForBuild( build ) {
 	def scmAction = build?.actions.find { action -> action instanceof jenkins.scm.api.SCMRevisionAction }
 	return scmAction?.revision?.hash
}